package com.clipbridge.app.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局连接状态，供 UI 层（Compose）观察。
 */
object StatusHolder {
    val connected = MutableStateFlow(false)
    val statusText = MutableStateFlow("")
}
