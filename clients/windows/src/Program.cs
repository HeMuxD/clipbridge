using System.Text.Json;
using ClipBridge.Models;
using ClipBridge.Services;
using ClipBridge.UI;
using ClipBridge.Utils;

namespace ClipBridge;

/// <summary>
/// 程序入口与托盘生命周期管理。
///
/// 整体结构：
///   ApplicationContext（无主窗口）
///     └── NotifyIcon 托盘图标
///           ├── SyncOrchestrator（剪贴板监听 + 截图监控 + 网络同步）
///           └── SettingsForm（按需弹出的设置窗口）
/// </summary>
internal sealed class TrayApplication : ApplicationContext
{
    private readonly AppSettings _settings;
    private readonly SyncOrchestrator _orchestrator;
    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _connectItem;

    private SettingsForm? _settingsForm;

    /// <summary>
    /// 专供切线程用的隐藏控件：句柄在 UI 线程上创建，
    /// 之后 InvokeRequired / Invoke 的判断都可靠（ToolStrip 做不到这一点）。
    /// </summary>
    private readonly Control _uiAnchor = new();

    public TrayApplication(bool startMinimized, string[]? sharePaths = null)
    {
        // 先把锚点控件的句柄建出来 —— 此刻就在 UI 线程上
        _ = _uiAnchor.Handle;

        _settings = SettingsStore.Load();
        Log.Verbose = false;

        Log.Info("========== ClipBridge 启动 ==========");
        Log.Info($"设备 ID：{_settings.DeviceId}，设备名：{_settings.DeviceName}");
        Log.Info($"服务端：{(_settings.ServerUrl.Length > 0 ? _settings.ServerUrl : "(未配置)")}");

        var client = new SyncClient(_settings);
        _orchestrator = new SyncOrchestrator(_settings, client);

        // ---- 托盘菜单 ----
        _statusItem = new ToolStripMenuItem("状态：正在启动…") { Enabled = false };
        _connectItem = new ToolStripMenuItem("重新连接") { Enabled = false };

        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(_connectItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("设置…", null, (_, _) => ShowSettings()));
        menu.Items.Add(new ToolStripMenuItem("立即同步剪贴板", null, (_, _) => ForceSyncNow()));
        menu.Items.Add(new ToolStripMenuItem("分享图片文件…", null, (_, _) => PickAndShareImage()));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("打开日志目录", null, (_, _) => OpenLogs()));
        menu.Items.Add(new ToolStripMenuItem("退出", null, (_, _) => ExitApp()));

        _connectItem.Click += (_, _) => _orchestrator.Reload();

        _tray = new NotifyIcon
        {
            Icon = UI.TrayIcon.CreateIcon(),
            Text = "ClipBridge — 跨端同步",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _tray.DoubleClick += (_, _) => ShowSettings();
        _tray.BalloonTipClicked += (_, _) => ShowSettings();

        // ---- 事件接线 ----
        _orchestrator.StatusMessage += OnStatusMessage;
        _orchestrator.ConnectionChanged += OnConnectionChanged;
        _orchestrator.RemoteContentReceived += OnRemoteContent;

        _orchestrator.Start();

        // 「发送到」的投递通道。必须在"处理首个 sharePaths"之前启动：
        // 用户可能连续发几次，每一下都是一个新进程来投递。
        ShareIpc.StartServer(paths => RunOnUi(() => _ = ShareImagesAsync(paths)));

        _ = Task.Run(() =>
        {
            try
            {
                // 旧版本把入口放在右键首层（<ext>\shell\ClipBridgeShare）。
                // 现在统一到「发送到」里，这里清掉残留，避免出现两个入口。
                SendToMenu.RemoveLegacyShellVerb();

                var wanted = _settings.SendToMenu;
                if (wanted)
                {
                    // 目标路径可能因程序换目录而失效，所以只要不是当前 exe 就重写
                    if (!SendToMenu.IsUpToDate()) SendToMenu.SetEnabled(true);
                }
                else if (SendToMenu.IsEnabled())
                {
                    SendToMenu.SetEnabled(false);
                }
            }
            catch (Exception ex)
            {
                Log.Warn($"同步「发送到」菜单失败：{ex.Message}");
            }
        });

        if (startMinimized)
        {
            Notify("ClipBridge 已在后台运行",
                string.IsNullOrEmpty(_settings.Token)
                    ? "尚未配对，双击图标完成设置"
                    : "剪贴板与截图将自动同步");
        }
        else if (string.IsNullOrEmpty(_settings.ServerUrl) || string.IsNullOrEmpty(_settings.Token))
        {
            // 首次运行直接打开设置向导
            ShowSettings();
        }

        // 本次就是被「发送到」拉起来的：直接走本地调用，
        // 不必绕一圈管道交给自己。
        if (sharePaths is { Length: > 0 })
        {
            _ = ShareImagesAsync(sharePaths);
        }
    }

    /// <summary>
    /// 从托盘菜单选一个图片文件来分享。
    ///
    /// 有这个入口，是因为右键「发送到」只对资源管理器里选中的文件生效，
    /// 而用户可能想分享一张不在那里的图（例如从聊天软件里另存出来的）。
    /// 既然底层已支持批量，这里也放开多选。
    /// </summary>
    private void PickAndShareImage()
    {
        RunOnUi(() =>
        {
            using var dlg = new OpenFileDialog
            {
                Title = "选择要分享的图片",
                Filter = "图片文件|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp|所有文件|*.*",
                CheckFileExists = true,
                Multiselect = true,
            };
            if (dlg.ShowDialog() != DialogResult.OK) return;
            _ = ShareImagesAsync(dlg.FileNames);
        });
    }

    /// <summary>
    /// 分享一批本地图片文件。所有入口（发送到菜单首启 / 管道投递 / 托盘菜单）
    /// 最终都汇到这里，保证行为一致。
    ///
    /// 逐个串行发送：并发上传会同时占用多条连接，在弱网下反而更容易失败，
    /// 而且用户看到的状态提示会互相覆盖。
    /// </summary>
    private async Task ShareImagesAsync(string[] paths)
    {
        if (paths.Length == 0) return;

        for (var i = 0; i < paths.Length; i++)
        {
            var name = Path.GetFileName(paths[i]);
            _statusItem.Text = paths.Length == 1
                ? $"状态：正在分享 {name}…"
                : $"状态：正在分享 {name}（{i + 1}/{paths.Length}）…";

            await _orchestrator.ShareImageFileAsync(paths[i]);
        }
    }

    // ---------- 事件处理 ----------

    private void OnStatusMessage(string message)
    {
        RunOnUi(() =>
        {
            _statusItem.Text = "状态：" + message;
            Log.Info($"[状态] {message}");
        });
    }

    private void OnConnectionChanged(bool connected)
    {
        RunOnUi(() =>
        {
            _connectItem.Enabled = !connected;
            _connectItem.Text = connected ? "已连接" : "重新连接";

            // 换图标表示连接状态
            var old = _tray.Icon;
            _tray.Icon = UI.TrayIcon.CreateIcon(connected);
            old?.Dispose();

            _tray.Text = connected
                ? "ClipBridge — 已连接"
                : "ClipBridge — 未连接";
        });
    }

    private void OnRemoteContent(string description)
    {
        if (!_settings.ShowNotifications) return;
        RunOnUi(() => Notify("ClipBridge", description));
    }

    // ---------- 菜单动作 ----------

    private void ShowSettings()
    {
        RunOnUi(() =>
        {
            if (_settingsForm is { IsDisposed: false })
            {
                _settingsForm.Show();
                _settingsForm.WindowState = FormWindowState.Normal;
                _settingsForm.Activate();
                return;
            }

            _settingsForm = new SettingsForm(_settings, _orchestrator);
            _settingsForm.FormClosed += (_, _) => _settingsForm = null;
            _settingsForm.Show();

            // 从托盘菜单弹出的窗口不会自动前置，显式把它拉到前面
            _settingsForm.WindowState = FormWindowState.Normal;
            _settingsForm.BringToFront();
            _settingsForm.Activate();
        });
    }

    /// <summary>
    /// 手动触发一次剪贴板同步：把此刻剪贴板里的内容直接发出去。
    /// 用于监听漏掉事件、或用户想强制重发当前内容的场景。
    /// </summary>
    private void ForceSyncNow()
    {
        RunOnUi(() =>
        {
            // 读取剪贴板必须在 UI（STA）线程上做，所以这里同步调用；
            // 发送过程本身是异步的，不阻塞界面。
            _ = _orchestrator.ForceSyncNowAsync();
        });
    }

    private void OpenLogs()
    {
        try
        {
            System.Diagnostics.Process.Start("explorer.exe", Log.LogDirectory);
        }
        catch (Exception ex)
        {
            Log.Error("打开日志目录失败", ex);
        }
    }

    private void ExitApp()
    {
        Log.Info("用户请求退出");
        try
        {
            _orchestrator.Dispose();
        }
        catch { }

        _tray.Visible = false;
        _tray.Dispose();
        ExitThread();
    }

    // ---------- 工具 ----------

    private void Notify(string title, string text)
    {
        try
        {
            _tray.BalloonTipTitle = title;
            _tray.BalloonTipText = text;
            _tray.BalloonTipIcon = ToolTipIcon.Info;
            _tray.ShowBalloonTip(3000);
        }
        catch { }
    }

    /// <summary>
    /// 确保在 UI 线程上执行。
    ///
    /// 以前这里拿 `_tray.ContextMenuStrip.InvokeRequired` 当判断依据 ——
    /// ToolStrip 是轻量控件、句柄创建时机不稳定，第一次从托盘菜单点「设置…」时
    /// 判断会出错，Invoke 抛出的异常又被 `catch { }` 吞掉，
    /// 表现就是"第一次点没反应，得再点一次"。
    /// 现在改用一个句柄明确属于 UI 线程的隐藏控件当锚点，
    /// 并且异常记进日志而不是静默吞掉。
    /// </summary>
    private void RunOnUi(Action action)
    {
        try
        {
            if (_uiAnchor.InvokeRequired)
                _uiAnchor.Invoke(action);
            else
                action();
        }
        catch (Exception ex)
        {
            Log.Error("切到 UI 线程执行失败", ex);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _orchestrator.Dispose();
            _tray.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>CLI 入口。全局异常兜底在这里统一处理。</summary>
internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // 「发送到 → 使用 ClipBridge 推送」传入的图片路径（可能多个）。
        // 必须在处理单实例之前解析：这条路走的是"转发给已在运行的实例"，
        // 而不是"再启动一个实例"。
        var sharePaths = GetSharePaths(args);

        // 单实例：多开会导致同一份剪贴板被重复上报
        using var single = new SingleInstance();
        if (!single.IsFirstInstance)
        {
            if (sharePaths.Length > 0)
            {
                // 已有实例在跑：把路径交给它，自己不弹窗、不建托盘、立刻退出。
                if (!ShareIpc.TrySend(sharePaths))
                {
                    MessageBox.Show(
                        "无法把图片转交给正在运行的 ClipBridge。\n请确认托盘图标还在，或重启程序后重试。",
                        "ClipBridge", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
                return;
            }

            MessageBox.Show("ClipBridge 已在运行中，请在系统托盘中查看。",
                "ClipBridge", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        // 未捕获异常必须记日志，否则用户遇到崩溃时无从排查
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex)
                Log.Error("未捕获异常", ex);
        };
        Application.ThreadException += (_, e) => Log.Error("UI 线程异常", e.Exception);
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Log.Error("后台任务异常", e.Exception);
            e.SetObserved();
        };

        ApplicationConfiguration.Initialize();

        var startMinimized = args.Any(a =>
            a.Equals("--minimized", StringComparison.OrdinalIgnoreCase) ||
            a.Equals("-m", StringComparison.OrdinalIgnoreCase));

        try
        {
            Application.Run(new TrayApplication(startMinimized, sharePaths));
        }
        catch (Exception ex)
        {
            Log.Error("程序异常终止", ex);
            MessageBox.Show($"ClipBridge 遇到错误并退出：\n\n{ex.Message}\n\n日志位置：\n{Log.LogDirectory}",
                "ClipBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    /// <summary>
    /// 从命令行解析「要分享的图片路径」。
    ///
    /// 两种来源都要支持：
    ///   1. <c>--share-image &lt;路径&gt;</c>（我们自己定义的开关，注册表 verb 用它）；
    ///   2. <c>--share-image</c> 后面【什么都不带】的裸路径列表 ——
    ///      这是「发送到」的工作方式：资源管理器把选中文件的路径追加在
    ///      快捷方式的 Arguments 之后，所以启动命令行长这样：
    ///        ClipBridge.exe --share-image "C:\a.png" "C:\b.png"
    ///      多选文件时路径依次追加。
    ///
    /// 空数组表示这不是一次分享调用。
    /// </summary>
    private static string[] GetSharePaths(string[] args)
    {
        var result = new List<string>();
        var isShareCall = false;

        foreach (var raw in args)
        {
            var a = raw.Trim('"');
            if (a.Length == 0) continue;

            if (a.StartsWith("--share-image=", StringComparison.OrdinalIgnoreCase))
            {
                isShareCall = true;
                var inline = a["--share-image=".Length..].Trim('"');
                if (inline.Length > 0) result.Add(inline);
                continue;
            }

            if (a.Equals("--share-image", StringComparison.OrdinalIgnoreCase))
            {
                isShareCall = true;
                continue;
            }

            // 其它以 -- 开头的开关（如 --minimized）跳过，不当成路径
            if (a.StartsWith("--", StringComparison.Ordinal)) continue;

            // 剩下的按路径收下（SendTo 追加的那些）
            if (isShareCall) result.Add(a);
        }

        return result.ToArray();
    }
}
