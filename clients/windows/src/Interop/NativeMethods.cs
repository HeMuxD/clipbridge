using System.Runtime.InteropServices;

namespace ClipBridge.Interop;

/// <summary>
/// Win32 原生 API 声明。
///
/// 本文件集中所有 P/Invoke，其余代码不直接调用 user32/shell32，
/// 便于审查与后续替换。
/// </summary>
internal static class NativeMethods
{
    // ---- 窗口消息 ----
    public const int WM_CLIPBOARDUPDATE = 0x031D;
    public const int WM_HOTKEY = 0x0312;
    public const int WM_DESTROY = 0x0002;

    /// <summary>HWND_MESSAGE：把窗口设为"消息专用窗口"，不可见、不进入任务栏。</summary>
    public static readonly IntPtr HWND_MESSAGE = new(-3);

    // ---- 剪贴板监听 ----
    /// <summary>
    /// 把窗口加入系统剪贴板监听列表。
    /// 剪贴板内容变化时，系统会向该窗口投递 WM_CLIPBOARDUPDATE。
    /// 这是 Windows Vista 之后推荐的做法，由系统维护，不依赖其他进程配合。
    /// </summary>
    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    // ---- 窗口创建 ----
    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr SetParent(IntPtr hWndChild, IntPtr hWndNewParent);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr CreateWindowEx(
        uint dwExStyle, string lpClassName, string lpWindowName, uint dwStyle,
        int x, int y, int nWidth, int nHeight,
        IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool DestroyWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    public static extern IntPtr DefWindowProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern ushort RegisterClassEx(ref WNDCLASSEX lpwcx);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr GetModuleHandle(string? lpModuleName);

    // ---- 全局热键 ----
    public const uint MOD_ALT = 0x0001;
    public const uint MOD_CONTROL = 0x0002;
    public const uint MOD_SHIFT = 0x0004;
    public const uint MOD_WIN = 0x0008;
    /// <summary>避免与其他程序的热键冲突时使用</summary>
    public const uint MOD_NOREPEAT = 0x4000;

    /// <summary>注册系统级热键，即使本程序没有焦点也能触发。</summary>
    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    // ---- 前台窗口查询（用于"排除指定应用"功能）----
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    /// <summary>获取窗口标题，用于判断当前前台是哪类应用。</summary>
    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder lpString, int nMaxCount);

    // ---- 结构体 ----

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct WNDCLASSEX
    {
        public uint cbSize;
        public uint style;
        public WndProcDelegate lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        [MarshalAs(UnmanagedType.LPWStr)] public string? lpszMenuName;
        [MarshalAs(UnmanagedType.LPWStr)] public string lpszClassName;
        public IntPtr hIconSm;
    }

    /// <summary>
    /// 窗口过程委托。必须作为字段保存在托管对象上，
    /// 否则会被 GC 回收，导致原生回调野指针崩溃。
    /// </summary>
    public delegate IntPtr WndProcDelegate(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
}
