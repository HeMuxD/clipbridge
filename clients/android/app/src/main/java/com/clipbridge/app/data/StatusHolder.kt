package com.clipbridge.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     * 唯一的例外是复菜单项的文案（截断到 12 字）—— 排障必须知道它长什么样，
     * 否则无法判断"复制"标签匹配规则要不要放宽。这些内容只留在本机内存里，不上传。
     */
    val diag = MutableStateFlow<List<String>>(emptyList())

    private const val DIAG_MAX = 40
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun addDiag(line: String) {
        val stamped = "${timeFmt.format(Date())} $line"
        // 直接构造新 list，避免 MutableStateFlow 因为元素相同而不触发重组
        diag.value = (diag.value + stamped).takeLast(DIAG_MAX)
    }

    @Synchronized
    fun clearDiag() {
        diag.value = emptyList()
    }
}
