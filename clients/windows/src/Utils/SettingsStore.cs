using System.Text.Json;
using System.Text.Json.Serialization;
using ClipBridge.Models;

namespace ClipBridge.Utils;

/// <summary>
/// 配置与 Token 的本地持久化。
///
/// Token 使用 DPAPI 加密后存储：只有当前 Windows 用户能解密，
/// 即使配置文件被拷走也无法在其他机器上使用。
/// </summary>
public static class SettingsStore
{
    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipBridge");

    private static readonly string SettingsPath = Path.Combine(Dir, "settings.json");
    private static readonly string TokenPath = Path.Combine(Dir, "token.bin");

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    public static string DataDirectory => Dir;

    /// <summary>读取配置。文件不存在或损坏时返回全新配置并填好默认值。</summary>
    public static AppSettings Load()
    {
        AppSettings settings;
        try
        {
            if (File.Exists(SettingsPath))
            {
                var json = File.ReadAllText(SettingsPath);
                settings = JsonSerializer.Deserialize<AppSettings>(json, JsonOpts) ?? new AppSettings();
            }
            else
            {
                settings = new AppSettings();
            }
        }
        catch
        {
            // 配置损坏不应导致程序无法启动，退化为默认值
            settings = new AppSettings();
        }

        // 首次运行：生成稳定的设备 ID 与默认设备名
        var changed = false;
        if (string.IsNullOrWhiteSpace(settings.DeviceId))
        {
            settings.DeviceId = "win-" + Guid.NewGuid().ToString("N")[..12];
            changed = true;
        }
        if (string.IsNullOrWhiteSpace(settings.DeviceName))
        {
            settings.DeviceName = Environment.MachineName;
            changed = true;
        }
        if (string.IsNullOrWhiteSpace(settings.ScreenshotFolder))
        {
            settings.ScreenshotFolder = DefaultScreenshotFolder();
            changed = true;
        }

        settings.Token = LoadToken();

        if (changed && string.IsNullOrEmpty(settings.Token))
        {
            // 只有还没配对过才写盘，避免覆盖正常配置
        }
        return settings;
    }

    /// <summary>保存配置（Token 单独加密存储）</summary>
    public static void Save(AppSettings settings)
    {
        Directory.CreateDirectory(Dir);

        SaveToken(settings.Token);

        // 写盘时不含 Token，Token 走单独的加密文件
        var copy = CloneWithoutToken(settings);
        var json = JsonSerializer.Serialize(copy, JsonOpts);
        // 先写临时文件再替换，避免掉电导致配置损坏
        var tmp = SettingsPath + ".tmp";
        File.WriteAllText(tmp, json);
        File.Move(tmp, SettingsPath, overwrite: true);
    }

    private static AppSettings CloneWithoutToken(AppSettings s) => new()
    {
        ServerUrl = s.ServerUrl,
        DeviceId = s.DeviceId,
        DeviceName = s.DeviceName,
        SyncText = s.SyncText,
        SyncImage = s.SyncImage,
        WatchScreenshotFolder = s.WatchScreenshotFolder,
        ScreenshotFolder = s.ScreenshotFolder,
        AutoStart = s.AutoStart,
        MaxContentBytes = s.MaxContentBytes,
        ExcludedProcesses = s.ExcludedProcesses,
        FilterSensitive = s.FilterSensitive,
        ShowNotifications = s.ShowNotifications,
        Token = "",
    };

    // ---- Token 的 DPAPI 加密读写 ----

    private static void SaveToken(string token)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            if (string.IsNullOrEmpty(token))
            {
                if (File.Exists(TokenPath)) File.Delete(TokenPath);
                return;
            }
            var encrypted = ProtectedData.Protect(
                System.Text.Encoding.UTF8.GetBytes(token), null, DataProtectionScope.CurrentUser);
            File.WriteAllBytes(TokenPath, encrypted);
        }
        catch
        {
            // Token 保存失败不致命：用户下次使用时重新配对即可
        }
    }

    private static string LoadToken()
    {
        try
        {
            if (!File.Exists(TokenPath)) return "";
            var encrypted = File.ReadAllBytes(TokenPath);
            var plain = ProtectedData.Unprotect(encrypted, null, DataProtectionScope.CurrentUser);
            return System.Text.Encoding.UTF8.GetString(plain);
        }
        catch
        {
            // 换了用户或换了机器会解密失败，此时当作未配对处理
            return "";
        }
    }

    /// <summary>系统默认截图目录（Win+PrtScn 的落盘位置）</summary>
    public static string DefaultScreenshotFolder()
    {
        var pictures = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
        var screenshots = Path.Combine(pictures, "Screenshots");
        return Directory.Exists(screenshots) ? screenshots : pictures;
    }

    /// <summary>清空所有本地数据（用于"退出登录"）</summary>
    public static void Reset()
    {
        try
        {
            if (File.Exists(TokenPath)) File.Delete(TokenPath);
            if (File.Exists(SettingsPath)) File.Delete(SettingsPath);
        }
        catch { }
    }
}
