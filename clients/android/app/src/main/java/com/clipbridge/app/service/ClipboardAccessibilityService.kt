package com.clipbridge.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.clipbridge.app.data.ClipOrigin
import com.clipbridge.app.data.HashCache
import com.clipbridge.app.data.Hashing
import com.clipbridge.app.data.Prefs
import com.clipbridge.app.data.StatusHolder

/**
 * 无障碍服务：负责**上行**（感知用户复制了什么）并同时充当同步连接的保活载体。
 *
 * ## 为什么不能只挂一个剪贴板监听
 *
 * Android 10 起，后台应用**无法读取剪贴板**。AOSP 的
 * `ClipboardService.clipboardAccessAllowed` 里，读操作只对这些身份放行：
 * 当前拥有焦点的应用、默认输入法、SystemUI、ContentCapture 服务、Autofill 服务。
 * **无障碍服务并不在豁免名单里**，而且没有任何权限可以申请。
 *
 * 所以这里用两路互补的策略：
 *
 * 1. **直接读系统剪贴板**：注册 `OnPrimaryClipChangedListener` 并主动读取。
 *    在部分 ROM、部分时机（例如系统未严格执行焦点校验）能读到，读到就直接用。
 * 2. **从无障碍事件里还原**：监听文本选中与"复制/剪切"菜单点击。
 *    用户选中文本时记下选中区间的内容，随后一旦检测到复制动作，
 *    就用这份内容上行。这一路不依赖剪贴板读取权限。
 *
 * 两路都拿不到时不会伪造内容 —— 宁可漏同步，也不能把用户没复制的东西发出去。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipBridgeA11y"
        private const val HOLDER = "accessibility"

        /** 事件去抖：复制动作往往连着好几个事件 */
        private const val DEBOUNCE_MS = 120L

        /** 选中内容的有效期。超过这个时间的选中不再视为"刚刚复制的内容" */
        private const val SELECTION_TTL_MS = 10_000L

        /** 中文语境下"复制"类菜单项的字样，包含匹配即可（如"复制链接"） */
        private val CJK_COPY_LABELS = listOf("复制", "拷贝", "剪切")

        /** 英文语境用精确匹配，避免 "cut" 命中 "execute" 之类 */
        private val EN_COPY_LABELS = setOf(
            "copy", "cut", "copy text", "cut text", "copy all", "copy link"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /**
     * 待执行的那次检查是否允许回退到"选中内容"。
     * 只增不减，直到该次检查真正执行 —— 原因见 scheduleCheck 的注释。
     */
    private var pendingAllowFallback = false
    private var attached = false

    /** 最近一次文本选中的内容与时间戳 */
    private var lastSelection: String? = null
    private var lastSelectionAt = 0L

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        scheduleCheck(allowSelectionFallback = false)
    }

    // ---------- 生命周期 ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接")
        attach()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 系统要求实现，无需处理
    }

    private fun attach() {
        if (attached) return
        attached = true
        StatusHolder.addDiag("无障碍服务已连接")

        // 启动瞬间剪贴板里已有的内容属于"以往的复制操作"，先登记掉，
        // 这样它永远不会被当成一次新的复制上报。
        ClipboardWriter.registerExisting(this)

        try {
            getSystemService(ClipboardManager::class.java)
                ?.addPrimaryClipChangedListener(clipListener)
            StatusHolder.addDiag("已注册剪贴板变更监听")
        } catch (e: Exception) {
            StatusHolder.addDiag("注册剪贴板监听失败：${e.message}")
            Log.w(TAG, "注册剪贴板监听失败", e)
        }

        // 连接由无障碍服务持有：只要用户在系统设置里开着它，
        // 进程就会被系统保活，不需要前台服务，也就不需要任何常驻通知。
        SyncEngine.acquire(this, HOLDER)
    }

    private fun detach() {
        if (!attached) return
        attached = false

        try {
            getSystemService(ClipboardManager::class.java)
                ?.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        pendingAllowFallback = false
        SyncEngine.release(HOLDER)
    }

    // ---------- 事件 ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 自己产生的事件不用管
        if (e.packageName == packageName) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                rememberSelection(e)
                // 选中之后用户很可能紧接着点"复制"，先去剪贴板碰一次运气
                scheduleCheck(allowSelectionFallback = false)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val hit = looksLikeCopyAction(e)
                StatusHolder.addDiag(
                    "${eventName(e.eventType)} ${shortPkg(e.packageName)} " +
                        if (hit) "命中复制标签" else "未命中复制标签"
                )
                if (hit) {
                    // 刚点了复制：优先读剪贴板，读不到就用刚才记下的选中内容
                    scheduleCheck(allowSelectionFallback = true, immediate = true)
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                scheduleCheck(allowSelectionFallback = false)
            }
        }
    }

    private fun eventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "选中变化"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "点击"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "长按"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "焦点"
        else -> "type=$type"
    }

    /** 只留包名最后一段，别把整条流水撑爆 */
    private fun shortPkg(pkg: CharSequence?): String =
        pkg?.toString()?.substringAfterLast('.')?.take(14) ?: "?"

    /**
     * 记下当前选中的文本，供"点了复制但读不到剪贴板"时回退使用。
     *
     * 这一路失败的原因往往很隐蔽（比如文本由 WebView 渲染时，节点不提供
     * textSelectionStart/End），所以每个 return 都留一条诊断 —— 否则外在表现
     * 只是"什么都没同步"，完全看不出断在哪一步。
     */
    private fun rememberSelection(e: AccessibilityEvent) {
        val node = e.source
        if (node == null) {
            StatusHolder.addDiag("选中变化：拿不到节点（e.source 为空）")
            return
        }
        val text = node.text?.toString()
        if (text.isNullOrEmpty()) {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            StatusHolder.addDiag("选中变化：节点无文本（$cls）")
            return
        }

        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end <= start || end > text.length) {
            StatusHolder.addDiag("选中变化：区间无效 start=$start end=$end（文本 ${text.length} 字）")
            return
        }

        lastSelection = text.substring(start, end)
        lastSelectionAt = System.currentTimeMillis()
        StatusHolder.addDiag("记下选中：${lastSelection?.length} 字")
        Log.d(TAG, "记下选中内容（${lastSelection?.length} 字）")
    }

    /** 判断这次点击是不是"复制/剪切" */
    private fun looksLikeCopyAction(e: AccessibilityEvent): Boolean {
        for (t in e.text) {
            if (isCopyLabel(t)) return true
        }
        if (isCopyLabel(e.contentDescription)) return true

        // 有些 App 的菜单项只有图标，文案在父节点上，往上找几层
        var node = e.source
        var depth = 0
        while (node != null && depth < 4) {
            if (isCopyLabel(node.text) || isCopyLabel(node.contentDescription)) return true
            node = node.parent
            depth++
        }
        return false
    }

    private fun isCopyLabel(cs: CharSequence?): Boolean {
        val s = cs?.toString()?.trim()?.lowercase() ?: return false
        if (s.isEmpty()) return false
        if (CJK_COPY_LABELS.any { s.contains(it) }) return true
        return EN_COPY_LABELS.contains(s)
    }

    // ---------- 上行 ----------

    /**
     * 调度一次"当前剪贴板"检查。
     *
     * ⚠️ 回退能力是**粘性**的：pendingAllowFallback 只增不减，直到这次检查真正执行。
     *
     * 原因是一次复制会同时产生两个触发：
     *   ① 无障碍的「点击了复制」事件 —— allowSelectionFallback = true，
     *      但受 notificationTimeout=100 节流，到得晚；
     *   ② 剪贴板变更通知 —— allowSelectionFallback = false，是即时的，到得早。
     * 后到的 ② 会取消 ① 已经排好的调度。如果让 ② 把回退能力覆盖回 false，
     * 那么 Android 10+ 上**唯一**能拿到内容的那条路就被静默取消了，
     * 表现正好是"复制了但什么都没同步"。
     */
    private fun scheduleCheck(allowSelectionFallback: Boolean, immediate: Boolean = false) {
        pending?.let { handler.removeCallbacks(it) }
        pendingAllowFallback = pendingAllowFallback || allowSelectionFallback
        val allowFallback = pendingAllowFallback
        val r = Runnable {
            pending = null
            pendingAllowFallback = false
            checkClipboard(allowFallback)
        }
        pending = r
        handler.postDelayed(r, if (immediate) 80L else DEBOUNCE_MS)
    }

    private fun checkClipboard(allowSelectionFallback: Boolean) {
        if (Prefs.getToken(this).isEmpty()) {
            StatusHolder.addDiag("检查中止：尚未配对")
            return
        }
        if (!ClipClient.connected) {
            StatusHolder.addDiag("检查中止：未连接")
            return
        }

        // 第一路：直接读系统剪贴板
        val fromClipboard = readClipboardText()
        if (!fromClipboard.isNullOrEmpty()) {
            StatusHolder.addDiag("读到剪贴板：${fromClipboard.length} 字")
            submitIfNew(fromClipboard)
            return
        }

        // 第二路：读不到剪贴板时，退回"点复制之前记下的选中内容"
        if (!allowSelectionFallback) {
            StatusHolder.addDiag("剪贴板读不到（系统限制），本次不允许回退")
            return
        }
        val sel = lastSelection
        if (sel.isNullOrEmpty()) {
            StatusHolder.addDiag("剪贴板读不到，且没有可用的选中内容")
            return
        }
        val ageMs = System.currentTimeMillis() - lastSelectionAt
        if (ageMs > SELECTION_TTL_MS) {
            StatusHolder.addDiag("剪贴板读不到，选中内容已过期（${ageMs / 1000}s）")
            return
        }
        StatusHolder.addDiag("回退用选中内容：${sel.length} 字")
        submitIfNew(sel)
    }

    private fun readClipboardText(): String? {
        return try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // 后台读取被系统拒绝是预期行为，不当成错误
            null
        }
    }

    private fun submitIfNew(text: String) {
        val hash = Hashing.sha256(text)
        // 命中缓存说明：这是我们自己刚写进剪贴板的远端内容（回环），
        // 或者用户重复复制了同一段内容 —— 两种都不该再上报一次。
        if (HashCache.contains(hash)) {
            StatusHolder.addDiag("跳过：与刚同步过的内容相同")
            return
        }

        HashCache.add(hash)
        lastSelection = null

        val ok = ClipClient.sendText(text, ClipOrigin.CLIPBOARD)
        StatusHolder.addDiag(if (ok) "✅ 已上行：${text.length} 字" else "❌ 上行失败：未连接")
        Log.i(TAG, "上行文本：${text.length} 字，发送成功=$ok")
        StatusHolder.statusText.value =
            if (ok) "已同步本机复制的内容" else "同步失败（未连接）"
    }
}
