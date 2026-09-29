package com.clipbridge.app.receiver

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clipbridge.app.data.ClipOrigin
import com.clipbridge.app.data.Hashing
import com.clipbridge.app.data.Prefs
import com.clipbridge.app.service.ClipClient
import com.clipbridge.app.service.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统分享菜单入口（ACTION_SEND）。
 *
 * 它是无障碍自动上行之外的兜底通道：零权限、100% 可靠。
 * 用户在任意 App 里「分享 → ClipBridge」即可把文本/图片同步出去。
 *
 * 分享是用户明确的一次操作，所以这里主动申请保持连接，
 * 并等发送真正完成后再释放 —— 否则 Activity 一关，进程可能就被回收了。
 */
class ShareReceiverActivity : AppCompatActivity() {

    private companion object {
        const val HOLDER = "share"

        /** 等待 WebSocket 建连的上限 */
        const val CONNECT_TIMEOUT_MS = 8_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acquired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.hasToken(this)) {
            toast("ClipBridge 尚未配对，请先打开 App 完成配对")
            finish()
            return
        }

        SyncEngine.acquire(applicationContext, HOLDER)
        acquired = true

        when (intent?.action) {
            Intent.ACTION_SEND -> scope.launch { handleSend(intent) }
            Intent.ACTION_SEND_MULTIPLE -> scope.launch { handleMultiple(intent) }
            else -> done("没有可同步的内容")
        }
    }

    override fun onDestroy() {
        releaseHolder()
        super.onDestroy()
    }

    // ---------- 处理 ----------

    private suspend fun handleSend(intent: Intent?) {
        if (intent == null) {
            done("没有可同步的内容")
            return
        }
        when (val type = intent.type) {
            null -> done("无法识别的内容类型")

            "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text.isNullOrEmpty()) {
                    done("没有可同步的文本")
                    return
                }
                if (!awaitConnection()) {
                    done("无法连接服务端，请检查网络")
                    return
                }
                val ok = ClipClient.sendText(text, ClipOrigin.SHARE)
                done(if (ok) "已同步文本" else "同步失败")
            }

            else -> {
                if (!type.startsWith("image/")) {
                    done("暂不支持该类型：$type")
                    return
                }
                val uri = getStreamUri(intent)
                if (uri == null) {
                    done("读取不到图片")
                    return
                }
                val bytes = runCatching {
                    contentResolver.openInputStream(uri)?.readBytes()
                }.getOrNull()
                if (bytes == null) {
                    done("读取图片失败")
                    return
                }
                if (!awaitConnection()) {
                    done("无法连接服务端，请检查网络")
                    return
                }
                val resp = ClipClient.upload(bytes)
                if (resp == null) {
                    done("图片上传失败")
                    return
                }
                val ok = ClipClient.sendImage(
                    resp.optString("fileId"),
                    resp.optString("url"),
                    resp.optString("mime"),
                    resp.optLong("size"),
                    Hashing.sha256(bytes),
                    ClipOrigin.SHARE
                )
                done(if (ok) "已同步图片" else "图片同步失败")
            }
        }
    }

    private suspend fun handleMultiple(intent: Intent?) {
        // 多图分享只取第一张，够用且实现简单
        val clip = intent?.clipData
        val item = clip?.getItemAt(0)
        if (item == null) {
            done("没有可同步的内容")
            return
        }
        val single = Intent(Intent.ACTION_SEND).apply {
            type = intent.type
            putExtra(Intent.EXTRA_STREAM, item.uri)
        }
        handleSend(single)
    }

    /** 等待长连接就绪。刚 acquire 时 WebSocket 还没建立，立刻发会丢。 */
    private suspend fun awaitConnection(): Boolean {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ClipClient.connected) return true
            kotlinx.coroutines.delay(120)
        }
        return ClipClient.connected
    }

    // ---------- 收尾 ----------

    private fun done(message: String) {
        releaseHolder()
        runOnUiThread {
            toast(message)
            finish()
        }
    }

    private fun releaseHolder() {
        if (!acquired) return
        acquired = false
        SyncEngine.release(HOLDER)
    }

    @Suppress("DEPRECATION")
    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
