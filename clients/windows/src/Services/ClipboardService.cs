using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using ClipBridge.Interop;
using ClipBridge.Models;
using ClipBridge.Utils;

namespace ClipBridge.Services;

/// <summary>一次剪贴板变更的内容快照</summary>
public sealed class ClipboardContent
{
    public required string Kind { get; init; }
    public string? Text { get; init; }
    public byte[]? ImagePng { get; init; }
    public string Hash { get; init; } = "";
}

/// <summary>
/// 剪贴板监听与读写。
///
/// 监听依赖 MessageWindow 收到的 WM_CLIPBOARDUPDATE —— 由系统推送，
/// 不轮询、不需要任何权限。
/// </summary>
public sealed class ClipboardService : IDisposable
{
    private readonly HashCache _cache;
    private readonly Func<bool> _shouldSync;
    private MessageWindow? _window;
    private bool _disposed;

    /// <summary>检测到新的剪贴板内容时触发</summary>
    public event Action<ClipboardContent>? ContentCaptured;

    /// <summary>监听器状态变化（用于 UI 显示）</summary>
    public event Action<bool>? ListenerStateChanged;

    public bool IsListening { get; private set; }

    public ClipboardService(HashCache cache, Func<bool> shouldSync)
    {
        _cache = cache;
        _shouldSync = shouldSync;
    }

    /// <summary>
    /// 启动监听。
    ///
    /// 关于线程：窗口必须建在**最终派发它消息的那个线程**上。这里由调用方
    /// （TrayApplication 构造函数，运行在 UI 线程）创建，之后 WM_CLIPBOARDUPDATE
    /// 由 WinForms 自己的消息泵在 UI 线程上派发到 WndProc —— 这是正确且够用的。
    ///
    /// 早先的实现额外起了一个 STA 线程跑 GetMessage 循环，指望它来接消息。
    /// 那是无效的：消息按窗口所属线程投递，那个线程上没有这个窗口，
    /// GetMessage(NULL) 永远等不到，纯粹是空转的死线程。
    /// </summary>
    public void Start()
    {
        if (IsListening) return;

        // 先登记"启动瞬间剪贴板里已有的内容"。
        // 用户开机自启时，剪贴板里往往还留着上次关机前复制的东西 ——
        // 那属于"以往的复制操作"，不该被同步，所以先把它标成已处理。
        RegisterExistingClipboard();

        _window = new MessageWindow();
        _window.ClipboardUpdated += OnClipboardUpdated;
        _window.StartClipboardListener();

        IsListening = true;
        ListenerStateChanged?.Invoke(true);
    }

    /// <summary>
    /// 把启动时就已经存在于剪贴板的内容登记为"历史内容"，
    /// 这样它永远不会被当成一次新的复制操作上报。
    /// 必须在 STA 线程上调用（Clipboard API 的要求）。
    /// </summary>
    private void RegisterExistingClipboard()
    {
        try
        {
            var existing = ReadClipboard();
            if (existing is null) return;

            _cache.Add(existing.Hash);
            Log.Info($"剪贴板中已有内容（{existing.Kind}），已登记为历史内容，不会同步");
        }
        catch (Exception ex)
        {
            // 登记失败不影响后续监听，最多是启动后可能多同步一次旧内容
            Log.Warn($"登记启动时的剪贴板内容失败：{ex.Message}");
        }
    }

    public void Stop()
    {
        if (!IsListening) return;

        _window?.StopClipboardListener();
        _window?.Dispose();
        _window = null;

        IsListening = false;
        ListenerStateChanged?.Invoke(false);
    }

    private void OnClipboardUpdated()
    {
        if (!_shouldSync()) return;

        var content = ReadClipboard();
        if (content is null) return;

        // 哈希已经在缓存里，说明这条内容不属于"当前这一次新操作"：
        //   · 我们自己刚把远端内容写进剪贴板，系统又回调了一次 —— 跳过才能阻断 A→B→A 回环
        //   · 启动前就存在于剪贴板的内容 —— 跳过，以往的操作不同步
        //   · 同一段内容被反复复制 —— 只同步第一次
        //
        // 注意用 Contains 而不是"命中即移除"：Windows 对一次剪贴板写入常常会投递
        // 多次 WM_CLIPBOARDUPDATE，若第一次就删掉哈希，后续那次会被当成新内容重复上报。
        if (_cache.Contains(content.Hash))
        {
            Log.Debug($"内容已处理过，跳过。hash={Short(content.Hash)}");
            return;
        }

        _cache.Add(content.Hash);
        ContentCaptured?.Invoke(content);
    }

    /// <summary>
    /// 读取"此刻"的剪贴板内容，供「立即同步剪贴板」这类手动触发使用。
    /// 必须在 STA 线程上调用（WinForms 的 Clipboard API 要求）。
    /// </summary>
    public static ClipboardContent? ReadCurrent() => ReadClipboard();

    /// <summary>
    /// 读取当前剪贴板内容。优先取文本，其次取图片。
    /// 返回 null 表示剪贴板为空或格式不受支持。
    /// </summary>
    private static ClipboardContent? ReadClipboard()
    {
        // 剪贴板是全局独占资源，被其他程序占用时会抛异常，重试几次
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                if (Clipboard.ContainsText())
                {
                    var text = Clipboard.GetText();
                    if (string.IsNullOrEmpty(text)) return null;

                    return new ClipboardContent
                    {
                        Kind = ClipKind.Text,
                        Text = text,
                        Hash = Hashing.Sha256Hex(text),
                    };
                }

                if (Clipboard.ContainsImage())
                {
                    using var image = Clipboard.GetImage();
                    if (image is null) return null;

                    var png = EncodePng(image);
                    return new ClipboardContent
                    {
                        Kind = ClipKind.Image,
                        ImagePng = png,
                        Hash = Hashing.Sha256Hex(png),
                    };
                }

                return null;
            }
            catch (ExternalException)
            {
                // 剪贴板被占用，短暂等待后重试
                Thread.Sleep(60);
            }
            catch
            {
                return null;
            }
        }
        return null;
    }

    /// <summary>把图片编码为 PNG 字节</summary>
    public static byte[] EncodePng(Image image)
    {
        using var ms = new MemoryStream();
        image.Save(ms, ImageFormat.Png);
        return ms.ToArray();
    }

    /// <summary>
    /// 把远端文本写入本机剪贴板。
    /// 写入时重试，避免与其他程序的剪贴板操作撞车。
    /// </summary>
    public static bool WriteText(string text)
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                // copy=true：让剪贴板持有数据副本，本进程退出后内容仍在。
                // （旧注释写的是 copy=false，与实际参数不符，已更正。）
                Clipboard.SetDataObject(text, copy: true, retryTimes: 3, retryDelay: 50);
                return true;
            }
            catch (ExternalException)
            {
                Thread.Sleep(60);
            }
            catch (Exception ex)
            {
                // 别静默返回 false：以前这里吞掉了 ThreadStateException，
                // 于是"下行写不进剪贴板"查了很久都看不出原因
                Log.Warn($"写入剪贴板（文本）失败：{ex.GetType().Name}: {ex.Message}");
                return false;
            }
        }
        return false;
    }

    /// <summary>把远端图片写入本机剪贴板</summary>
    public static bool WriteImage(byte[] pngBytes)
    {
        for (var attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                using var ms = new MemoryStream(pngBytes);
                using var image = Image.FromStream(ms);

                // 必须复制一份交给剪贴板：Image.FromStream 的流不能被提前释放。
                // ⚠️ 这个 Bitmap 不能 using —— SetDataObject(copy: true) 会自己
                // 持有它并负责销毁；在这里提前 Dispose 会导致剪贴板拿到已释放的位图
                // （粘贴时表现为空白或抛异常）。
                var bitmap = new Bitmap(image);

                var data = new DataObject();
                data.SetData(DataFormats.Bitmap, true, bitmap);

                Clipboard.SetDataObject(data, copy: true, retryTimes: 3, retryDelay: 50);
                return true;
            }
            catch (ExternalException)
            {
                Thread.Sleep(60);
            }
            catch (Exception ex)
            {
                Log.Warn($"写入剪贴板（图片）失败：{ex.GetType().Name}: {ex.Message}");
                return false;
            }
        }
        return false;
    }

    private static string Short(string hash) => hash.Length > 12 ? hash[..12] : hash;

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
    }
}
