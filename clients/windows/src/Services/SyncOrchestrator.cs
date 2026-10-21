using System.Drawing;
using ClipBridge.Models;
using ClipBridge.Services;
using ClipBridge.Utils;

namespace ClipBridge.Services;

/// <summary>
/// 同步编排器：把"本地检测 → 上传 → 收发"串成完整闭环。
///
/// 所有防回环逻辑集中在这里，是保证系统不会无限循环的关键。
/// </summary>
public sealed class SyncOrchestrator : IDisposable
{
    private readonly AppSettings _settings;
    private readonly HashCache _cache;
    private readonly ClipboardService _clipboard;
    private readonly ScreenshotWatcher _screenshots;
    private readonly SyncClient _client;

    /// <summary>
    /// 剪贴板写入必须在 STA 线程上做（OLE 的要求），而下行事件来自
    /// ThreadPool 的 MTA 线程，所以统一走这个派发器。
    /// </summary>
    private readonly StaDispatcher _sta = new();

    private bool _disposed;

    public event Action<string>? StatusMessage;
    public event Action<bool>? ConnectionChanged;
    public event Action<string>? RemoteContentReceived;

    public bool IsConnected => _client.IsConnected;

    public SyncOrchestrator(AppSettings settings, SyncClient client)
    {
        _settings = settings;
        _client = client;
        _cache = new HashCache(300);

        _clipboard = new ClipboardService(_cache, ShouldSyncClipboard);
        _screenshots = new ScreenshotWatcher(_cache, ShouldSyncScreenshot);

        _clipboard.ContentCaptured += OnLocalContentCaptured;
        _screenshots.ScreenshotDetected += OnScreenshotDetected;
        _screenshots.StatusMessage += m => StatusMessage?.Invoke(m);

        _client.ClipReceived += OnRemoteClipReceived;
        _client.ConnectionChanged += c => ConnectionChanged?.Invoke(c);
        _client.StatusMessage += m => StatusMessage?.Invoke(m);
    }

    public void Start()
    {
        try
        {
            _clipboard.Start();
            Log.Info("剪贴板监听已启动");
        }
        catch (Exception ex)
        {
            Log.Error("剪贴板监听启动失败", ex);
            StatusMessage?.Invoke($"剪贴板监听启动失败：{ex.Message}");
        }

        if (_settings.WatchScreenshotFolder)
        {
            _screenshots.Start(_settings.ScreenshotFolder);
        }

        if (!string.IsNullOrEmpty(_settings.Token) && !string.IsNullOrEmpty(_settings.ServerUrl))
        {
            _client.Start();
        }
        else if (string.IsNullOrEmpty(_settings.ServerUrl))
        {
            // 地址为空就别启动连接：带着空地址 Start() 会一直重连并刷
            // "Invalid URI: The hostname could not be parsed"，
            // 只在 token.bin 还在、settings.json 丢了的时候才会走到这里。
            StatusMessage?.Invoke("尚未配置服务端地址，请在设置中填写");
        }
        else
        {
            StatusMessage?.Invoke("尚未配对，请在设置中填入服务端地址与配对码");
        }
    }

    public void Stop()
    {
        _clipboard.Stop();
        _screenshots.Stop();
        _client.Stop();
    }

    // ---------- 触发判定 ----------

    private bool ShouldSyncClipboard()
    {
        if (!_settings.SyncText && !_settings.SyncImage) return false;
        if (string.IsNullOrEmpty(_settings.Token)) return false;

        // 排除列表：从密码管理器等应用复制的内容不同步
        if (_settings.ExcludedProcesses.Count > 0 && ForegroundApp.IsExcluded(_settings.ExcludedProcesses))
        {
            Log.Debug($"前台应用 {ForegroundApp.GetProcessName()} 在排除列表中，已跳过");
            return false;
        }
        return true;
    }

    private bool ShouldSyncScreenshot()
    {
        if (!_settings.SyncImage) return false;
        if (string.IsNullOrEmpty(_settings.Token)) return false;
        // 截图监控由用户主动开启，理应同步，不受前台应用排除影响
        return true;
    }

    // ---------- 上行 ----------

    private async void OnLocalContentCaptured(ClipboardContent content)
    {
        try
        {
            switch (content.Kind)
            {
                case ClipKind.Text:
                    if (!_settings.SyncText) return;
                    await SendTextAsync(content.Text!, ClipOrigin.Clipboard);
                    break;

                case ClipKind.Image:
                    if (!_settings.SyncImage) return;
                    await SendImageAsync(content.ImagePng!, content.Hash, ClipOrigin.Clipboard);
                    break;
            }
        }
        catch (Exception ex)
        {
            Log.Error("发送本地内容失败", ex);
        }
    }

    private async void OnScreenshotDetected(string path)
    {
        try
        {
            var bytes = await File.ReadAllBytesAsync(path);
            // 截图目录里的图直接用原始字节，避免二次编码造成画质损失
            var png = NormalizeToPng(bytes);
            var hash = Hashing.Sha256Hex(png);
            await SendImageAsync(png, hash, ClipOrigin.Screenshot);
        }
        catch (Exception ex)
        {
            Log.Error($"发送截图失败：{path}", ex);
        }
    }

    private async Task SendTextAsync(string text, string origin)
    {
        if (text.Length > 1024 * 1024)
        {
            Log.Warn("文本过长（超过 1MB），已跳过同步");
            return;
        }

        // 敏感内容过滤（设置里那个"跳过密码/卡号"的开关）。
        //
        // 之前这里是空的：SensitiveFilter 类写好了却没有任何调用点，
        // 于是 UI 上的开关是个纯装饰 —— 勾不勾行为完全一样。现在接通。
        //
        // 注意这是"防误伤"而不是安全边界：真正的边界是服务端不过度留存
        // 与可选的端到端加密。所以命中时只跳过 + 记日志，不打扰用户。
        if (_settings.FilterSensitive && SensitiveFilter.IsSensitive(text))
        {
            Log.Info($"文本疑似含敏感信息（密码/卡号/密钥等），已按设置跳过同步（{text.Length} 字）");
            StatusMessage?.Invoke("内容疑似敏感，已跳过同步");
            return;
        }

        var payload = new ClipPayload
        {
            Kind = ClipKind.Text,
            Hash = Hashing.Sha256Hex(text),
            Text = text,
            Origin = origin,
            Size = System.Text.Encoding.UTF8.GetByteCount(text),
        };

        var ok = await _client.SendClipAsync(payload);
        if (!ok)
        {
            // 发送失败就把 hash 撤销 —— ClipboardService 在我们上报前已经 Add 过一次
            // （见其 OnClipboardUpdated），不撤销的话这段内容永远不会再被自动上报：
            // 用户重连后想再复制同一段文字，会被 Contains 当成"已处理"跳过。
            _cache.Remove(payload.Hash);
        }
        StatusMessage?.Invoke(ok ? $"已同步文本（{text.Length} 字）" : "文本同步失败");
    }

    private async Task SendImageAsync(byte[] pngBytes, string hash, string origin)
    {
        if (pngBytes.Length > _settings.MaxContentBytes)
        {
            Log.Warn($"图片过大（{pngBytes.Length} 字节），已跳过");
            StatusMessage?.Invoke("图片超过大小上限，已跳过同步");
            return;
        }

        // 图片走 HTTP 上传，WebSocket 只传轻量的元数据
        var upload = await _client.UploadImageAsync(pngBytes, hash);
        if (upload is null)
        {
            // 与文本同理：上传失败要撤销登记的 hash，否则这张图再也不会被自动同步
            _cache.Remove(hash);
            StatusMessage?.Invoke("图片上传失败");
            return;
        }

        var (w, h) = GetImageSize(pngBytes);

        var payload = new ClipPayload
        {
            Kind = ClipKind.Image,
            Hash = upload.Hash.Length > 0 ? upload.Hash : hash,
            FileId = upload.FileId,
            Url = upload.Url,
            Mime = upload.Mime,
            Size = upload.Size,
            Width = w,
            Height = h,
            Origin = origin,
        };

        // 记下哈希：服务端广播回来时（如果自己也收到了）能识别并跳过
        _cache.Add(payload.Hash);

        var ok = await _client.SendClipAsync(payload);
        if (!ok)
        {
            // 上传成功但 clip 消息没发出去：服务端已有文件、其他设备没收到内容。
            // 撤销 hash，让用户重发这张图时还能再试一次。
            _cache.Remove(payload.Hash);
            _cache.Remove(hash);
        }
        StatusMessage?.Invoke(ok
            ? $"已同步图片（{upload.Size / 1024.0:F0} KB）"
            : "图片同步失败");
    }

    // ---------- 下行 ----------

    private async void OnRemoteClipReceived(ClipPayload payload)
    {
        try
        {
            var src = string.IsNullOrEmpty(payload.SrcName) ? "其他设备" : payload.SrcName;

            // 同一份内容刚同步过就跳过。上游把同一条投递两次时（服务端重复广播、
            // 或对端把收到的图又上行了一次），不挡的话三端都会留下重复的一份。
            if (_cache.Contains(payload.Hash))
            {
                var shortHash = payload.Hash[..Math.Min(12, payload.Hash.Length)];
                Log.Info($"跳过重复内容（{shortHash}，来自 {src}）");
                return;
            }

            // 登记哈希：写入剪贴板会立刻触发本机的 WM_CLIPBOARDUPDATE，
            // 监听器必须在那一刻能查到该哈希并跳过，否则形成回环。
            _cache.Add(payload.Hash);

            // 后面任何一步失败，都要把这个 hash 撤销登记 —— 否则这份内容
            // 就永远被 Contains 挡在门外，对端重发也进不来。
            var delivered = false;
            try
            {
                if (payload.Kind == ClipKind.Text)
                {
                    if (!_settings.SyncText) return;
                    if (payload.Text is null) return;

                    var ok = await _sta.InvokeAsync(() => ClipboardService.WriteText(payload.Text));
                    if (ok)
                    {
                        delivered = true;
                        Log.Info($"已写入远端文本（来自 {src}）");
                        RemoteContentReceived?.Invoke($"收到来自「{src}」的文本");
                    }
                    else
                    {
                        Log.Warn("写入剪贴板失败");
                    }
                }
                else if (payload.Kind == ClipKind.Image)
                {
                    if (!_settings.SyncImage) return;
                    if (string.IsNullOrEmpty(payload.Url)) return;

                    var bytes = await _client.DownloadImageAsync(payload.Url);
                    if (bytes is null)
                    {
                        Log.Warn("图片下载失败");
                        return;
                    }

                    // 先写剪贴板（用户最直接的感知方式），再往磁盘留一份。
                    // 注意必须切到 STA 线程：下行事件来自 ThreadPool 的 MTA 线程，
                    // 直接在 MTA 上调 Clipboard 会抛 ThreadStateException。
                    var ok = await _sta.InvokeAsync(() => ClipboardService.WriteImage(bytes));

                    string? savedPath = null;
                    if (_settings.SaveReceivedImages)
                    {
                        savedPath = ImageSaver.Save(bytes, SettingsStore.ResolveImageFolder(_settings));
                    }

                    if (ok) Log.Info($"已写入远端图片（来自 {src}）");
                    else Log.Warn("写入剪贴板失败（图片）");

                    if (savedPath is not null) Log.Info($"图片已留档：{savedPath}");
                    else if (_settings.SaveReceivedImages) Log.Warn("图片留档失败");

                    // 只要有一边成功就算收到了（另一边失败不影响用户拿到图）
                    if (ok || savedPath is not null)
                    {
                        delivered = true;
                        RemoteContentReceived?.Invoke(DescribeImageReceived(src, ok, savedPath));
                    }
                }
                else
                {
                    // 未知 kind：不认识的类型不该占着 hash
                    Log.Warn($"未知的内容类型：{payload.Kind}");
                }
            }
            finally
            {
                if (!delivered)
                {
                    _cache.Remove(payload.Hash);
                    Log.Info($"内容未成功交付，已撤销去重登记（{payload.Hash[..Math.Min(12, payload.Hash.Length)]}）");
                }
            }
        }
        catch (Exception ex)
        {
            Log.Error("处理远端内容失败", ex);
            // 异常路径同样要撤销，交给上面的 finally 已覆盖；
            // 这里兜底处理 InvokeAsync 抛出前的极端情况。
            _cache.Remove(payload.Hash);
        }
    }

    /// <summary>把"收到了截图"这件事连同两个落点（剪贴板 / 本地留档）的结果说清楚</summary>
    private static string DescribeImageReceived(string srcName, bool inClipboard, string? savedPath)
    {
        var tail = (inClipboard, savedPath) switch
        {
            (true, not null) => "（已进剪贴板，并存了一份到本地）",
            (false, not null) => "（已存到本地，剪贴板被其他程序占用）",
            (true, null) => "（已进剪贴板）",
            _ => "",
        };
        return $"收到来自「{srcName}」的截图{tail}";
    }

    // ---------- 工具 ----------

    /// <summary>
    /// 把任意图片字节统一转成 PNG，保证跨端一致性。
    ///
    /// 已是 PNG 的字节直接原样返回，避免一次无谓的"解码 → 重编码"（会掉画质、
    /// 也会让同一张图在两端算出不同的字节 hash）。其它格式才真正转码。
    /// </summary>
    private static byte[] NormalizeToPng(byte[] raw)
    {
        // PNG 魔数：89 50 4E 47 0D 0A 1A 0A
        if (raw.Length >= 8 && raw[0] == 0x89 && raw[1] == 0x50 &&
            raw[2] == 0x4E && raw[3] == 0x47)
            return raw;

        try
        {
            using var ms = new MemoryStream(raw);
            using var img = Image.FromStream(ms);
            using var outMs = new MemoryStream();
            img.Save(outMs, System.Drawing.Imaging.ImageFormat.Png);
            return outMs.ToArray();
        }
        catch
        {
            // 不是有效图片，原样返回，由服务端按魔数判断
            return raw;
        }
    }

    private static (int Width, int Height) GetImageSize(byte[] bytes)
    {
        try
        {
            using var ms = new MemoryStream(bytes);
            using var img = Image.FromStream(ms);
            return (img.Width, img.Height);
        }
        catch
        {
            return (0, 0);
        }
    }

    /// <summary>
    /// 手动同步一次：把"此刻"剪贴板里的内容直接发出去。
    ///
    /// 与自动同步的区别是它不看哈希缓存 —— 用户主动点了菜单，
    /// 就是明确要把这一次的内容发出去，哪怕它和之前的内容一模一样。
    /// 必须在 UI（STA）线程上调用。
    /// </summary>
    public async Task ForceSyncNowAsync()
    {
        ClipboardContent? content;
        try
        {
            content = ClipboardService.ReadCurrent();
        }
        catch (Exception ex)
        {
            Log.Error("读取剪贴板失败", ex);
            StatusMessage?.Invoke("读取剪贴板失败");
            return;
        }

        if (content is null)
        {
            StatusMessage?.Invoke("剪贴板为空，没有可同步的内容");
            return;
        }

        // 先记进缓存，避免紧接着的自动监听又把同一条内容上报一次
        _cache.Add(content.Hash);

        switch (content.Kind)
        {
            case ClipKind.Text:
                await SendTextAsync(content.Text!, ClipOrigin.Manual);
                break;

            case ClipKind.Image:
                await SendImageAsync(content.ImagePng!, content.Hash, ClipOrigin.Manual);
                break;
        }
    }

    /// <summary>应用设置变更后调用，重启监听以生效</summary>
    public void Reload()
    {
        _screenshots.Stop();
        if (_settings.WatchScreenshotFolder)
            _screenshots.Start(_settings.ScreenshotFolder);

        if (!string.IsNullOrEmpty(_settings.Token) && !_client.IsConnected)
            _client.Start();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
        _clipboard.Dispose();
        _screenshots.Dispose();
        _client.Dispose();
        _sta.Dispose();
    }
}
