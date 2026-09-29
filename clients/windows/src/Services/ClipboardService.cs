using System.Drawing;
using System.Drawing.Imaging;
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
    private Thread? _loopThread;
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
    /// 启动监听。会创建独立线程跑消息循环。
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

        _loopThread = new Thread(_window.RunMessageLoop)
        {
            IsBackground = true,
            Name = "ClipBridge.ClipboardPump",
        };
        _loopThread.SetApartmentState(ApartmentState.STA);
        _loopThread.Start();

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
                // 用 SetDataObject 且 copy=false + 自定义格式，可以避免
                // 本进程退出后剪贴板内容丢失的问题
                Clipboard.SetDataObject(text, copy: true, retryTimes: 3, retryDelay: 50);
                return true;
            }
            catch (ExternalException)
            {
                Thread.Sleep(60);
            }
            catch
            {
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
            catch
            {
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
