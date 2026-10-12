using System.Collections.Concurrent;

namespace ClipBridge.Services;

/// <summary>
/// 剪贴板操作的 STA 派发器。
///
/// WinForms 的 <c>Clipboard</c> 底层是 OLE，<b>要求调用线程处于 STA 模式</b>；
/// 而 WebSocket 的接收循环跑在 <c>Task.Run</c> 的 ThreadPool（MTA）线程上，
/// 直接调用 <c>Clipboard.SetDataObject</c> 会抛 <see cref="ThreadStateException"/>
/// （"在可以调用 OLE 之前，必须将当前线程设置为单线程单元(STA)模式"）。
///
/// 读取那条路是对的 —— <c>ClipboardService</c> 给消息泵线程显式设了 STA；
/// 但下行写入忘了做同样的事，于是异常被写入失败的 <c>catch</c> 吞掉，
/// 表现就是"远端内容能下载、能落盘，却永远写不进剪贴板"。
///
/// 这里起一条专用的 STA 线程当"邮差"：调用方把委托排进队列并异步等待结果，
/// 既不阻塞 UI，也不占用 ThreadPool 线程去做 OLE 调用。
/// </summary>
public sealed class StaDispatcher : IDisposable
{
    private readonly BlockingCollection<Action> _queue = new();
    private readonly Thread _thread;
    private bool _disposed;

    public StaDispatcher()
    {
        _thread = new Thread(Pump)
        {
            IsBackground = true,
            Name = "ClipBridge.ClipboardSta",
        };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
    }

    private void Pump()
    {
        foreach (var job in _queue.GetConsumingEnumerable())
        {
            // 单个任务抛异常不能拖垮整个泵
            try { job(); }
            catch { }
        }
    }

    /// <summary>在 STA 线程上执行并等待结果。</summary>
    public Task<T> InvokeAsync<T>(Func<T> job)
    {
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);

        if (_disposed)
        {
            tcs.SetException(new ObjectDisposedException(nameof(StaDispatcher)));
            return tcs.Task;
        }

        try
        {
            _queue.Add(() =>
            {
                try { tcs.SetResult(job()); }
                catch (Exception ex) { tcs.SetException(ex); }
            });
        }
        catch (InvalidOperationException)
        {
            // 队列已关闭（正在退出）
            tcs.SetException(new ObjectDisposedException(nameof(StaDispatcher)));
        }

        return tcs.Task;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { _queue.CompleteAdding(); } catch { }
    }
}
