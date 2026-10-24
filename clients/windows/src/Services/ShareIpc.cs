using System.IO.Pipes;
using System.Text;

namespace ClipBridge.Services;

/// <summary>
/// 资源管理器右键菜单与本进程之间的投递通道。
///
/// ## 为什么需要它
///
/// 程序是单实例的（多开会让同一份剪贴板被重复上报，托盘图标也会出现多个）。
/// 用户点右键菜单时，系统会拉起第二个 ClipBridge.exe —— 那个进程必须把文件路径
/// 交给已经在跑的那个实例，自己立刻退出。如果让它自己去做上传，它既没有托盘、
/// 也没有长连接，发完就退出，用户看不到任何结果，重复点击也没有任何提示。
///
/// ## 为什么用命名管道而不是 WM_COPYDATA
///
/// WM_COPYDATA 要求对方已经创建好窗口句柄，而"点菜单时程序刚好在启动中"这个
/// 时间窗里窗口还不存在，消息会直接丢掉。命名管道由内核排队，
/// 对方稍后开始监听也能接上（本实现里 StartServer 早于网络连接启动）。
/// </summary>
public static class ShareIpc
{
    /// <summary>管道名。带版本号，将来协议不兼容时可以并行升级。</summary>
    private const string PipeName = "ClipBridge.Share.v1";

    /// <summary>
    /// 启动接收端。后台线程，随进程退出而结束（IsBackground=true）。
    ///
    /// 一次投递可能带多个文件（资源管理器"发送到"支持多选），所以回调收到的是路径数组。
    /// </summary>
    /// <param name="onPaths">收到一批文件路径时回调。可能是任意线程，调用方自己切 UI。</param>
    public static void StartServer(Action<string[]> onPaths)
    {
        var thread = new Thread(() =>
        {
            while (true)
            {
                try
                {
                    using var server = new NamedPipeServerStream(
                        PipeName, PipeDirection.In, maxNumberOfServerInstances: 4,
                        PipeTransmissionMode.Byte, PipeOptions.None);

                    server.WaitForConnection();

                    using var reader = new StreamReader(server, Encoding.UTF8);
                    // 协议：一行一个路径，读到 EOF 为止。
                    // 用多行而不是把多个路径拼成一行，是为了天然支持含空格/逗号的文件名 ——
                    // 任何分隔符都会和 Windows 上合法的文件名撞车。
                    var batch = new List<string>();
                    string? line;
                    while ((line = reader.ReadLine()) != null)
                    {
                        var p = line.Trim();
                        if (p.Length > 0) batch.Add(p);
                    }
                    if (batch.Count > 0)
                    {
                        try { onPaths(batch.ToArray()); }
                        catch { /* 单批处理失败不能拖垮监听循环 */ }
                    }
                }
                catch
                {
                    // 管道被占用 / 对方提前断开：歇一下再来，绝不能让循环退出，
                    // 否则用户只在第一次点菜单时有效，之后全都静默失败。
                    Thread.Sleep(1000);
                }
            }
        })
        {
            IsBackground = true,
            Name = "ClipBridge.ShareIpc",
        };
        thread.Start();
    }

    /// <summary>
    /// 把一批路径交给已在运行的实例。
    /// 返回 false 表示超时内没有任何实例在监听（通常意味着主程序没在跑）。
    /// </summary>
    public static bool TrySend(IEnumerable<string> paths, int timeoutMs = 1500)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
            client.Connect(timeoutMs);

            // 不带 BOM：接收端按 UTF-8 原样读，BOM 会混进路径里
            using var writer = new StreamWriter(
                client, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false))
            {
                AutoFlush = true,
            };
            foreach (var p in paths)
            {
                if (!string.IsNullOrWhiteSpace(p)) writer.WriteLine(p);
            }
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>单个路径的简写</summary>
    public static bool TrySend(string path, int timeoutMs = 1500)
        => TrySend(new[] { path }, timeoutMs);
}
