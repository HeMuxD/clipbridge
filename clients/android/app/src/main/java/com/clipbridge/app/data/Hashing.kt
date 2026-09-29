package com.clipbridge.app.data

import java.security.MessageDigest

/**
 * 内容哈希（SHA-256 十六进制小写），与服务端 / Windows 端保持一致。
 */
object Hashing {
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
}

/**
 * 近期处理过的内容哈希。
 *
 * 三个作用，与 Windows 端的 HashCache 一一对应：
 *   1. **阻断回环**：我们把远端内容写进剪贴板后，无障碍监听会再次看到它，
 *      命中哈希就跳过；否则会 A→B→A→B 无限循环，把服务端流量打爆。
 *   2. **同一次操作只同步一次**：Android 对一次剪贴板写入可能回调多次。
 *   3. **启动时登记既有内容**：服务启动瞬间剪贴板里已有的东西属于"以往的复制"，
 *      先登记进来，它就不会被当成一次新的复制操作上报。
 */
object HashCache {
    private const val CAPACITY = 200

    private val order = ArrayDeque<String>()
    private val set = HashSet<String>()

    @Synchronized
    fun add(hash: String) {
        if (hash.isEmpty()) return
        if (!set.add(hash)) return
        order.addLast(hash)
        while (order.size > CAPACITY) {
            set.remove(order.removeFirst())
        }
    }

    @Synchronized
    fun contains(hash: String): Boolean = set.contains(hash)

    @Synchronized
    fun clear() {
        order.clear()
        set.clear()
    }
}
