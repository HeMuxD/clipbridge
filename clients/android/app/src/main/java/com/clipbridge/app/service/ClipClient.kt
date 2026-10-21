package com.clipbridge.app.service

import android.util.Log
import com.clipbridge.app.data.ClipKind
import com.clipbridge.app.data.Hashing
import com.clipbridge.app.data.MsgType
import com.clipbridge.app.data.StatusHolder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 与服务端的连接管理（单例）。
 * 负责：配对、WebSocket 长连接、上行发送、下行分发。
 * JSON 用 Android 内置 org.json，避免引入额外序列化依赖。
 */
object ClipClient {
    private const val TAG = "ClipClient"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // WebSocket 不能用短读超时：应用层心跳是 30 秒一次（SyncEngine.PING_INTERVAL_MS），
        // 读超时若也设 30 秒，边界完全贴死，网络稍有抖动就会在两次心跳之间被判超时断开。
        // 这里置 0（不限），由 OkHttp 自带的 pingInterval 与系统 TCP keepalive 负责保活。
        // 普通 HTTP 请求（配对/上传/下载）由各自调用点控制，不受影响。
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile var ws: WebSocket? = null

    /**
     * 当前连接是否可用。
     *
     * 必须用 @Volatile：写它的是 OkHttp 的读/写线程（回调里），
     * 读它的是无障碍服务、截图线程、UI 线程 —— 不 volatile 会读到陈旧的 false，
     * 表现为"明明连着却说未连接，于是不上行"。
     */
    @Volatile var connected = false
        private set

    /** 供内部回调置位（外部只读，避免别处随手改坏连接状态） */
    private fun setConnected(v: Boolean) {
        connected = v
    }

    @Volatile var serverUrl = ""
    @Volatile var token = ""

    var listener: Listener? = null

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onText(srcName: String, text: String)
        fun onImage(srcName: String, url: String)
        fun onError(code: Int, message: String)
    }

    data class PairResult(val ok: Boolean, val token: String = "", val error: String = "")

    // ---------- 配对 ----------

    fun pair(serverUrl: String, pairCode: String, deviceId: String, deviceName: String): PairResult {
        return try {
            val body = JSONObject().apply {
                put("pairCode", pairCode)
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("platform", "android")
            }
            val req = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/pair")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val tok = JSONObject(respBody).optString("token")
                    if (tok.isNotEmpty()) PairResult(true, tok)
                    else PairResult(false, error = "服务端未返回 token")
                } else {
                    val msg = try { JSONObject(respBody).optString("message") } catch (_: Exception) { "" }
                    PairResult(false, error = if (msg.isNotEmpty()) msg else "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            PairResult(false, error = e.message ?: "连接失败")
        }
    }

    // ---------- 连接 ----------

    fun connect(serverUrl: String, token: String) {
        disconnect()
        this.serverUrl = serverUrl.trimEnd('/')
        this.token = token
        val wsUrl = this.serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/v1"

        val req = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $token")
            .build()
        ws = http.newWebSocket(req, wsListener)
    }

    fun disconnect() {
        val old = ws
        ws = null
        setConnected(false)
        try { old?.close(1000, "bye") } catch (_: Exception) {}
    }

    // ---------- 上行 ----------

    fun sendText(text: String, origin: String): Boolean {
        val payload = JSONObject().apply {
            put("kind", ClipKind.TEXT)
            put("hash", Hashing.sha256(text))
            put("text", text)
            put("origin", origin)
        }
        return sendEnvelope(MsgType.CLIP, payload)
    }

    fun sendImage(fileId: String, url: String, mime: String, size: Long, hash: String, origin: String): Boolean {
        val payload = JSONObject().apply {
            put("kind", ClipKind.IMAGE)
            put("hash", hash)
            put("fileId", fileId)
            put("url", url)
            put("mime", mime)
            put("size", size)
            put("origin", origin)
        }
        return sendEnvelope(MsgType.CLIP, payload)
    }

    /** 上传图片，返回 { fileId, url, mime, size, hash }，失败返回 null */
    fun upload(bytes: ByteArray): JSONObject? {
        return try {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "clip.png", bytes.toRequestBody("image/png".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("$serverUrl/api/upload")
                .header("Authorization", "Bearer $token")
                .post(multipart)
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}") else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            null
        }
    }

    private fun sendEnvelope(type: String, payload: JSONObject): Boolean {
        val ws = ws
        if (ws == null || !connected) return false
        val env = JSONObject().apply {
            put("type", type)
            put("msgId", UUID.randomUUID().toString())
            put("ts", System.currentTimeMillis())
            put("payload", payload)
        }
        return ws.send(env.toString())
    }

    /**
     * 回一条 ACK，确认收到某条 clip。
     *
     * 这是客户端唯一能提供给服务端的"已落地"证据，排障时能区分
     * "服务端没广播" 与 "广播了但客户端没处理"。
     * 发送失败无所谓，下一条消息还会带新的 ACK。
     */
    private fun ack(msgId: String) {
        if (msgId.isEmpty()) return
        val ws = ws ?: return
        if (!connected) return
        try {
            val env = JSONObject().apply {
                put("type", MsgType.ACK)
                put("msgId", UUID.randomUUID().toString())
                put("ts", System.currentTimeMillis())
                put("payload", JSONObject().apply {
                    put("msgId", msgId)
                    put("status", "ok")
                })
            }
            ws.send(env.toString())
        } catch (e: Exception) {
            Log.d(TAG, "回 ACK 失败：${e.message}")
        }
    }

    fun sendPing() {
        val env = JSONObject().apply {
            put("type", MsgType.PING)
            put("msgId", UUID.randomUUID().toString())
            put("ts", System.currentTimeMillis())
        }
        ws?.send(env.toString())
    }

    // ---------- WebSocket 回调 ----------
    //
    // ⚠️ 每个回调都必须先确认"我是不是当前那条连接"。
    //
    // 原因：wsListener 是全局单例，而 connect() 会先 disconnect() 关掉旧 socket。
    // 旧 socket 的 onClosed/onFailure 是**异步**到达的，往往晚于新连接的 onOpen。
    // 如果不做身份校验，旧 socket 的关闭回调会把刚建好的新连接置成 connected=false
    // 并触发 scheduleReconnect()，于是"刚连上就被判掉线 → 再连 → 再被旧回调打断"，
    // 形成自激重连。判据很简单：回调里的 webSocket 就是当前 ws 的那个对象才作数。

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) {
                Log.d(TAG, "忽略过期 socket 的 onOpen")
                return
            }
            setConnected(true)
            StatusHolder.connected.value = true
            listener?.onConnected()
            // 连上之后立刻报告一次活动，让读超时有东西可等
            webSocket.send("""{"type":"${MsgType.PING}","msgId":"${java.util.UUID.randomUUID()}"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            handleMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) {
                Log.d(TAG, "忽略过期 socket 的 onClosed(code=$code)")
                return
            }
            Log.i(TAG, "连接关闭：code=$code reason=$reason")
            setConnected(false)
            StatusHolder.connected.value = false
            listener?.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) {
                Log.d(TAG, "忽略过期 socket 的 onFailure：${t.message}")
                return
            }
            Log.w(TAG, "连接失败：${t.message}")
            setConnected(false)
            StatusHolder.connected.value = false
            listener?.onDisconnected()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val root = JSONObject(text)
            when (root.optString("type")) {
                MsgType.CLIP -> {
                    val p = root.optJSONObject("payload") ?: return
                    val srcName = p.optString("srcName", "远端设备")
                    when (p.optString("kind")) {
                        ClipKind.TEXT -> {
                            val t = p.optString("text")
                            if (t.isNotEmpty()) listener?.onText(srcName, t)
                        }
                        ClipKind.IMAGE -> {
                            val url = p.optString("url")
                            if (url.isNotEmpty()) listener?.onImage(srcName, url)
                        }
                    }
                    // 交给上层之后再回 ACK：协议第 7.2 节第 5 步的语义是
                    // "我已落地"，所以必须排在处理之后（文本是同步写剪贴板，
                    // 图片是异步下载，这里至少保证消息已被接收并分派）。
                    ack(root.optString("msgId"))
                }
                MsgType.PONG -> {
                    // 收到 pong 说明链路正常，刷新一下状态即可
                    StatusHolder.connected.value = true
                }
                MsgType.ERROR -> {
                    val p = root.optJSONObject("payload")
                    listener?.onError(p?.optInt("code") ?: 0, p?.optString("message") ?: "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleMessage failed", e)
        }
    }
}
