package com.clipbridge.app.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.clipbridge.app.data.HashCache
import com.clipbridge.app.data.Hashing

/**
 * 剪贴板写入。
 *
 * 要点：**后台写入剪贴板是允许的**，受限的只是"后台读取"。
 * AOSP 的 ClipboardService.clipboardAccessAllowed 里，读操作会校验焦点，
 * 而写操作直接 `allowed = true`。所以"收到即静默进剪贴板"这条路是通的。
 */
object ClipboardWriter {

    /** 写入文本 */
    fun writeText(ctx: Context, text: String): Boolean {
        return try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("ClipBridge", text))
            true
        } catch (e: Exception) {
            Log.w("ClipboardWriter", "写入文本失败", e)
            false
        }
    }

    /**
     * 写入图片。
     *
     * 用 content:// URI 而不是位图本身：跨进程传递大 Bitmap 会撑爆 Binder，
     * 而且多数 App 粘贴图片时也只认 URI。
     * 部分系统对"从剪贴板读取 URI"有额外限制，所以调用方还应把图片同时存进相册兜底。
     */
    fun writeImage(ctx: Context, uri: android.net.Uri): Boolean {
        return try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
            cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "ClipBridge", uri))
            true
        } catch (e: Exception) {
            Log.w("ClipboardWriter", "写入图片失败", e)
            false
        }
    }

    /**
     * 登记"当前剪贴板里已有的内容"，使其不会被当成一次新的复制操作。
     * 读取可能被系统拒绝（后台读取限制），失败时静默忽略。
     */
    fun registerExisting(ctx: Context) {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return
            if (text.isEmpty()) return
            HashCache.add(Hashing.sha256(text))
            Log.i("ClipboardWriter", "剪贴板中已有内容，已登记为历史内容，不会同步")
        } catch (e: Exception) {
            Log.d("ClipboardWriter", "登记既有剪贴板内容失败（通常是无后台读取权限）: ${e.message}")
        }
    }
}
