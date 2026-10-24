using System.Reflection;
using System.Runtime.InteropServices;

namespace ClipBridge.Utils;

/// <summary>
/// 「发送到」菜单项的注册：在用户 SendTo 目录里放一个快捷方式。
///
/// ## 为什么要这一套，而不是继续用 shell verb
///
/// 右键菜单有两套地方：
///   1. <c>HKCU\Software\Classes\&lt;ext&gt;\shell\&lt;verb&gt;</c> —— 右键【直接】出现的那一项
///      （Win11 上会被折叠进"显示更多选项"）。
///   2. <c>%APPDATA%\Microsoft\Windows\SendTo\</c> —— 右键 → 发送到 → 其中的一项。
///
/// 用户更习惯第二种：它在一个稳定的子菜单里，不受 Win11 菜单折叠影响，
/// 而且天然支持多选多个文件一起发。
///
/// ## 传参约定
///
/// 资源管理器会把选中的文件路径【追加在】快捷方式 Arguments 之后。
/// 所以快捷方式只需要 <c>--share-image</c> 这一个"开关"，真实路径由系统补上，
/// 不能用 shell verb 里的 <c>%1</c> 占位符（那套只对 verb 生效）。
///
/// 多个文件时路径会依次追加，程序侧按"至少一个路径"处理。
/// </summary>
public static class SendToMenu
{
    /// <summary>快捷方式显示名。中文名与其它菜单项风格一致。</summary>
    private const string ShortcutName = "使用 ClipBridge 推送到其他设备.lnk";

    /// <summary>目标程序收到的开关参数。</summary>
    public const string ShareArgument = "--share-image";

    private static string SendToDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "Microsoft", "Windows", "SendTo");

    private static string ShortcutPath => Path.Combine(SendToDir, ShortcutName);

    private static string? ExePath => Environment.ProcessPath;

    /// <summary>当前是否已注册</summary>
    public static bool IsEnabled()
    {
        try { return File.Exists(ShortcutPath); }
        catch { return false; }
    }

    /// <summary>
    /// 是否已注册【且指向当前这个 exe】。
    ///
    /// 分开判断是必要的：程序换目录后旧快捷方式还在，但它指向的旧路径可能已经失效。
    /// 启动时据此决定要不要重写。
    /// </summary>
    public static bool IsUpToDate()
    {
        try
        {
            if (!File.Exists(ShortcutPath)) return false;
            var target = ReadTargetPath();
            var exe = ExePath;
            if (string.IsNullOrEmpty(target) || string.IsNullOrEmpty(exe)) return false;
            return string.Equals(
                Path.GetFullPath(target), Path.GetFullPath(exe),
                StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>启用或禁用。失败时 <paramref name="error"/> 给出原因。</summary>
    public static bool SetEnabled(bool enabled, out string? error)
    {
        error = null;
        try
        {
            if (!enabled)
            {
                if (File.Exists(ShortcutPath)) File.Delete(ShortcutPath);
                return true;
            }

            var exe = ExePath;
            if (string.IsNullOrEmpty(exe) || !File.Exists(exe))
            {
                error = "取不到自身可执行文件路径，无法创建快捷方式";
                return false;
            }

            Directory.CreateDirectory(SendToDir);
            CreateShortcut(ShortcutPath, exe);
            return true;
        }
        catch (Exception ex)
        {
            error = $"{ex.GetType().Name}: {ex.Message}";
            return false;
        }
    }

    /// <summary>只关心成败时的简写</summary>
    public static bool SetEnabled(bool enabled) => SetEnabled(enabled, out _);

    /// <summary>
    /// 删除旧版本注册的右键 verb。
    ///
    /// 早期版本把入口做成"右键直接出现的一项"（<c>&lt;ext&gt;\shell\ClipBridgeShare</c>），
    /// 现在统一改到「发送到」里。老用户升级后注册表里的旧项还在，
    /// 会同时看到两个入口 —— 所以每次启动都清一遍。
    ///
    /// 删除是幂等的，旧项不存在时是空操作。
    /// </summary>
    public static void RemoveLegacyShellVerb()
    {
        foreach (var ext in new[] { ".png", ".jpg", ".jpeg", ".bmp", ".gif", ".webp" })
        {
            try
            {
                Microsoft.Win32.Registry.CurrentUser.DeleteSubKeyTree(
                    $@"Software\Classes\{ext}\shell\{LegacyVerbName}",
                    throwOnMissingSubKey: false);
            }
            catch
            {
                // 单项失败不影响其它格式，也不该阻塞启动
            }
        }
    }

    /// <summary>旧版右键菜单的 verb 键名，仅用于清理</summary>
    private const string LegacyVerbName = "ClipBridgeShare";

    /// <summary>
    /// 创建 .lnk。
    ///
    /// 用 WScript.Shell 的 COM 接口，而不是自己写二进制 lnk 格式 ——
    /// lnk 的文件结构没有公开稳定文档，手写极易在某个 Windows 版本上失效。
    /// 项目里另有一处用到 COM（ProtectedData 之外的 DPAPI），对 COM 的依赖不是新引入。
    /// </summary>
    private static void CreateShortcut(string lnkPath, string targetExe)
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell")
            ?? throw new InvalidOperationException("系统缺少 WScript.Shell（lWindows Script Host 被禁用？）");

        object? shell = null;
        object? shortcut = null;
        try
        {
            shell = Activator.CreateInstance(shellType)
                ?? throw new InvalidOperationException("无法创建 WScript.Shell 实例");

            shortcut = shellType.InvokeMember(
                "CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { lnkPath })
                ?? throw new InvalidOperationException("CreateShortcut 返回空");

            var shortcutType = shortcut.GetType();
            void Set(string name, string value) =>
                shortcutType.InvokeMember(name, BindingFlags.SetProperty, null, shortcut, new object[] { value });

            Set("TargetPath", targetExe);
            // 关键：这里不放 "%1"。SendTo 由系统把文件路径追加在参数之后。
            Set("Arguments", ShareArgument);
            Set("WorkingDirectory", Path.GetDirectoryName(targetExe) ?? "");
            Set("IconLocation", targetExe + ",0");
            Set("Description", "使用 ClipBridge 把图片推送到其他设备");

            shortcutType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
        }
        finally
        {
            // 不释放 COM 对象会残留一个 wscript 宿主进程引用
            if (shortcut is not null && Marshal.IsComObject(shortcut)) Marshal.FinalReleaseComObject(shortcut);
            if (shell is not null && Marshal.IsComObject(shell)) Marshal.FinalReleaseComObject(shell);
        }
    }

    /// <summary>读取已有快捷方式的目标路径（用于判断是否过期）</summary>
    private static string? ReadTargetPath()
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType is null) return null;

        object? shell = null;
        object? shortcut = null;
        try
        {
            shell = Activator.CreateInstance(shellType);
            if (shell is null) return null;

            shortcut = shellType.InvokeMember(
                "CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { ShortcutPath });
            if (shortcut is null) return null;

            return shortcut.GetType().InvokeMember(
                "TargetPath", BindingFlags.GetProperty, null, shortcut, null) as string;
        }
        catch
        {
            return null;
        }
        finally
        {
            if (shortcut is not null && Marshal.IsComObject(shortcut)) Marshal.FinalReleaseComObject(shortcut);
            if (shell is not null && Marshal.IsComObject(shell)) Marshal.FinalReleaseComObject(shell);
        }
    }
}
