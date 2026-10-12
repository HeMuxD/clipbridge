package com.clipbridge.app.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.clipbridge.app.data.HashCache
import com.clipbridge.app.data.Hashing
import com.clipbridge.app.data.ImageFingerprint
import com.clipbridge.app.data.Prefs
import com.clipbridge.app.data.StatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同步引擎：持有与服务端的长连接，处理心跳、断线重连与下行内容。
 *
 * 为什么单独抽出来：连接的"存活依据"可能来自两个地方 ——
 *   1. 无障碍服务（默认，无任何通知，真正的无感）
 *   2. 可选的前台服务（部分国产 ROM 会定时掐掉后台进程，需要一个前台身份）
 * 用引用计数管理：谁在，连接就在；都退出才断开。
 */
object SyncEngine {

    private const val TAG = "SyncEngine"
    private const val PING_INTERVAL_MS = 30_000L
    private const val MAX_BACKOFF_SECONDS = 60

    private val refCount = AtomicInteger(0)
    private val lock = Any()

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 被谁持有（仅用于日志排查） */
    private val holders = mutableSetOf<String>()

    // ---------- 生命周期 ----------

    /**
     * 申请保持连接。可重复调用（幂等向上计数）。
     * @param holder 持有者标识，如 "accessibility" / "keepalive-service" / "share"
     */
    fun acquire(ctx: Context, holder: String) {
        val context = ctx.applicationContext
        synchronized(lock) {
            appContext = context
            holders.add(holder)
            if (refCount.incrementAndGet() > 1) return
        }
        Log.i(TAG, "建立连接（持有者：$holder）")
        setScope()
        doConnect()
    }

    /** 释放持有。计数归零才真正断开。 */
    fun release(holder: String) {
        synchronized(lock) {
            holders.remove(holder)
            if (refCount.get() == 0) return
            if (refCount.decrementAndGet() > 0) return
        }
        Log.i(TAG, "断开连接（已无持有者）")
        teardown()
    }

    /** 配置变化（重新配对）后重新连接 */
    fun restart() {
        synchronized(lock) {
            if (refCount.get() == 0) return
        }
        teardown()
        setScope()
        doConnect()
    }

    /**
     * 无条件彻底断开（取消配对时用）。
     *
     * 为什么不复用 [release]：引用计数回答的是"还有没有人需要连接"，
     * 而取消配对是"连接本身不再成立"—— 此时无障碍服务仍然开着、仍然持有引用，
     * 它会觉得自己该继续连着，逐个 release 根本断不掉。
     * 所以这里直接把计数清零并把持有者集合清空，语义是"从现在起谁都不再持有"。
     *
     * 之后若用户重新配对，[acquire] 会从 0 涨到 1 并正常建连。
     */
    fun disconnectAll() {
        synchronized(lock) {
            holders.clear()
            refCount.set(0)
        }
        Log.i(TAG, "取消配对：强制断开全部连接")
        teardown()
        StatusHolder.statusText.value = "尚未配对"
    }

    /**
     * 看门狗用：持有者还在，但连接确实断了，就主动补一次。
     *
     * 为什么需要它：正常断线由 [scheduleReconnect] 的指数退避负责，但那个协程
     * 跑在进程内 —— 进程被系统冻结/回收时它一并消失，退避链就断了。
     * 此时引用计数仍大于 0（服务还"在"），`acquire` 不会重新触发连接，
     * 于是表现为"服务活着，但一直连不上"。这里补上这一环。
     *
     * 已经有退避任务在跑时不插手，避免和正常重连打架。
     */
    fun ensureConnected() {
        synchronized(lock) {
            if (refCount.get() == 0) return
            if (reconnectJob?.isActive == true) return
        }
        if (ClipClient.connected) return

        Log.i(TAG, "看门狗补连：当前未连接且无重连任务，主动重连一次")
        StatusHolder.addDiag("看门狗补连：主动重连一次")
        if (scope == null) setScope()
        attempt = 0
        doConnect()
    }

    private fun setScope() {
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    private fun teardown() {
        synchronized(lock) {
            pingJob?.cancel(); pingJob = null
            reconnectJob?.cancel(); reconnectJob = null
        }
        scope?.cancel()
        scope = null
        ClipClient.listener = null
        ClipClient.disconnect()
        StatusHolder.connected.value = false
        StatusHolder.statusText.value = "未连接"
    }

    // ---------- 连接 ----------

    private fun doConnect() {
        val ctx = appContext ?: return
        val url = Prefs.getServerUrl(ctx)
        val token = Prefs.getToken(ctx)

        if (url.isEmpty() || token.isEmpty()) {
            StatusHolder.connected.value = false
            StatusHolder.statusText.value = "尚未配对"
            Log.i(TAG, "未配对，跳过连接")
            return
        }

        ClipClient.listener = listener
        ClipClient.connect(url, token)
        startPing()
    }

    private fun startPing() {
        val s = scope ?: return
        synchronized(lock) {
            pingJob?.cancel()
            pingJob = s.launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    ClipClient.sendPing()
                }
            }
        }
    }

    /** 指数退避重连：1s、2s、4s… 上限 60s，带 ±20% 抖动避免多设备同时冲击服务端 */
    private fun scheduleReconnect() {
        val s = scope ?: return
        synchronized(lock) {
            if (reconnectJob?.isActive == true) return
            reconnectJob = s.launch {
                val seconds = (1 shl minOf(attempt, 6)).coerceAtMost(MAX_BACKOFF_SECONDS)
                val jitter = (Math.random() * 0.4) - 0.2
                val waitMs = ((seconds * 1000L) * (1 + jitter)).toLong().coerceAtLeast(1000L)

                attempt++
                Log.i(TAG, "${waitMs / 1000}s 后重连（第 $attempt 次）")
                delay(waitMs)

                if (isActive && refCount.get() > 0) {
                    reconnectJob = null
                    doConnect()
                }
            }
        }
    }

    // ---------- 下行 ----------

    private val listener = object : ClipClient.Listener {
        override fun onConnected() {
            attempt = 0
            StatusHolder.connected.value = true
            StatusHolder.statusText.value = "已连接"
            Log.i(TAG, "已连接服务端")
        }

        override fun onDisconnected() {
            StatusHolder.connected.value = false
            StatusHolder.statusText.value = "未连接"
            scheduleReconnect()
        }

        override fun onText(srcName: String, text: String) {
            val ctx = appContext ?: return
            // 关键顺序：先把哈希登记好，再写剪贴板。
            // 否则写入动作会立刻被无障碍监听看到，形成 A→B→A 回环。
            HashCache.add(Hashing.sha256(text))
            val ok = ClipboardWriter.writeText(ctx, text)
            StatusHolder.statusText.value =
                if (ok) "已收到「$srcName」的文本" else "写入剪贴板失败"
            Log.i(TAG, "已静默写入远端文本（来自 $srcName），成功=$ok")
        }

        override fun onImage(srcName: String, url: String) {
            appContext?.let { ctx ->
                scope?.launch { downloadAndDeliver(ctx, srcName, url) }
            }
        }

        override fun onError(code: Int, message: String) {
            Log.w(TAG, "服务端错误 $code：$message")
        }
    }

    /**
     * 图片下行：下载 → 存相册（可靠，用户能在相册里直接看到）
     * → 同时尝试写进剪贴板（能直接粘贴到支持的 App）。
     * 全程静默，不弹任何通知。
     */
    private fun downloadAndDeliver(ctx: Context, srcName: String, url: String) {
        try {
            val bytes = download(url) ?: run {
                StatusHolder.statusText.value = "图片下载失败"
                return
            }

            // 同一份内容刚同步过就跳过。上游把同一条投递两次时（同一张图被广播两遍），
            // 不挡的话三端都会留下重复的一份 —— 这里同时兼顾"回环"和"真重复"。
            val hash = Hashing.sha256(bytes)
            if (HashCache.contains(hash)) {
                StatusHolder.statusText.value = "跳过重复收到的图片"
                Log.i(TAG, "图片重复，已跳过")
                return
            }
            HashCache.add(hash)

            // 登记像素指纹：等会儿小米云同步把「别人截的这张图」压缩后推到本机相册时，
            // 截图监听要靠它识别出来并跳过，否则会形成回环（见 ImageFingerprint 说明）。
            ImageFingerprint.dHash(bytes)?.let { HashCache.add("fp:$it") }

            val uri = saveImage(ctx, bytes)
            if (uri == null) {
                StatusHolder.statusText.value = "图片保存失败"
                return
            }

            val inClipboard = ClipboardWriter.writeImage(ctx, uri)
            StatusHolder.statusText.value =
                if (inClipboard) "已收到「$srcName」的截图（已存相册）"
                else "已收到「$srcName」的截图（已存相册，剪贴板写入被系统拒绝）"
            Log.i(TAG, "图片已交付：相册=OK 剪贴板=$inClipboard")
        } catch (e: Exception) {
            Log.w(TAG, "图片下行失败", e)
        }
    }

    private fun download(url: String): ByteArray? {
        val req = Request.Builder().url(url).build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * 存进相册 Pictures/ClipBridge，返回可用于剪贴板的 content:// URI。
     *
     * Android 10+ 走 MediaStore，不需要任何存储权限；
     * Android 9 及以下退回应用私有目录 + FileProvider，同样不需要权限。
     */
    private fun saveImage(ctx: Context, bytes: ByteArray): Uri? {
        // 按真实内容判定格式，而不是写死 png。
        // 用 image/png 的声明去存 JPEG 字节，会触发部分 ROM（小米等）的媒体库
        // 重新编码 / 重新归类 —— 字节一变，"按字节 hash 去重"就失效了，
        // 而且截图监听可能把落进相册的图当成一张"新截图"再上行，形成回环
        // （三端各出现两份内容相同、大小不同的图）。
        val (ext, mime) = sniffImage(bytes)
        val name = "clipbridge_${System.currentTimeMillis()}.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClipBridge")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return null

                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                Log.w(TAG, "写入相册失败", e)
                null
            }
        }

        // Android 9 及以下：写应用私有目录，再用 FileProvider 暴露成 content:// URI。
        // 这样做的好处是任何版本都不需要申请存储权限。
        return try {
            val dir = File(ctx.filesDir, "shared").apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.w(TAG, "写入图片文件失败", e)
            null
        }
    }

    /** 从字节魔数判断图片的真实扩展名与 MIME 类型 */
    private fun sniffImage(b: ByteArray): Pair<String, String> = when {
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() ->
            "jpg" to "image/jpeg"
        b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() ->
            "png" to "image/png"
        b.size >= 6 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() ->
            "gif" to "image/gif"
        b.size >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() &&
            b[3] == 0x46.toByte() && b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
            b[10] == 0x42.toByte() && b[11] == 0x50.toByte() ->
            "webp" to "image/webp"
        else -> "png" to "image/png"
    }
}
