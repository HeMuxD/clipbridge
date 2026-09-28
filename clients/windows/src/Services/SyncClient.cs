using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using ClipBridge.Models;

namespace ClipBridge.Services;

/// <summary>
/// 与服务端的连接管理：
///   · HTTP：配对、上传、历史查询
///   · WebSocket：实时收发 clip 消息
///
/// 内置指数退避重连，网络恢复后自动恢复同步，无需用户干预。
/// </summary>
public sealed class SyncClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private readonly HttpClient _http;
    private readonly AppSettings _settings;

    private ClientWebSocketCompat? _ws;
    private CancellationTokenSource? _cts;
    private Task? _runTask;

    // 等待 ACK 的消息：msgId -> TaskCompletionSource
    private readonly ConcurrentDictionary<string, TaskCompletionSource<bool>> _pendingAcks = new();

    private int _reconnectAttempt;

    public event Action<bool>? ConnectionChanged;
    public event Action<ClipPayload>? ClipReceived;
    public event Action<string>? StatusMessage;

    public bool IsConnected { get; private set; }

    public SyncClient(AppSettings settings)
    {
        _settings = settings;
        _http = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(60),
        };
        if (!string.IsNullOrEmpty(settings.Token))
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", settings.Token);
    }

    private string BaseUrl => _settings.ServerUrl.TrimEnd('/');

    private string WsUrl
    {
        get
        {
            var b = BaseUrl;
            // http -> ws, https -> wss
            if (b.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                return "wss://" + b[8..] + "/ws/v1";
            if (b.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
                return "ws://" + b[7..] + "/ws/v1";
            return "wss://" + b + "/ws/v1";
        }
    }

    // ---------- 配对 ----------

    /// <summary>用配对码换取 Token</summary>
    public async Task<bool> PairAsync(string pairCode)
    {
        var req = new PairRequest
        {
            PairCode = pairCode.Trim(),
            DeviceId = _settings.DeviceId,
            DeviceName = _settings.DeviceName,
            Platform = "windows",
        };

        try
        {
            var body = JsonSerializer.Serialize(req, JsonOpts);
            using var content = new StringContent(body, Encoding.UTF8, "application/json");
            using var resp = await _http.PostAsync($"{BaseUrl}/api/pair", content);

            if (!resp.IsSuccessStatusCode)
            {
                StatusMessage?.Invoke(resp.StatusCode == System.Net.HttpStatusCode.Unauthorized
                    ? "配对码无效或已过期"
                    : $"配对失败：HTTP {(int)resp.StatusCode}");
                return false;
            }

            var json = await resp.Content.ReadAsStringAsync();
            var result = JsonSerializer.Deserialize<PairResponse>(json, JsonOpts);
            if (result is null || string.IsNullOrEmpty(result.Token))
            {
                StatusMessage?.Invoke("服务端返回的配对结果无效");
                return false;
            }

            _settings.Token = result.Token;
            _http.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Bearer", result.Token);
            StatusMessage?.Invoke("配对成功");
            return true;
        }
        catch (Exception ex)
        {
            StatusMessage?.Invoke($"无法连接服务端：{ex.Message}");
            return false;
        }
    }

    // ---------- 上传 ----------

    /// <summary>上传图片，返回服务端分配的 fileId 与下载地址</summary>
    public async Task<UploadResponse?> UploadImageAsync(byte[] pngBytes, string hash)
    {
        try
        {
            using var form = new MultipartFormDataContent();
            var fileContent = new ByteArrayContent(pngBytes);
            fileContent.Headers.ContentType = new MediaTypeHeaderValue("image/png");
            form.Add(fileContent, "file", "clip.png");
            form.Add(new StringContent(hash), "hash");

            using var resp = await _http.PostAsync($"{BaseUrl}/api/upload", form);
            if (!resp.IsSuccessStatusCode)
            {
                var err = await resp.Content.ReadAsStringAsync();
                StatusMessage?.Invoke($"上传失败：HTTP {(int)resp.StatusCode} {Trim(err)}");
                return null;
            }

            var json = await resp.Content.ReadAsStringAsync();
            return JsonSerializer.Deserialize<UploadResponse>(json, JsonOpts);
        }
        catch (Exception ex)
        {
            StatusMessage?.Invoke($"上传异常：{ex.Message}");
            return null;
        }
    }

    /// <summary>下载图片</summary>
    public async Task<byte[]?> DownloadImageAsync(string url)
    {
        try
        {
            using var resp = await _http.GetAsync(url);
            if (!resp.IsSuccessStatusCode) return null;
            return await resp.Content.ReadAsByteArrayAsync();
        }
        catch
        {
            return null;
        }
    }

    // ---------- 连接管理 ----------

    /// <summary>启动连接循环（阻塞在后台任务里，自动重连）</summary>
    public void Start()
    {
        if (_runTask is not null) return;

        _cts = new CancellationTokenSource();
        _runTask = Task.Run(() => RunLoopAsync(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        try { _ws?.Dispose(); } catch { }
        _ws = null;
    }

    private async Task RunLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await ConnectAndPumpAsync(ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                StatusMessage?.Invoke($"连接中断：{ex.Message}");
            }

            if (ct.IsCancellationRequested) break;

            // 指数退避重连，加抖动避免多设备同时冲击服务端
            var delay = Math.Min(60, Math.Pow(2, Math.Min(_reconnectAttempt, 6)));
            var jitter = Random.Shared.NextDouble() * 0.4 - 0.2;
            var seconds = Math.Max(1, delay * (1 + jitter));
            _reconnectAttempt++;

            StatusMessage?.Invoke($"将在 {seconds:F0} 秒后重连…");
            try { await Task.Delay(TimeSpan.FromSeconds(seconds), ct); }
            catch (OperationCanceledException) { break; }
        }
    }

    private async Task ConnectAndPumpAsync(CancellationToken ct)
    {
        if (string.IsNullOrEmpty(_settings.Token))
            throw new InvalidOperationException("尚未配对");

        using var ws = new System.Net.WebSockets.ClientWebSocket();
        ws.Options.SetRequestHeader("Authorization", $"Bearer {_settings.Token}");
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);

        await ws.ConnectAsync(new Uri(WsUrl), ct);
        _ws = new ClientWebSocketCompat(ws);

        _reconnectAttempt = 0;
        IsConnected = true;
        ConnectionChanged?.Invoke(true);
        StatusMessage?.Invoke("已连接到服务端");

        // 发送自我介绍
        await SendAsync(ws, new Envelope
        {
            Type = MsgType.Hello,
            MsgId = Guid.NewGuid().ToString(),
            Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        }, ct);

        var heartbeat = HeartbeatLoopAsync(ws, ct);
        try
        {
            await ReceiveLoopAsync(ws, ct);
        }
        finally
        {
            IsConnected = false;
            ConnectionChanged?.Invoke(false);
            _ws = null;
        }
    }

    private async Task ReceiveLoopAsync(System.Net.WebSockets.ClientWebSocket ws, CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
        var accumulated = new MemoryStream();

        while (!ct.IsCancellationRequested && ws.State == System.Net.WebSockets.WebSocketState.Open)
        {
            var result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);

            if (result.MessageType == System.Net.WebSockets.WebSocketMessageType.Close)
            {
                await ws.CloseOutputAsync(
                    System.Net.WebSockets.WebSocketCloseStatus.NormalClosure, "", ct);
                break;
            }

            accumulated.Write(buffer, 0, result.Count);

            if (!result.EndOfMessage)
            {
                // 消息较大时可能分多帧，继续累积
                if (accumulated.Length > 8 * 1024 * 1024)
                {
                    // 保护：单条消息超过 8MB 直接丢弃，避免内存被打爆
                    accumulated.SetLength(0);
                    StatusMessage?.Invoke("收到超大消息，已丢弃");
                }
                continue;
            }

            var json = Encoding.UTF8.GetString(accumulated.ToArray());
            accumulated.SetLength(0);

            try
            {
                HandleMessage(json);
            }
            catch (Exception ex)
            {
                StatusMessage?.Invoke($"消息处理失败：{ex.Message}");
            }
        }
    }

    private void HandleMessage(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        var type = root.TryGetProperty("type", out var t) ? t.GetString() : null;
        switch (type)
        {
            case MsgType.Clip:
                if (root.TryGetProperty("payload", out var payloadEl))
                {
                    var payload = payloadEl.Deserialize<ClipPayload>(JsonOpts);
                    if (payload is not null && !string.IsNullOrEmpty(payload.Hash))
                        ClipReceived?.Invoke(payload);
                }
                break;

            case MsgType.Ack:
                if (root.TryGetProperty("payload", out var ackEl))
                {
                    var ack = ackEl.Deserialize<AckPayload>(JsonOpts);
                    if (ack is not null && _pendingAcks.TryRemove(ack.MsgId, out var tcs))
                    {
                        tcs.TrySetResult(ack.Status == "ok");
                    }
                }
                break;

            case MsgType.Error:
                if (root.TryGetProperty("payload", out var errEl))
                {
                    var err = errEl.Deserialize<ErrorPayload>(JsonOpts);
                    if (err is not null)
                    {
                        StatusMessage?.Invoke($"服务端错误 {err.Code}：{err.Message}");
                        if (err.Code == ErrorCode.InvalidToken)
                            StatusMessage?.Invoke("Token 已失效，请重新配对");
                    }
                }
                break;

            case MsgType.Pong:
                break;

            case MsgType.DeviceList:
                break;
        }
    }

    /// <summary>心跳：30 秒一次。服务端也依赖它维持连接活性。</summary>
    private async Task HeartbeatLoopAsync(System.Net.WebSockets.ClientWebSocket ws, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested && ws.State == System.Net.WebSockets.WebSocketState.Open)
            {
                await Task.Delay(TimeSpan.FromSeconds(30), ct);
                if (ws.State != System.Net.WebSockets.WebSocketState.Open) break;

                try
                {
                    await SendAsync(ws, new Envelope
                    {
                        Type = MsgType.Ping,
                        MsgId = Guid.NewGuid().ToString(),
                        Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    }, ct);
                }
                catch
                {
                    break;
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    // ---------- 发送 ----------

    /// <summary>发送内容并等待 ACK。返回是否成功。</summary>
    public async Task<bool> SendClipAsync(ClipPayload payload, int timeoutSeconds = 10)
    {
        var ws = _ws?.Inner;
        if (ws is null || ws.State != System.Net.WebSockets.WebSocketState.Open)
        {
            StatusMessage?.Invoke("未连接，内容将不会同步");
            return false;
        }

        var msgId = Guid.NewGuid().ToString();
        var env = new Envelope
        {
            Type = MsgType.Clip,
            MsgId = msgId,
            Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Payload = payload,
        };

        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingAcks[msgId] = tcs;

        try
        {
            await SendAsync(ws, env, CancellationToken.None);

            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
            using var reg = cts.Token.Register(() => tcs.TrySetResult(false));
            return await tcs.Task;
        }
        catch (Exception ex)
        {
            StatusMessage?.Invoke($"发送失败：{ex.Message}");
            return false;
        }
        finally
        {
            _pendingAcks.TryRemove(msgId, out _);
        }
    }

    private static Task SendAsync(System.Net.WebSockets.ClientWebSocket ws, Envelope env, CancellationToken ct)
    {
        var json = JsonSerializer.Serialize(env, JsonOpts);
        var bytes = Encoding.UTF8.GetBytes(json);
        return ws.SendAsync(new ArraySegment<byte>(bytes),
            System.Net.WebSockets.WebSocketMessageType.Text, true, ct);
    }

    private static string Trim(string s)
        => s.Length > 200 ? s[..200] + "…" : s;

    public void Dispose()
    {
        Stop();
        _http.Dispose();
    }

    /// <summary>
    /// 简单包装，便于在发送时判断内部 socket 状态。
    /// </summary>
    private sealed class ClientWebSocketCompat(System.Net.WebSockets.ClientWebSocket inner) : IDisposable
    {
        public System.Net.WebSockets.ClientWebSocket Inner { get; } = inner;
        public void Dispose() => Inner.Dispose();
    }
}
