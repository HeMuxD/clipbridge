using System.Text.Json.Serialization;

namespace ClipBridge.Models;

/// <summary>消息类型常量，与 shared/protocol/PROTOCOL.md 保持一致</summary>
public static class MsgType
{
    public const string Hello = "hello";
    public const string Clip = "clip";
    public const string Ack = "ack";
    public const string Ping = "ping";
    public const string Pong = "pong";
    public const string DeviceList = "device_list";
    public const string Error = "error";
}

public static class ClipKind
{
    public const string Text = "text";
    public const string Image = "image";
}

public static class ClipOrigin
{
    public const string Clipboard = "clipboard";
    public const string Screenshot = "screenshot";
    public const string Share = "share";
    public const string Manual = "manual";
}

/// <summary>所有 WebSocket 消息的统一信封</summary>
public sealed class Envelope
{
    [JsonPropertyName("type")] public string Type { get; set; } = "";
    [JsonPropertyName("msgId")] public string MsgId { get; set; } = "";
    [JsonPropertyName("ts")] public long Ts { get; set; }
    [JsonPropertyName("payload")] public ClipPayload? Payload { get; set; }
}

/// <summary>clip 消息的消息体</summary>
public sealed class ClipPayload
{
    [JsonPropertyName("kind")] public string Kind { get; set; } = ClipKind.Text;
    [JsonPropertyName("hash")] public string Hash { get; set; } = "";
    [JsonPropertyName("origin")] public string Origin { get; set; } = ClipOrigin.Clipboard;
    [JsonPropertyName("text")] public string? Text { get; set; }
    [JsonPropertyName("fileId")] public string? FileId { get; set; }
    [JsonPropertyName("url")] public string? Url { get; set; }
    [JsonPropertyName("mime")] public string? Mime { get; set; }
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("width")] public int Width { get; set; }
    [JsonPropertyName("height")] public int Height { get; set; }
    [JsonPropertyName("srcDevice")] public string? SrcDevice { get; set; }
    [JsonPropertyName("srcName")] public string? SrcName { get; set; }
}

/// <summary>配对请求</summary>
public sealed class PairRequest
{
    [JsonPropertyName("pairCode")] public string PairCode { get; set; } = "";
    [JsonPropertyName("deviceId")] public string DeviceId { get; set; } = "";
    [JsonPropertyName("deviceName")] public string DeviceName { get; set; } = "";
    [JsonPropertyName("platform")] public string Platform { get; set; } = "windows";
}

/// <summary>配对响应</summary>
public sealed class PairResponse
{
    [JsonPropertyName("token")] public string Token { get; set; } = "";
    [JsonPropertyName("deviceId")] public string DeviceId { get; set; } = "";
    [JsonPropertyName("expiresAt")] public long ExpiresAt { get; set; }
}

/// <summary>上传响应</summary>
public sealed class UploadResponse
{
    [JsonPropertyName("fileId")] public string FileId { get; set; } = "";
    [JsonPropertyName("url")] public string Url { get; set; } = "";
    [JsonPropertyName("mime")] public string Mime { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("hash")] public string Hash { get; set; } = "";
}

/// <summary>ACK 消息体</summary>
public sealed class AckPayload
{
    [JsonPropertyName("msgId")] public string MsgId { get; set; } = "";
    [JsonPropertyName("status")] public string Status { get; set; } = "ok";
    [JsonPropertyName("code")] public int Code { get; set; }
    [JsonPropertyName("reason")] public string? Reason { get; set; }
}

/// <summary>错误消息体</summary>
public sealed class ErrorPayload
{
    [JsonPropertyName("code")] public int Code { get; set; }
    [JsonPropertyName("message")] public string Message { get; set; } = "";
}

/// <summary>服务端错误码</summary>
public static class ErrorCode
{
    public const int InvalidToken = 1001;
    public const int DeviceNotPaired = 1002;
    public const int RateLimited = 2001;
    public const int FileTooLarge = 2002;
    public const int Duplicate = 2003;
    public const int Internal = 5001;
}

/// <summary>本地持久化的配置</summary>
public sealed class AppSettings
{
    /// <summary>服务端地址，如 https://clip.example.com</summary>
    public string ServerUrl { get; set; } = "";

    /// <summary>配对后获得的 Token</summary>
    public string Token { get; set; } = "";

    /// <summary>本机设备 ID，首次运行时生成并保持不变</summary>
    public string DeviceId { get; set; } = "";

    /// <summary>展示给其他设备的名称</summary>
    public string DeviceName { get; set; } = "";

    /// <summary>是否同步文本</summary>
    public bool SyncText { get; set; } = true;

    /// <summary>是否同步图片（截图）</summary>
    public bool SyncImage { get; set; } = true;

    /// <summary>是否监控系统截图目录（Win+PrtScn 的落盘位置）</summary>
    public bool WatchScreenshotFolder { get; set; } = true;

    /// <summary>截图目录路径，留空则用系统默认</summary>
    public string ScreenshotFolder { get; set; } = "";

    /// <summary>是否开机自启</summary>
    public bool AutoStart { get; set; }

    /// <summary>单条内容大小上限（字节）</summary>
    public long MaxContentBytes { get; set; } = 20L * 1024 * 1024;

    /// <summary>不参与同步的进程名（不含 .exe，小写）。用于排除密码管理器等</summary>
    public List<string> ExcludedProcesses { get; set; } = new();

    /// <summary>是否启用本地敏感内容检测</summary>
    public bool FilterSensitive { get; set; } = true;

    /// <summary>是否在收到远端内容时弹出托盘提示</summary>
    public bool ShowNotifications { get; set; } = true;
}
