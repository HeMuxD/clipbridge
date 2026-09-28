using ClipBridge.Utils;
using ClipBridge.Services;

namespace ClipBridge.Services;

/// <summary>
/// 截图目录监控。
///
/// 覆盖 Win+PrtScn 这类"直接落盘到图片目录"的截图方式，
/// 与剪贴板监听形成互补：后者覆盖 Win+Shift+S 这类"先进剪贴板"的方式。
///
/// 用 FileSystemWatcher（底层 ReadDirectoryChangesW）而非轮询 ——
/// 由内核事件驱动，CPU 占用几乎为零。
/// </summary>
public sealed class ScreenshotWatcher : IDisposable
{
    private static readonly string[] ImageExtensions = { ".png", ".jpg", ".jpeg", ".bmp", ".webp" };

    private readonly HashCache _cache;
    private readonly Func<bool> _shouldSync;
    private FileSystemWatcher? _watcher;
    private bool _disposed;

    /// <summary>检测到新截图时触发，参数为文件完整路径</summary>
    public event Action<string>? ScreenshotDetected;
    public event Action<string>? StatusMessage;

    public bool IsWatching => _watcher?.EnableRaisingEvents ?? false;

    public ScreenshotWatcher(HashCache cache, Func<bool> shouldSync)
    {
        _cache = cache;
        _shouldSync = shouldSync;
    }

    /// <summary>开始监控指定目录</summary>
    public bool Start(string folder)
    {
        Stop();

        if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder))
        {
            StatusMessage?.Invoke($"截图目录不存在：{folder}");
            return false;
        }

        _watcher = new FileSystemWatcher(folder)
        {
            // 只关心新文件，忽略改名与删除
            NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite,
            IncludeSubdirectories = false,
            // 截图文件通常有前缀，这里全量监听后按扩展名过滤，
            // 因为不同工具的命名规则不一样
            Filter = "*.*",
            InternalBufferSize = 64 * 1024,
        };

        _watcher.Created += OnCreated;
        _watcher.Renamed += OnRenamed;
        _watcher.Error += OnError;
        _watcher.EnableRaisingEvents = true;

        Log.Info($"截图目录监控已启动：{folder}");
        return true;
    }

    public void Stop()
    {
        if (_watcher is null) return;

        _watcher.EnableRaisingEvents = false;
        _watcher.Created -= OnCreated;
        _watcher.Renamed -= OnRenamed;
        _watcher.Error -= OnError;
        _watcher.Dispose();
        _watcher = null;
    }

    private void OnCreated(object sender, FileSystemEventArgs e) => HandleFile(e.FullPath);

    private void OnRenamed(object sender, RenamedEventArgs e) => HandleFile(e.FullPath);

    private void OnError(object sender, ErrorEventArgs e)
    {
        // 缓冲区溢出会丢事件。日志提示，下次启动前不易恢复，
        // 但正常情况下 InternalBufferSize 64KB 足够。
        Log.Error("截图目录监控出错（可能事件缓冲区溢出）", e.GetException());
    }

    /// <summary>
    /// 处理新出现的文件。
    ///
    /// 注意：Created 事件可能在文件还没写完时就触发，
    /// 因此必须等到文件句柄可独占打开、且大小稳定后再读取，
    /// 否则会读到半张图。
    /// </summary>
    private void HandleFile(string path)
    {
        if (!_shouldSync()) return;

        var ext = Path.GetExtension(path).ToLowerInvariant();
        if (!ImageExtensions.Contains(ext)) return;

        _ = Task.Run(async () =>
        {
            try
            {
                if (!await WaitForFileReadyAsync(path))
                {
                    Log.Debug($"文件始终未就绪，放弃：{path}");
                    return;
                }

                var bytes = await File.ReadAllBytesAsync(path);
                if (bytes.Length == 0) return;

                var hash = Hashing.Sha256Hex(bytes);

                // 我们自己写入的内容不会出现在这里，但仍做一次去重，
                // 避免同一张图被多个工具重复落盘时重复同步
                if (_cache.Contains(hash)) return;
                _cache.Add(hash);

                Log.Info($"检测到新截图：{Path.GetFileName(path)}（{bytes.Length} 字节）");
                ScreenshotDetected?.Invoke(path);
            }
            catch (Exception ex)
            {
                Log.Error($"处理截图文件失败：{path}", ex);
            }
        });
    }

    /// <summary>
    /// 等待文件写入完成：以独占方式打开成功且大小连续两次一致。
    /// </summary>
    private static async Task<bool> WaitForFileReadyAsync(string path, int maxAttempts = 20)
    {
        long lastSize = -1;
        var stableCount = 0;

        for (var i = 0; i < maxAttempts; i++)
        {
            await Task.Delay(150);

            try
            {
                var info = new FileInfo(path);
                if (!info.Exists) return false;

                // 能独占打开说明写入者已经释放了句柄
                using (var fs = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.None))
                {
                    if (fs.Length == lastSize)
                    {
                        stableCount++;
                        if (stableCount >= 2) return true;
                    }
                    else
                    {
                        lastSize = fs.Length;
                        stableCount = 0;
                    }
                }
            }
            catch (IOException)
            {
                // 文件仍被占用，继续等待
                stableCount = 0;
            }
            catch
            {
                return false;
            }
        }
        return false;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
    }
}
