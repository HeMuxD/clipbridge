using System.Diagnostics;
using System.Text.RegularExpressions;
using ClipBridge.Interop;
using ClipBridge.Utils;

namespace ClipBridge.Services;

/// <summary>
/// 日志。写到 %APPDATA%\ClipBridge\logs\clipbridge.log，按天滚动。
/// 同时输出到 Debug 窗口，便于开发时观察。
/// </summary>
public static class Log
{
    private static readonly object Gate = new();
    private static readonly string LogDir = Path.Combine(SettingsStore.DataDirectory, "logs");
    private static string? _currentFile;
    private static DateTime _currentDate = DateTime.MinValue;

    public static bool Verbose { get; set; }

    public static void Debug(string message) => Write("DEBUG", message, onlyVerbose: true);
    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message) => Write("ERROR", message);

    public static void Error(string message, Exception ex)
        => Write("ERROR", $"{message} | {ex.GetType().Name}: {ex.Message}");

    private static void Write(string level, string message, bool onlyVerbose = false)
    {
        if (onlyVerbose && !Verbose) return;

        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level,-5}] {message}";

        System.Diagnostics.Debug.WriteLine(line);

        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(LogDir);
                var today = DateTime.Today;
                if (_currentFile is null || today != _currentDate)
                {
                    _currentDate = today;
                    _currentFile = Path.Combine(LogDir, $"clipbridge-{today:yyyyMMdd}.log");
                    CleanupOldLogs();
                }
                File.AppendAllText(_currentFile, line + Environment.NewLine);
            }
            catch
            {
                // 日志失败绝不能影响主流程
            }
        }
    }

    /// <summary>只保留最近 7 天的日志</summary>
    private static void CleanupOldLogs()
    {
        try
        {
            var cutoff = DateTime.Today.AddDays(-7);
            foreach (var f in Directory.GetFiles(LogDir, "clipbridge-*.log"))
            {
                if (File.GetLastWriteTime(f) < cutoff)
                    File.Delete(f);
            }
        }
        catch { }
    }

    public static string LogDirectory => LogDir;
}

/// <summary>
/// 敏感内容检测。
///
/// 目的：避免把密码管理器里的内容、验证码、银行卡号等同步到其他设备。
/// 这是"防误伤"而非安全边界 —— 真正的安全边界是端到端加密。
/// </summary>
public static class SensitiveFilter
{
    private static readonly Regex[] Rules =
    {
        // 银行卡号（15~19 位连续数字）
        new(@"\b\d{15,19}\b", RegexOptions.Compiled),
        // 中国身份证号
        new(@"\b\d{17}[\dXx]\b", RegexOptions.Compiled),
        // 密码字段
        new(@"(?i)(password|passwd|pwd|密码)\s*[:=]\s*\S+", RegexOptions.Compiled),
        // 常见 Token / Secret 字段
        new(@"(?i)(api[_-]?key|secret|token)\s*[:=]\s*\S{16,}", RegexOptions.Compiled),
        // 私钥块
        new(@"-----BEGIN [A-Z ]*PRIVATE KEY-----", RegexOptions.Compiled),
    };

    /// <summary>判断文本是否疑似包含敏感信息</summary>
    public static bool IsSensitive(string text)
    {
        if (string.IsNullOrEmpty(text)) return false;
        foreach (var rule in Rules)
        {
            if (rule.IsMatch(text)) return true;
        }
        return false;
    }
}

/// <summary>
/// 前台进程识别，用于"排除指定应用"。
/// 例如把密码管理器加入排除列表后，从它复制的任何内容都不会同步。
/// </summary>
public static class ForegroundApp
{
    /// <summary>获取当前前台窗口所属的进程名（小写，不含 .exe）。失败返回空串。</summary>
    public static string GetProcessName()
    {
        try
        {
            var hwnd = NativeMethods.GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return "";

            NativeMethods.GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == 0) return "";

            using var proc = Process.GetProcessById((int)pid);
            return proc.ProcessName.ToLowerInvariant();
        }
        catch
        {
            return "";
        }
    }

    /// <summary>判断当前前台进程是否在排除列表中</summary>
    public static bool IsExcluded(IEnumerable<string> excluded)
    {
        var name = GetProcessName();
        if (string.IsNullOrEmpty(name)) return false;

        foreach (var item in excluded)
        {
            if (string.IsNullOrWhiteSpace(item)) continue;
            var normalized = item.Trim().ToLowerInvariant();
            if (normalized.EndsWith(".exe")) normalized = normalized[..^4];
            if (normalized == name) return true;
        }
        return false;
    }
}
