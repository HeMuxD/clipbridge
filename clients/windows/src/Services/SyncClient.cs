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

    /// <summary>
    /// 发送串行化闸门。
    ///
    /// ClientWebSocket **不允许两个 SendAsync 并发执行** —— 并发时会抛
    /// InvalidOperationException（"There is already one outstanding 'SendAsync' call"）。
    /// 而本类有两个发送方：心跳循环（每 30s）与 SendClipAsync（用户每次复制）。
    /// 两者撞在同一条连接上只是概率问题，不是不可能 —— 一旦撞上，那条消息就丢了。
    /// AcknowledgeAsync 之类的其他调用同样受此约束。
    /// </summary>
    private readonly SemaphoreSlim _sendGate = new(1, 1);

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
        // 注意：这里【不要】设置 DefaultRequestHeaders.Authorization。
        //
        // 曾经的做法是在构造时按当时的 settings.Token 设一次默认头，结果是首次配对后
        // 图片上传/下载一直 401：配对走的是 SettingsForm 里另建的临时 SyncClient
        // （见 SettingsForm.OnPairClick），它只把新 Token 写回共享的 _settings，
        // 而本实例的 _http 默认头还是构造时的空值 —— 且 DefaultRequestHeaders 一旦
        // 在请求发出后被读过就会进入"已使用"状态，之后再改会抛异常。
        //
        // 改为每个请求现取 Token（见 NewAuthorizedRequest），配对成功立刻生效，
        // 不再依赖"实例构造时机 == Token 就绪时机"这个脆弱假设。
    }

    private string BaseUrl => _settings.ServerUrl.TrimEnd('/');

    /// <summary>
    /// 构造一个带上"当前"Token 的请求。
    ///
    /// 不用 DefaultRequestHeaders 的原因见构造函数注释：Token 会在配对成功后被替换，
    /// 而默认头是一个"设一次就固定"的容器。这里每次现取，保证配对后下一个请求就生效。
    /// </summary>
    private HttpRequestMessage NewRequest(HttpMethod method, string url)
    {
        var req = new HttpRequestMessage(method, url);
        var token = _settings.Token;
        if (!string.IsNullOrEmpty(token))
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return req;
    }

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
            // 配对请求本身不需要 Token（服务端 /api/pair 是匿名接口）。
            // 但这里仍走同一套构造方式，保持"所有 HTTP 出口只有一处"的一致。
            using var request = new HttpRequestMessage(HttpMethod.Post, $"{BaseUrl}/api/pair")
            {
                Content = content,
            };
            using var resp = await _http.SendAsync(request);

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

            // 现取 Token（配对后立刻生效），见 NewRequest 的说明
            using var request = NewRequest(HttpMethod.Post, $"{BaseUrl}/api/upload");
            request.Content = form;

            using var resp = await _http.SendAsync(request);
            if (!resp.IsSuccessStatusCode)
            {
                var err = await resp.Content.ReadAsStringAsync();
                StatusMessage?.Invoke(resp.StatusCode == System.Net.HttpStatusCode.Unauthorized
                    ? "上传失败：Token 无效或已过期，请重新配对"
                    : $"上传失败：HTTP {(int)resp.StatusCode} {Trim(err)}");
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
            // 下载地址指向 /f/，服务端不校验 Token；这里仍带上（若已配对），
            // 便于将来给静态资源加签名后无需再改客户端。
            using var request = NewRequest(HttpMethod.Get, url);
            using var resp = await _http.SendAsync(request);
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
        // _runTask 必须在退出时清空，否则 Stop() 之后再 Start() 会变成 no-op：
        // 旧实现只判断 `_runTask is not null`，而 Stop() 从不清它 ——
        // 「重新连接」菜单点第二次就没反应了。
        if (_runTask is { IsCompleted: false }) return;

        _runTask = null;
        _cts?.Dispose();
        _cts = new CancellationTokenSource();
        var ct = _cts.Token;

        // 手动重启时把退避计数归零，让"用户主动重连"立刻生效而不是等 60 秒
        _reconnectAttempt = 0;
        _runTask = Task.Run(() => RunLoopAsync(ct), ct);
    }

    public void Stop()
    {
        try { _cts?.Cancel(); } catch { }
        try { _ws?.Dispose(); } catch { }
        _ws = null;

        // 连接被主动断开时，等待中的 ACK 不会再到达；全部按失败释放，
        // 否则 SendClipAsync 的调用方要白等满 10 秒超时。
        foreach (var kv in _pendingAcks)
        {
            kv.Value.TrySetResult(false);
        }
        _pendingAcks.Clear();

        IsConnected = false;
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

        // 发送自我介绍。走串行闸门，避免与紧随其后的心跳/业务消息并发发送。
        await SendSerializedAsync(ws, new Envelope
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
                    {
                        // 先把消息投给上层（真正写入剪贴板/落盘），再回 ACK。
                        //
                        // 顺序是有意的：协议第 7.2 节第 5 步要求"处理完再 ack"，
                        // ACK 的语义是"我已落地"而不是"我已收到字节"。
                        // 这样服务端日志里的 ACK 才是可信的投递证据。
                        ClipReceived?.Invoke(payload);
                        AckReceivedClip(root, payload);
                    }
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

    /// <summary>
    /// 对收到并已交给上层处理的 clip 消息回一条 ACK。
    ///
    /// 协议第 7.2 节要求客户端确认收到（服务端 onWSSMessage 会记 debug 日志）。
    /// 这条 ACK 是"服务端已广播"与"对端已落地"之间唯一由客户端提供的证据，
    /// 排障时价值很高。
    ///
    /// 用 fire-and-forget：ACK 发不出去不影响内容本身已经落地，
    /// 而且这里跑在接收循环里，同步等待会阻塞后续消息。
    /// </summary>
    private void AckReceivedClip(JsonElement root, ClipPayload payload)
    {
        var ws = _ws?.Inner;
        if (ws is null || ws.State != System.Net.WebSockets.WebSocketState.Open) return;

        var ackMsgId = root.TryGetProperty("msgId", out var m) ? m.GetString() : null;
        if (string.IsNullOrEmpty(ackMsgId)) return;

        var env = new Envelope
        {
            Type = MsgType.Ack,
            MsgId = Guid.NewGuid().ToString(),
            Ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Payload = new AckPayload { MsgId = ackMsgId, Status = "ok" },
        };

        _ = Task.Run(async () =>
        {
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                await SendSerializedAsync(ws, env, cts.Token);
            }
            catch
            {
                // ACK 失败无所谓，下一条消息还会带新的 ACK
            }
        });
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
                    await SendSerializedAsync(ws, new Envelope
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
            await SendSerializedAsync(ws, env, CancellationToken.None);

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

    /// <summary>
    /// 串行化的发送。所有对外发送都必须走这里，不能直接调 SendAsync。
    /// 为什么必须：ClientWebSocket 不支持并发 SendAsync，见 _sendGate 的说明。
    /// </summary>
    private async Task SendSerializedAsync(System.Net.WebSockets.ClientWebSocket ws, Envelope env, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await SendAsync(ws, env, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
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
