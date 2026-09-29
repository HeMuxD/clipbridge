package com.clipbridge.app.data

/**
 * 跨端协议常量，与 shared/protocol/PROTOCOL.md 严格对齐。
 */
object MsgType {
    const val HELLO = "hello"
    const val CLIP = "clip"
    const val ACK = "ack"
    const val PING = "ping"
    const val PONG = "pong"
    const val DEVICE_LIST = "device_list"
    const val ERROR = "error"
}

object ClipKind {
    const val TEXT = "text"
    const val IMAGE = "image"
}

object ClipOrigin {
    const val CLIPBOARD = "clipboard"
    const val SHARE = "share"
    const val SCREENSHOT = "screenshot"
}
