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

        if (!string.IsNullOrEmpty(_settings.Token))
        {
            _client.Start();
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

        var payload = new ClipPayload
        {
            Kind = ClipKind.Text,
            Hash = Hashing.Sha256Hex(text),
            Text = text,
            Origin = origin,
            Size = System.Text.Encoding.UTF8.GetByteCount(text),
        };

        var ok = await _client.SendClipAsync(payload);
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
        StatusMessage?.Invoke(ok
            ? $"已同步图片（{upload.Size / 1024.0:F0} KB）"
            : "图片同步失败");
    }

    // ---------- 下行 ----------

    private async void OnRemoteClipReceived(ClipPayload payload)
    {
        try
        {
            // 关键：先登记哈希。写入剪贴板会立刻触发本机的 WM_CLIPBOARDUPDATE，
            // 监听器必须在那一刻能查到该哈希并跳过，否则形成回环。
            _cache.Add(payload.Hash);

            if (payload.Kind == ClipKind.Text)
            {
                if (!_settings.SyncText) return;
                if (payload.Text is null) return;

                var ok = ClipboardService.WriteText(payload.Text);
                if (ok)
                {
                    Log.Info($"已写入远端文本（来自 {payload.SrcName}）");
                    RemoteContentReceived?.Invoke($"收到来自「{payload.SrcName}」的文本");
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

                var ok = ClipboardService.WriteImage(bytes);
                if (ok)
                {
                    Log.Info($"已写入远端图片（来自 {payload.SrcName}）");
                    RemoteContentReceived?.Invoke($"收到来自「{payload.SrcName}」的截图");
                }
                else
                {
                    Log.Warn("写入剪贴板失败（图片）");
                }
            }
        }
        catch (Exception ex)
        {
            Log.Error("处理远端内容失败", ex);
        }
    }

    // ---------- 工具 ----------

    /// <summary>把任意图片字节统一转成 PNG，保证跨端一致性</summary>
    private static byte[] NormalizeToPng(byte[] raw)
    {
        try
        {
            using var ms = new MemoryStream(raw);
            using var img = Image.FromStream(ms);

            // 如果本来就是 PNG 且尺寸不大，直接返回原字节，避免重编码
            if (raw.Length >= 8 && raw[0] == 0x89 && raw[1] == 0x50)
                return raw;

            using var outMs = new MemoryStream();
            img.Save(outMs, System.Drawing.Imaging.ImageFormat.Png);
            return outMs.ToArray();
        }
        catch
        {
            // 不是有效图片，原样返回，由服务端判断
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
    }
}
