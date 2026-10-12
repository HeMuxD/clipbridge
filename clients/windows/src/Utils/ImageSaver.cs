using ClipBridge.Services;

namespace ClipBridge.Utils;

/// <summary>
/// 收到远端图片后的本地留档。
///
/// 与 Android 端的策略一致：那边存进相册 `Pictures/ClipBridge`，
/// 这边存进 `图片\ClipBridge`。两边都是"写剪贴板"之外再留一份 ——
/// 图片只活在剪贴板里的话，下一次复制就把它顶掉了，回头想找都没有。
/// </summary>
public static class ImageSaver
{
    /// <summary>
    /// 保存一张图片，返回落盘路径；失败返回 null。
    /// 留档失败不应影响主流程（剪贴板已经写好了），所以这里自己吞掉异常只记日志。
    /// </summary>
    public static string? Save(byte[] pngBytes, string folder)
    {
        try
        {
            Directory.CreateDirectory(folder);
            var path = NextPath(folder);
            File.WriteAllBytes(path, pngBytes);
            return path;
        }
        catch (Exception ex)
        {
            Log.Warn($"接收图片留档失败（{folder}）：{ex.Message}");
            return null;
        }
    }

    /// <summary>
    /// 生成不冲突的文件名。
    /// 用可读的时间戳而不是毫秒数 —— 用户会直接在资源管理器里翻这个目录。
    /// </summary>
    private static string NextPath(string folder)
    {
        var stamp = DateTime.Now.ToString("yyyyMMdd_HHmmss");
        var path = Path.Combine(folder, $"clipbridge_{stamp}.png");

        // 同一秒内收到多张时递增后缀
        for (var i = 2; File.Exists(path) && i < 1000; i++)
            path = Path.Combine(folder, $"clipbridge_{stamp}-{i}.png");

        return path;
    }
}
