using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;

namespace ClipBridge.UI;

/// <summary>程序图标。运行时绘制，免去二进制资源文件。</summary>
public static class TrayIcon
{
    private static Icon? _cached;

    /// <summary>生成一个剪贴板样式的图标</summary>
    public static Icon CreateIcon(bool connected = true)
    {
        // 连接状态直接影响图标颜色，这里缓存两种状态
        if (connected && _cached is not null) return _cached;

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

        if (connected) _cached = cloned;
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
