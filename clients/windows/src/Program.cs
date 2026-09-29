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

    public TrayApplication(bool startMinimized)
    {
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

    /// <summary>确保在 UI 线程上执行</summary>
    private void RunOnUi(Action action)
    {
        try
        {
            if (_tray.ContextMenuStrip?.InvokeRequired ?? false)
                _tray.ContextMenuStrip.Invoke(action);
            else
                action();
        }
        catch { }
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
        // 单实例：多开会导致同一份剪贴板被重复上报
        using var single = new SingleInstance();
        if (!single.IsFirstInstance)
        {
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
            Application.Run(new TrayApplication(startMinimized));
        }
        catch (Exception ex)
        {
            Log.Error("程序异常终止", ex);
            MessageBox.Show($"ClipBridge 遇到错误并退出：\n\n{ex.Message}\n\n日志位置：\n{Log.LogDirectory}",
                "ClipBridge", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
