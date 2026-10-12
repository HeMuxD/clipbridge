using ClipBridge.Models;
using ClipBridge.Services;
using ClipBridge.Utils;
using ClipBridge.UI;

namespace ClipBridge.UI;

/// <summary>
/// 设置窗口。纯代码构建 UI，不依赖设计器文件，便于版本管理。
/// </summary>
public sealed class SettingsForm : Form
{
    private readonly AppSettings _settings;
    private readonly SyncOrchestrator _orchestrator;

    private readonly TextBox _serverUrl = new();
    private readonly TextBox _deviceName = new();
    private readonly TextBox _pairCode = new();
    private readonly CheckBox _syncText = new();
    private readonly CheckBox _syncImage = new();
    private readonly CheckBox _watchFolder = new();
    private readonly CheckBox _saveImages = new();
    private readonly TextBox _imageFolder = new();
    private readonly Button _browseFolder = new();
    private readonly Button _openFolder = new();
    private readonly CheckBox _autoStart = new();
    private readonly CheckBox _filterSensitive = new();
    private readonly TextBox _excluded = new();
    private readonly Label _status = new();
    private readonly Label _connStatus = new();
    private readonly Button _pairButton = new();

    /// <summary>是否正在跑一次配对请求（期间配对按钮要禁用）</summary>
    private bool _pairing;

    public SettingsForm(AppSettings settings, SyncOrchestrator orchestrator)
    {
        _settings = settings;
        _orchestrator = orchestrator;

        BuildUi();
        LoadValues();

        _orchestrator.StatusMessage += OnStatus;
        _orchestrator.ConnectionChanged += OnConnectionChanged;
        FormClosed += (_, _) =>
        {
            _orchestrator.StatusMessage -= OnStatus;
            _orchestrator.ConnectionChanged -= OnConnectionChanged;
        };
    }

    private void BuildUi()
    {
        Text = "ClipBridge 设置";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(520, 656);
        Font = new Font("Microsoft YaHei UI", 9F);
        Icon = TrayIcon.CreateIcon();

        var y = 16;

        // ---- 服务端 ----
        AddSection("服务端", ref y);
        AddLabeledInput("服务端地址", _serverUrl, ref y, "例如 https://clip.example.com");
        AddLabeledInput("设备名称", _deviceName, ref y, "显示在其他设备上的名字");

        // ---- 配对 ----
        AddSection("配对", ref y);
        AddLabeledInput("配对码", _pairCode, ref y, "在服务端日志中查看，8 位数字");

        _pairButton.Text = "配对 / 重新配对";
        _pairButton.Location = new Point(16, y);
        _pairButton.Size = new Size(150, 30);
        _pairButton.Click += OnPairClick;
        Controls.Add(_pairButton);

        // 地址一填上就立刻可点。如果只在 LoadValues 里算一次，
        // 首次运行（配置里还没有地址）按钮会一直是灰的，用户根本点不动。
        _serverUrl.TextChanged += (_, _) => UpdatePairButtonState();

        _connStatus.Location = new Point(178, y + 6);
        _connStatus.Size = new Size(320, 20);
        _connStatus.ForeColor = Color.Gray;
        _connStatus.Text = "未连接";
        Controls.Add(_connStatus);
        y += 42;

        // ---- 同步选项 ----
        AddSection("同步内容", ref y);

        _syncText.Text = "同步剪贴板文本";
        _syncText.Location = new Point(16, y);
        _syncText.Size = new Size(300, 22);
        Controls.Add(_syncText);
        y += 26;

        _syncImage.Text = "同步图片 / 截图（剪贴板中的图像）";
        _syncImage.Location = new Point(16, y);
        _syncImage.Size = new Size(400, 22);
        Controls.Add(_syncImage);
        y += 26;

        // 与 Android 端"收到图片存一份到相册"对应
        _saveImages.Text = "收到的图片另存一份到本地（手机端对应相册 Pictures/ClipBridge）";
        _saveImages.Location = new Point(16, y);
        _saveImages.Size = new Size(470, 22);
        _saveImages.CheckedChanged += (_, _) => UpdateImageFolderEnabled();
        Controls.Add(_saveImages);
        y += 26;

        _imageFolder.Location = new Point(38, y);
        _imageFolder.Size = new Size(326, 24);
        _imageFolder.PlaceholderText = "留空则用 图片\\ClipBridge";
        Controls.Add(_imageFolder);

        _browseFolder.Text = "浏览…";
        _browseFolder.Location = new Point(372, y - 2);
        _browseFolder.Size = new Size(62, 26);
        _browseFolder.Click += OnBrowseImageFolder;
        Controls.Add(_browseFolder);

        _openFolder.Text = "打开";
        _openFolder.Location = new Point(438, y - 2);
        _openFolder.Size = new Size(62, 26);
        _openFolder.Click += OnOpenImageFolder;
        Controls.Add(_openFolder);
        y += 34;

        _watchFolder.Text = "监控系统截图目录（覆盖 Win+PrtScn）";
        _watchFolder.Location = new Point(16, y);
        _watchFolder.Size = new Size(400, 22);
        Controls.Add(_watchFolder);
        y += 30;

        _filterSensitive.Text = "启用敏感内容检测（跳过疑似密码 / 卡号 / 密钥）";
        _filterSensitive.Location = new Point(16, y);
        _filterSensitive.Size = new Size(440, 22);
        Controls.Add(_filterSensitive);
        y += 30;

        // ---- 排除的应用 ----
        AddSection("排除的应用", ref y);
        var exclLabel = new Label
        {
            Text = "从这些应用复制的内容不会被同步（逗号分隔进程名，不含 .exe）",
            Location = new Point(16, y),
            Size = new Size(480, 20),
            ForeColor = Color.Gray,
        };
        Controls.Add(exclLabel);
        y += 22;

        _excluded.Location = new Point(16, y);
        _excluded.Size = new Size(486, 24);
        _excluded.PlaceholderText = "例如：keepassxc, 1password, bitwarden";
        Controls.Add(_excluded);
        y += 34;

        // ---- 启动 ----
        AddSection("启动", ref y);

        _autoStart.Text = "开机自动启动";
        _autoStart.Location = new Point(16, y);
        _autoStart.Size = new Size(300, 22);
        Controls.Add(_autoStart);
        y += 32;

        // ---- 状态栏与按钮 ----
        _status.Location = new Point(16, y);
        _status.Size = new Size(486, 40);
        _status.ForeColor = Color.DimGray;
        _status.Text = "就绪";
        Controls.Add(_status);

        var save = new Button
        {
            Text = "保存",
            Location = new Point(330, ClientSize.Height - 44),
            Size = new Size(84, 30),
        };
        save.Click += OnSaveClick;
        Controls.Add(save);

        var cancel = new Button
        {
            Text = "取消",
            Location = new Point(420, ClientSize.Height - 44),
            Size = new Size(84, 30),
            DialogResult = DialogResult.Cancel,
        };
        Controls.Add(cancel);

        var openLogs = new Button
        {
            Text = "打开日志",
            Location = new Point(16, ClientSize.Height - 44),
            Size = new Size(96, 30),
        };
        openLogs.Click += (_, _) =>
        {
            try
            {
                System.Diagnostics.Process.Start("explorer.exe", Log.LogDirectory);
            }
            catch { }
        };
        Controls.Add(openLogs);

        CancelButton = cancel;
    }

    private readonly List<(string Title, int Y)> _sections = new();

    private void AddSection(string title, ref int y)
    {
        var label = new Label
        {
            Text = title,
            Location = new Point(16, y),
            Size = new Size(480, 22),
            Font = new Font("Microsoft YaHei UI", 9.5F, FontStyle.Bold),
            ForeColor = Color.FromArgb(40, 60, 90),
        };
        Controls.Add(label);
        y += 30;
    }

    private void AddLabeledInput(string labelText, TextBox box, ref int y, string hint)
    {
        var label = new Label
        {
            Text = labelText,
            Location = new Point(16, y),
            Size = new Size(90, 22),
            TextAlign = ContentAlignment.MiddleLeft,
        };
        Controls.Add(label);

        box.Location = new Point(110, y);
        box.Size = new Size(392, 24);
        box.PlaceholderText = hint;
        Controls.Add(box);

        y += 32;
    }

    private void LoadValues()
    {
        _serverUrl.Text = _settings.ServerUrl;
        _deviceName.Text = _settings.DeviceName;
        _syncText.Checked = _settings.SyncText;
        _syncImage.Checked = _settings.SyncImage;
        _watchFolder.Checked = _settings.WatchScreenshotFolder;
        _autoStart.Checked = AutoStart.IsEnabled();
        _filterSensitive.Checked = _settings.FilterSensitive;
        _excluded.Text = string.Join(", ", _settings.ExcludedProcesses);
        _saveImages.Checked = _settings.SaveReceivedImages;
        _imageFolder.Text = SettingsStore.ResolveImageFolder(_settings);

        UpdateImageFolderEnabled();
        UpdatePairButtonState();
        _connStatus.Text = _orchestrator.IsConnected
            ? "已连接"
            : (string.IsNullOrEmpty(_settings.Token) ? "未配对" : "未连接");
        _connStatus.ForeColor = _orchestrator.IsConnected ? Color.SeaGreen : Color.Gray;
    }

    /// <summary>
    /// 配对按钮何时可点：地址填了、且当前没有正在跑的配对请求。
    /// 由地址输入框的 TextChanged 实时驱动。
    /// </summary>
    private void UpdatePairButtonState()
    {
        _pairButton.Enabled = !_pairing && !string.IsNullOrWhiteSpace(_serverUrl.Text);
    }

    /// <summary>关掉"另存一份"后，目录框与两个按钮都失去意义</summary>
    private void UpdateImageFolderEnabled()
    {
        var on = _saveImages.Checked;
        _imageFolder.Enabled = on;
        _browseFolder.Enabled = on;
        _openFolder.Enabled = on;
    }

    private void OnBrowseImageFolder(object? sender, EventArgs e)
    {
        using var dlg = new FolderBrowserDialog
        {
            Description = "选择接收图片的保存目录",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true,
            SelectedPath = Directory.Exists(_imageFolder.Text) ? _imageFolder.Text : "",
        };
        if (dlg.ShowDialog(this) == DialogResult.OK)
        {
            _imageFolder.Text = dlg.SelectedPath;
        }
    }

    private void OnOpenImageFolder(object? sender, EventArgs e)
    {
        try
        {
            var dir = string.IsNullOrWhiteSpace(_imageFolder.Text)
                ? SettingsStore.DefaultImageFolder()
                : _imageFolder.Text.Trim();

            // 还没收到过图片时目录可能不存在，先建出来再打开，
            // 否则资源管理器会弹一个"找不到路径"
            Directory.CreateDirectory(dir);
            System.Diagnostics.Process.Start("explorer.exe", dir);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"打开目录失败：{ex.Message}", "提示",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void OnPairClick(object? sender, EventArgs e)
    {
        var url = _serverUrl.Text.Trim();
        if (string.IsNullOrWhiteSpace(url))
        {
            MessageBox.Show(this, "请先填写服务端地址", "提示",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (string.IsNullOrWhiteSpace(_pairCode.Text))
        {
            MessageBox.Show(this, "请填写配对码（可在服务端日志中查看）", "提示",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        // 先落盘地址与设备名，配对请求要用
        _settings.ServerUrl = url;
        _settings.DeviceName = _deviceName.Text.Trim();
        SettingsStore.Save(_settings);

        _pairing = true;
        UpdatePairButtonState();
        _status.Text = "正在配对…";

        // 配对必须在后台线程做，避免卡住 UI
        _ = Task.Run(async () =>
        {
            var client = new SyncClient(_settings);
            // 失败原因（配对码无效 / 连不上服务端）是通过这个事件报出来的。
            // 不接的话界面会一直停在"正在配对…"，用户看不到为什么失败。
            client.StatusMessage += OnStatus;

            var ok = false;
            try
            {
                ok = await client.PairAsync(_pairCode.Text);
            }
            finally
            {
                client.StatusMessage -= OnStatus;
                client.Dispose();
            }

            if (ok) SettingsStore.Save(_settings);

            try
            {
                BeginInvoke(() =>
                {
                    if (IsDisposed) return;

                    _pairing = false;
                    UpdatePairButtonState();

                    if (ok)
                    {
                        _pairCode.Text = "";
                        _status.Text = "配对成功，正在建立连接…";
                        _orchestrator.Reload();
                    }
                    else if (_status.Text == "正在配对…")
                    {
                        // 已经收到过具体原因就别覆盖它
                        _status.Text = "配对失败，详细原因见日志";
                    }
                });
            }
            catch (InvalidOperationException)
            {
                // 配对期间用户把窗口关了，忽略即可
            }
        });
    }

    private void OnSaveClick(object? sender, EventArgs e)
    {
        var url = _serverUrl.Text.Trim();
        if (!string.IsNullOrWhiteSpace(url) &&
            !url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) &&
            !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            MessageBox.Show(this, "服务端地址需要以 http:// 或 https:// 开头", "提示",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        // 提示：Android 9+ 默认禁止明文连接，用 http 会在手机端失败
        if (url.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
        {
            var choice = MessageBox.Show(this,
                "http:// 为明文连接，Android 端默认会拒绝。\n建议配置域名与证书后改用 https://。\n\n仍要保存吗？",
                "安全提示", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (choice != DialogResult.Yes) return;
        }

        _settings.ServerUrl = url;
        _settings.DeviceName = string.IsNullOrWhiteSpace(_deviceName.Text)
            ? Environment.MachineName
            : _deviceName.Text.Trim();
        _settings.SyncText = _syncText.Checked;
        _settings.SyncImage = _syncImage.Checked;
        _settings.WatchScreenshotFolder = _watchFolder.Checked;
        _settings.FilterSensitive = _filterSensitive.Checked;
        _settings.SaveReceivedImages = _saveImages.Checked;
        _settings.ImageSaveFolder = string.IsNullOrWhiteSpace(_imageFolder.Text)
            ? SettingsStore.DefaultImageFolder()
            : _imageFolder.Text.Trim();

        _settings.ExcludedProcesses = _excluded.Text
            .Split(new[] { ',', '，', ';', '；' }, StringSplitOptions.RemoveEmptyEntries)
            .Select(s => s.Trim().ToLowerInvariant())
            .Where(s => s.Length > 0)
            .Distinct()
            .ToList();

        SettingsStore.Save(_settings);

        if (!AutoStart.SetEnabled(_autoStart.Checked))
        {
            MessageBox.Show(this, "开机自启设置失败（可能被安全软件拦截）", "提示",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        _settings.AutoStart = _autoStart.Checked;

        _orchestrator.Reload();

        _status.Text = "设置已保存";
        Log.Info("设置已保存");
    }

    private void OnStatus(string message)
    {
        if (IsDisposed) return;
        try
        {
            BeginInvoke(() => _status.Text = message);
        }
        catch { }
    }

    private void OnConnectionChanged(bool connected)
    {
        if (IsDisposed) return;
        try
        {
            BeginInvoke(() =>
            {
                _connStatus.Text = connected ? "已连接" : "未连接";
                _connStatus.ForeColor = connected ? Color.SeaGreen : Color.Gray;
            });
        }
        catch { }
    }
}
