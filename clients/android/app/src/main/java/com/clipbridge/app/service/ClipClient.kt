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
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile var ws: WebSocket? = null
    @Volatile var connected = false

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
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        connected = false
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

    fun sendPing() {
        val env = JSONObject().apply {
            put("type", MsgType.PING)
            put("msgId", UUID.randomUUID().toString())
            put("ts", System.currentTimeMillis())
        }
        ws?.send(env.toString())
    }

    // ---------- WebSocket 回调 ----------

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            StatusHolder.connected.value = true
            listener?.onConnected()
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            StatusHolder.connected.value = false
            listener?.onDisconnected()
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
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
