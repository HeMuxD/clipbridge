using System.Runtime.InteropServices;
using static ClipBridge.Interop.NativeMethods;

namespace ClipBridge.Interop;

/// <summary>
/// 消息专用窗口：不可见、不进入任务栏、不参与 Z 序，
/// 唯一用途是接收系统投递的消息（剪贴板变更、全局热键）。
///
/// 用独立窗口而非主窗体，好处是监听逻辑与 UI 完全解耦 ——
/// 设置窗口关掉后监听依然工作。
/// </summary>
internal sealed class MessageWindow : IDisposable
{
    private const string ClassName = "ClipBridgeMessageWindow";

    // 必须持有委托引用，否则被 GC 回收后原生回调会崩溃
    private readonly WndProcDelegate _wndProcDelegate;
    private readonly IntPtr _hInstance;
    private IntPtr _hwnd;
    private ushort _classAtom;
    private bool _disposed;

    /// <summary>收到 WM_CLIPBOARDUPDATE 时触发</summary>
    public event Action? ClipboardUpdated;

    /// <summary>收到 WM_HOTKEY 时触发，参数为热键 ID</summary>
    public event Action<int>? HotKeyPressed;

    public IntPtr Handle => _hwnd;

    public MessageWindow()
    {
        _hInstance = GetModuleHandle(null);
        _wndProcDelegate = WndProc;

        var wc = new WNDCLASSEX
        {
            cbSize = (uint)Marshal.SizeOf<WNDCLASSEX>(),
            lpfnWndProc = _wndProcDelegate,
            hInstance = _hInstance,
            lpszClassName = ClassName,
        };

        _classAtom = RegisterClassEx(ref wc);
        if (_classAtom == 0)
        {
            int err = Marshal.GetLastWin32Error();
            // 类已注册（1409 ERROR_CLASS_ALREADY_EXISTS）时不算失败
            if (err != 1409)
                throw new InvalidOperationException($"注册窗口类失败，Win32 错误码 {err}");
        }

        // 父窗口设为 HWND_MESSAGE，即成为消息专用窗口
        _hwnd = CreateWindowEx(
            0, ClassName, "ClipBridge", 0,
            0, 0, 0, 0,
            HWND_MESSAGE, IntPtr.Zero, _hInstance, IntPtr.Zero);

        if (_hwnd == IntPtr.Zero)
        {
            int err = Marshal.GetLastWin32Error();
            throw new InvalidOperationException($"创建消息窗口失败，Win32 错误码 {err}");
        }
    }

    /// <summary>注册剪贴板监听</summary>
    public bool StartClipboardListener()
    {
        if (!AddClipboardFormatListener(_hwnd))
        {
            int err = Marshal.GetLastWin32Error();
            throw new InvalidOperationException($"注册剪贴板监听失败，Win32 错误码 {err}");
        }
        return true;
    }

    public void StopClipboardListener() => RemoveClipboardFormatListener(_hwnd);

    /// <summary>注册全局热键</summary>
    public bool RegisterHotKey(int id, uint modifiers, uint virtualKey)
        => NativeMethods.RegisterHotKey(_hwnd, id, modifiers | MOD_NOREPEAT, virtualKey);

    public void UnregisterHotKey(int id) => NativeMethods.UnregisterHotKey(_hwnd, id);

    private IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        switch (msg)
        {
            case WM_CLIPBOARDUPDATE:
                // 不要在窗口过程里做耗时操作：这是一条系统消息，
                // 阻塞过久会导致其他程序的复制操作出现卡顿。
                // 交给线程池异步处理。
                ThreadPool.QueueUserWorkItem(_ =>
                {
                    try { ClipboardUpdated?.Invoke(); }
                    catch { /* 单次事件异常不应影响监听循环 */ }
                });
                return IntPtr.Zero;

            case WM_HOTKEY:
                int id = wParam.ToInt32();
                ThreadPool.QueueUserWorkItem(_ =>
                {
                    try { HotKeyPressed?.Invoke(id); }
                    catch { }
                });
                return IntPtr.Zero;

            case WM_DESTROY:
                return IntPtr.Zero;
        }
        return DefWindowProc(hWnd, msg, wParam, lParam);
    }

    /// <summary>
    /// 启动消息循环。必须在独立线程上调用，并且该线程会一直阻塞。
    /// </summary>
    public void RunMessageLoop()
    {
        while (!_disposed && GetMessage(out MSG msg, IntPtr.Zero, 0, 0))
        {
            TranslateMessage(ref msg);
            DispatchMessage(ref msg);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public int ptX;
        public int ptY;
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DispatchMessage(ref MSG lpmsg);

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        StopClipboardListener();
        if (_hwnd != IntPtr.Zero)
        {
            DestroyWindow(_hwnd);
            _hwnd = IntPtr.Zero;
        }
    }
}
