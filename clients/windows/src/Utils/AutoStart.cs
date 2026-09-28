using Microsoft.Win32;

namespace ClipBridge.Utils;

/// <summary>
/// 开机自启管理。
///
/// 写 HKCU\Software\Microsoft\Windows\CurrentVersion\Run，
/// 这是用户级注册表项，不需要管理员权限。
/// </summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "ClipBridge";

    /// <summary>是否已设置开机自启</summary>
    public static bool IsEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
            var value = key?.GetValue(ValueName) as string;
            return !string.IsNullOrEmpty(value);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>启用或禁用开机自启，返回是否成功</summary>
    public static bool SetEnabled(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true)
                            ?? Registry.CurrentUser.CreateSubKey(RunKey);
            if (key is null) return false;

            if (enabled)
            {
                var exe = Environment.ProcessPath;
                if (string.IsNullOrEmpty(exe)) return false;
                // --minimized 让程序启动后直接托盘化，不弹设置窗口
                key.SetValue(ValueName, $"\"{exe}\" --minimized");
            }
            else
            {
                if (key.GetValue(ValueName) is not null)
                    key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
            return true;
        }
        catch
        {
            return false;
        }
    }
}

/// <summary>
/// 单实例约束。
///
/// 多开会导致同一份剪贴板被重复上报，并且托盘图标出现多个。
/// 用命名 Mutex 保证只有一个实例。
/// </summary>
public sealed class SingleInstance : IDisposable
{
    private Mutex? _mutex;

    /// <summary>true 表示当前是唯一实例</summary>
    public bool IsFirstInstance { get; private set; }

    public SingleInstance(string name = "ClipBridge.SingleInstance")
    {
        _mutex = new Mutex(initiallyOwned: true, name, out var createdNew);
        IsFirstInstance = createdNew;
    }

    public void Dispose()
    {
        try
        {
            _mutex?.ReleaseMutex();
        }
        catch { }
        _mutex?.Dispose();
        _mutex = null;
    }
}
