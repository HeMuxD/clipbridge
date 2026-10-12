package com.clipbridge.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条上行诊断记录。
 *
 * 带上产生时刻有两个用处：界面上能看出事情发生的先后，
 * 以及超过保留时长后可以被自动淘汰 —— 否则列表只会越攒越长。
 */
data class DiagEntry(val at: Long, val stamp: String, val line: String) {
    /** 界面上显示的一行 */
    val text: String get() = "$stamp  $line"
}

/**
 * 全局连接状态，供 UI 层（Compose）观察。
 */
object StatusHolder {
    val connected = MutableStateFlow(false)
    val statusText = MutableStateFlow("")

    /**
     * 上行诊断流水：最近若干条与"感知本次复制"相关的事件。
     *
     * 存在的理由：Android 10+ 的上行是"两路互补 + 层层回退"，
     * 任何一环不成立都表现为"什么都没发生"，且服务端只会看到"零请求"——
     * 从服务端侧完全无法区分是哪一环断了。把关键节点就地记下来，
     * 用户不用 adb、不用连电脑，在 App 里就能看到断在哪。
     *
     * ⚠️ 只记录**长度、类型、命中与否**，不记录剪贴板内容本身。
     * 唯一的例外是复制菜单项的文案（截断到 12 字）—— 排障必须知道它长什么样，
     * 否则无法判断"复制"标签匹配规则要不要放宽。这些内容只留在本机内存里，不上传。
     */
    val diag = MutableStateFlow<List<DiagEntry>>(emptyList())

    /**
     * 保留条数与时长都是**可配置**的，默认 200 条 / 2 小时。
     *
     * 为什么不写死：诊断记录是排障用的，"该留多少"取决于用户想拿它做什么 ——
     * 只想知道刚才为什么没同步，200 条足够；想回顾一整天的同步情况就得往上加。
     * 上限留死一个 10000 条的天花板，避免配置成极端值时把内存吃满。
     *
     * `@Volatile`：这两个值由 UI 线程写、由无障碍服务的线程读。
     */
    @Volatile
    private var maxEntries: Int = Prefs.DEFAULT_DIAG_MAX

    @Volatile
    private var ttlMs: Long = Prefs.DEFAULT_DIAG_TTL_MIN * 60_000L

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 由 UI 层在启动时与设置变更时调用。
     *
     * 用"推"而不是"每次 addDiag 时读 SharedPreferences"：
     * addDiag 可能被无障碍事件高频触发，读磁盘不合适。
     */
    fun applyConfig(maxEntries: Int, ttlMs: Long) {
        this.maxEntries = maxEntries.coerceIn(20, 10_000)
        this.ttlMs = ttlMs.coerceIn(60_000L, 7 * 24 * 60 * 60_000L)
        pruneDiag()
    }

    /** 当前生效的保留时长（毫秒），供界面显示 */
    fun currentTtlMs(): Long = ttlMs

    @Synchronized
    fun addDiag(line: String) {
        val now = System.currentTimeMillis()
        val stamp = timeFmt.format(Date(now))
        diag.value = (diag.value + DiagEntry(now, stamp, line))
            .filter { now - it.at <= ttlMs }
            .takeLast(maxEntries)
    }

    /**
     * 淘汰超过保留时长的记录。
     *
     * 不能只在 addDiag 里做：没有新事件时列表就不会收缩，
     * 界面上那几条旧记录会一直挂着。UI 层会定时调用本方法。
     */
    @Synchronized
    fun pruneDiag() {
        val now = System.currentTimeMillis()
        val kept = diag.value.filter { now - it.at <= ttlMs }.takeLast(maxEntries)
        if (kept.size != diag.value.size) diag.value = kept
    }

    @Synchronized
    fun clearDiag() {
        diag.value = emptyList()
    }
}
