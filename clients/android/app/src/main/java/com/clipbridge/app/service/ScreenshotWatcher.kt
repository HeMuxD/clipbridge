package com.clipbridge.app.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.clipbridge.app.data.ClipOrigin
import com.clipbridge.app.data.HashCache
import com.clipbridge.app.data.Hashing
import com.clipbridge.app.data.StatusHolder

/**
 * 截图上行：监听相册里**新产生**的截图，上传后作为 image 内容同步出去。
 *
 * ## 为什么不能像 Windows 端那样监控目录
 *
 * Android 10 起的分区存储下，直接遍历 `/sdcard` 已经读不到别的应用写入的文件，
 * MediaStore 是唯一受支持的入口。好处是除媒体读取权限外不需要任何额外权限。
 *
 * ## 关于权限
 *
 * Android 13+ 需要 `READ_MEDIA_IMAGES`，更早需要 `READ_EXTERNAL_STORAGE`。
 * 这是个"读你所有照片"级别的权限，所以默认关闭、由用户在 App 里显式开启。
 * 没有权限时不注册监听，并在诊断里写明 —— 否则表现只是"开了却不生效"。
 *
 * ## 只同步"当前这一次"
 *
 * 记录 `start()` 的时刻，比它更早的文件一律忽略。用户在开启这个功能之前
 * 拍过的截图不会被补发 —— 与"只同步当前这一次操作"的整体约定一致。
 */
class ScreenshotWatcher(private val ctx: Context) {

    companion object {
        private const val TAG = "ClipBridgeShot"

        /**
         * 截图在文件名或所在目录里会出现的字样。
         * 厂商差异极大（Screenshot_2026…、截屏_…、Screenshots/…），逐一列举。
         */
        private val KEYWORDS = listOf(
            "screenshot", "screen_shot", "screen-shot",
            "截屏", "截图", "屏幕截图",
        )

        /** 需要申请的权限名。Android 13 起按媒体类型细分。 */
        fun requiredPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasPermission(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, requiredPermission()) ==
                PackageManager.PERMISSION_GRANTED
    }

    private var thread: HandlerThread? = null
    private var observer: ContentObserver? = null
    private var startedAtMs = 0L

    fun isRunning(): Boolean = observer != null

    /** 注册监听。没权限时直接返回 false，由调用方提示用户。 */
    fun start(): Boolean {
        if (observer != null) return true
        if (!hasPermission(ctx)) {
            StatusHolder.addDiag("截图监听未启动：缺少${requiredPermission().substringAfterLast('.')} 权限")
            return false
        }

        val t = HandlerThread("ClipBridge.Shots").also { it.start() }
        thread = t
        startedAtMs = System.currentTimeMillis()

        val h = Handler(t.looper)
        val obs = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val target = uri ?: return
                // 插入通知可能早于文件真正落盘，等一会儿再读，否则会拿到 0 字节
                h.postDelayed({ handle(target) }, 800L)
            }
        }
        ctx.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs
        )
        observer = obs
        StatusHolder.addDiag("✅ 截图监听已启动（只同步此后新拍的截图）")
        return true
    }

    fun stop() {
        observer?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
        observer = null
        thread?.quitSafely()
        thread = null
        StatusHolder.addDiag("截图监听已停止")
    }

    // ---------- 单个新图片 ----------

    private fun handle(uri: Uri) {
        try {
            val info = queryInfo(uri) ?: return
            if (!info.looksLikeScreenshot) return
            // 只认比监听启动更晚的文件，避免把相册里的历史截图补发出去
            if (info.addedAtMs < startedAtMs) return

            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                StatusHolder.addDiag("截图读取失败或为空：${info.name}")
                return
            }

            val hash = Hashing.sha256(bytes)
            if (HashCache.contains(hash)) {
                StatusHolder.addDiag("截图跳过（与刚同步过的相同）：${info.name}")
                return
            }
            HashCache.add(hash)

            StatusHolder.addDiag("发现新截图：${info.name}（${bytes.size / 1024} KB）")

            if (!ClipClient.connected) {
                StatusHolder.addDiag("❌ 截图未上传：未连接")
                return
            }
            val resp = ClipClient.upload(bytes)
            if (resp == null) {
                StatusHolder.addDiag("❌ 截图上传失败")
                return
            }
            val fileId = resp.optString("fileId")
            val url = resp.optString("url")
            if (fileId.isEmpty() || url.isEmpty()) {
                StatusHolder.addDiag("❌ 上传响应缺少 fileId/url")
                return
            }

            val ok = ClipClient.sendImage(
                fileId = fileId,
                url = url,
                mime = info.mime.ifEmpty { "image/png" },
                size = bytes.size.toLong(),
                hash = hash,
                origin = ClipOrigin.SCREENSHOT,
            )
            StatusHolder.addDiag(if (ok) "✅ 已上行截图：${info.name}" else "❌ 截图上报失败（未连接）")
            if (ok) {
                StatusHolder.statusText.value = "已同步本机截图"
            }
        } catch (e: Exception) {
            StatusHolder.addDiag("截图处理异常：${e.message}")
            Log.w(TAG, "处理截图失败", e)
        }
    }

    private data class Info(
        val name: String,
        val hint: String,
        val mime: String,
        val addedAtMs: Long,
    ) {
        /** 文件名或所在目录里出现截图关键字即认为是截图 */
        val looksLikeScreenshot: Boolean
            get() {
                val hay = (name + " " + hint).lowercase()
                return KEYWORDS.any { hay.contains(it) }
            }
    }

    private fun queryInfo(uri: Uri): Info? {
        // RELATIVE_PATH 是 API 29+ 的字段；更早的版本只有已废弃的 DATA
        val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            pathCol,
        )

        ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: return null
            val addedAtMs = (c.getLong(1) ?: 0L) * 1000L
            val mime = c.getString(2) ?: ""
            val hint = c.getString(3) ?: ""
            return Info(name = name, hint = hint, mime = mime, addedAtMs = addedAtMs)
        }
        return null
    }

    /** 便于将来需要按 id 取 URI（当前用不到，保留给"截图去重"之类扩展） */
    @Suppress("unused")
    private fun uriOf(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}
