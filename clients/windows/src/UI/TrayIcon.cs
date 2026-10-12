using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace ClipBridge.UI;

/// <summary>程序图标。运行时绘制，免去二进制资源文件。</summary>
public static class TrayIcon
{
    // 两种状态各留一份"模板"。它们是共享的，**绝不能被外部 Dispose**，
    // 所以对外一律返回 Clone。
    private static readonly Icon ConnectedTemplate = Build(true);
    private static readonly Icon DisconnectedTemplate = Build(false);

    /// <summary>
    /// 生成一个剪贴板样式的图标（返回的是副本，调用方可以放心 Dispose）。
    ///
    /// 以前这里是"缓存 + 直接返回缓存对象"，而 TrayApplication 换图标时会
    /// `old?.Dispose()` —— 连接成功那一刻 old 和新建出来的就是同一个缓存对象，
    /// 于是缓存把自己给释放了。之后 CreateIcon(true) 一直返回已释放的 Icon，
    /// 构造 SettingsForm 时设 Icon 就抛 ObjectDisposedException，
    /// 现象就是"托盘菜单里第一次点『设置…』没反应"。
    /// </summary>
    public static Icon CreateIcon(bool connected = true)
        => (Icon)(connected ? ConnectedTemplate : DisconnectedTemplate).Clone();

    private static Icon Build(bool connected)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            // 底板：圆角矩形
            var accent = connected ? Color.FromArgb(58, 122, 92) : Color.FromArgb(140, 140, 140);
            using var bg = new SolidBrush(accent);
            using var path = RoundedRect(new Rectangle(3, 3, 26, 26), 7);
            g.FillPath(bg, path);

            // 剪贴板的"夹子"
            using var clip = new SolidBrush(Color.White);
            using var clipPath = RoundedRect(new Rectangle(11, 1, 10, 8), 2);
            g.FillPath(clip, clipPath);

            // 两条内容线
            using var line = new Pen(Color.White, 2.4f)
            {
                StartCap = LineCap.Round,
                EndCap = LineCap.Round,
            };
            g.DrawLine(line, 10, 15, 22, 15);
            g.DrawLine(line, 10, 21, 18, 21);
        }

        var icon = Icon.FromHandle(bmp.GetHicon());
        // GetHicon 返回的句柄需要手动释放，这里复制一份后立即销毁原句柄
        var cloned = (Icon)icon.Clone();
        DestroyIcon(icon.Handle);
        return cloned;
    }

    private static GraphicsPath RoundedRect(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);
}
