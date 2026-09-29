package com.clipbridge.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

        /** 选中内容的有效期。超过这个时间的选中不再视为"刚刚复制的内容"。
         *
         *  取 6 秒是个折中：剪贴板变更现在也允许回退（为了兼容微信这类不暴露
         *  "复制"节点的 App），窗口越长，"选中了文字但随后复制的是别的东西"
         *  被误判的概率就越高。6 秒足够覆盖"选中 → 点复制"的正常操作节奏。 */
        private const val SELECTION_TTL_MS = 6_000L

        /** 中文语境下"复制"类菜单项的字样，包含匹配即可（如"复制链接"） */
        private val CJK_COPY_LABELS = listOf("复制", "拷贝", "剪切")

        /** 英文语境用精确匹配，避免 "cut" 命中 "execute" 之类 */
        private val EN_COPY_LABELS = setOf(
            "copy", "cut", "copy text", "cut text", "copy all", "copy link"
        )

        /**
         * 运行中的服务实例，仅供 UI 层转发"配置变了"这一次通知。
         *
         * 为什么不靠重启服务来生效：无障碍服务由系统管理，App 没有 API
         * 可以主动重启它。持有一个静态引用是最轻的做法 —— 只用来触发一次
         * 状态刷新，不承载任何业务数据，且在 onDestroy 里清空。
         */
        @Volatile
        private var instance: ClipboardAccessibilityService? = null

        /** 用户在 App 里改了配置（例如截图开关）后调用 */
        fun notifyPrefsChanged() {
            instance?.applyScreenshotPref()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /**
     * 待执行的那次检查是否允许回退到"选中内容"。
     * 只增不减，直到该次检查真正执行 —— 原因见 scheduleCheck 的注释。
     */
    private var pendingAllowFallback = false
    private var attached = false

    /** 截图上行监听（仅在用户开启该功能时创建） */
    private var screenshotWatcher: ScreenshotWatcher? = null

    /** 最近一次文本选中的内容、时间戳，以及当时所在的应用包名 */
    private var lastSelection: String? = null
    private var lastSelectionAt = 0L
    private var lastSelectionPkg: String? = null

    /**
     * 剪贴板变更通知 —— 上行链路里**最可靠**的触发信号。
     *
     * 它由系统直接投递，不受无障碍事件 `notificationTimeout` 的节流，也不依赖
     * 目标 App 是否暴露了可点击的"复制"节点。微信/QQ 这类自绘复制菜单的 App
     * 基本只能靠这条路径。
     *
     * 但"触发可靠"不等于"内容拿得到"：Android 10+ 下后台读剪贴板正文会被系统拒绝。
     * 所以这里允许回退到刚刚记下的选中内容。是否可信由两道守卫决定：
     *   ① 选中在 SELECTION_TTL_MS 之内（够新）
     *   ② 中间没有切换到别的应用（切了就作废，见 TYPE_WINDOW_STATE_CHANGED）
     */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        StatusHolder.addDiag("剪贴板变更通知")
        scheduleCheck(allowSelectionFallback = true)
    }

    // ---------- 生命周期 ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接")
        instance = this
        attach()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
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

        // 截图上行（可选功能）。放在无障碍服务里启动，好处是它的生命周期
        // 跟着服务走 —— 服务在，监听就在，不需要额外的保活手段。
        applyScreenshotPref()
    }

    /** 按当前配置启动/停止截图监听。用户改开关、或服务刚连上时都会走到这里。 */
    private fun applyScreenshotPref() {
        if (Prefs.isScreenshotSyncEnabled(this)) {
            if (screenshotWatcher == null) screenshotWatcher = ScreenshotWatcher(this)
            screenshotWatcher?.start()
        } else {
            screenshotWatcher?.stop()
            screenshotWatcher = null
        }
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
        screenshotWatcher?.stop()
        screenshotWatcher = null
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

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 切应用/切界面了：之前记下的选中内容不再可信，作废掉。
                //
                // 不这样做的话，"在 A 应用里选中一段文字 → 切到 B 应用复制别的东西"
                // 会被误判成"复制了 A 里那段文字"，把用户根本没复制的内容发出去 ——
                // 这违反"宁可漏同步也不伪造内容"的底线。
                //
                // 比的是**包名**而不是"有窗口变化就清"：选中文字后弹出的复制工具条
                // 本身也可能是个新窗口，一律清会把正常流程掐断。
                // 如果某天诊断里出现"切换应用，作废之前的选中内容"紧挨着复制动作，
                // 说明这个判断在对应 ROM 上过于激进，需要放宽。
                val pkg = e.packageName?.toString()
                if (lastSelection != null && pkg != null && pkg != lastSelectionPkg) {
                    StatusHolder.addDiag("切换应用（${shortPkg(pkg)}），作废之前的选中内容")
                    lastSelection = null
                    lastSelectionPkg = null
                }
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
        lastSelectionPkg = e.packageName?.toString()
        StatusHolder.addDiag("记下选中：${lastSelection?.length} 字（${shortPkg(lastSelectionPkg)}）")
        Log.d(TAG, "记下选中内容（${lastSelection?.length} 字）")
    }

    /** 判断这次点击是不是"复制/剪切"。
     *
     *  这只是**补充**触发，不是主路径 —— 主路径是剪贴板变更通知（对所有 App 通用）。
     *  这里保留它是因为：多一个触发点，就多一次"抢在内容仍可读时"取到内容的机会。
     *  匹配不上也不影响同步，所以不必为某个 App 的文案特调。
     */
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

        // 第二路：读不到剪贴板时，用"选中内容"作为本次复制的内容。
        //
        // 内容来源不挑 App，按可靠性依次尝试：
        //   ① 事件里记下的选中文本（任何 App 的文本节点只要上报选中区间就会有）
        //   ② 现场扫一遍当前窗口，找带选中区间的文本节点
        // ② 存在的意义：①依赖"我们恰好收到了那次选中事件"，而事件可能被节流、
        // 或节点是后建的。与其赌事件被捕获，不如在真正需要内容的那一刻直接去窗口里找 ——
        // 这对所有 App 一视同仁，不依赖任何厂商或应用的私有实现。
        if (!allowSelectionFallback) {
            StatusHolder.addDiag("剪贴板读不到（系统限制），本次不允许回退")
            return
        }

        var sel = lastSelection
        var how = "记录的选中"
        var fresh = sel != null && System.currentTimeMillis() - lastSelectionAt <= SELECTION_TTL_MS
        if (sel.isNullOrEmpty() || !fresh) {
            val scanned = selectionFromActiveWindow()
            if (!scanned.isNullOrEmpty()) {
                sel = scanned
                how = "当前窗口的选中"
                fresh = true // 现场读到的，天然是"此刻"的
            }
        }

        if (sel.isNullOrEmpty()) {
            StatusHolder.addDiag("剪贴板读不到，当前窗口也没有选中文本")
            return
        }
        if (!fresh) {
            val ageMs = System.currentTimeMillis() - lastSelectionAt
            StatusHolder.addDiag("剪贴板读不到，选中内容已过期（${ageMs / 1000}s）")
            return
        }
        StatusHolder.addDiag("回退用$how：${sel.length} 字")
        submitIfNew(sel)
    }

    /**
     * 在当前活动窗口里找"带选中区间的文本节点"，返回其中的选中部分。
     *
     * 通用性说明：走的是无障碍标准接口（`textSelectionStart/End`），
     * 任何把文本交给系统无障碍框架的控件都适用，与具体 App 无关。
     * 深度上限用于防止异常深的视图树把主线程拖死。
     */
    private fun selectionFromActiveWindow(): String? {
        return try {
            findSelectedText(rootInActiveWindow, 0)
        } catch (e: Exception) {
            Log.w(TAG, "扫描当前窗口失败", e)
            null
        }
    }

    private fun findSelectedText(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 12) return null
        try {
            val t = node.text?.toString()
            if (!t.isNullOrEmpty()) {
                val s = node.textSelectionStart
                val e = node.textSelectionEnd
                if (s in 0 until e && e <= t.length) {
                    val sel = t.substring(s, e)
                    if (sel.isNotEmpty()) return sel
                }
            }
            for (i in 0 until node.childCount) {
                val found = findSelectedText(node.getChild(i), depth + 1)
                if (found != null) return found
            }
        } catch (_: Exception) {
        }
        return null
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
