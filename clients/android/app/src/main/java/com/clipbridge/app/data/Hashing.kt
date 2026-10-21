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
 *
 * 容量分两档：文本哈希与图片指纹（`fp:` 前缀）各自计数。
 * 早期共用一个 200 条的环形队列，截图密集时（每张图要占 1 个字节 hash + 1 个 dHash）
 * 会把文本的条目挤出去，导致刚同步过的文本又被判成"新内容"重复上报。
 */
object HashCache {
    /** 文本/字节 hash 的容量 */
    private const val CAPACITY = 200

    /** 图片像素指纹（`fp:` 前缀）单独一档容量 */
    private const val FP_CAPACITY = 200

    private val order = ArrayDeque<String>()
    private val set = HashSet<String>()

    private val fpOrder = ArrayDeque<String>()
    private val fpSet = HashSet<String>()

    @Synchronized
    fun add(hash: String) {
        if (hash.isEmpty()) return
        if (hash.startsWith("fp:")) {
            if (!fpSet.add(hash)) return
            fpOrder.addLast(hash)
            while (fpOrder.size > FP_CAPACITY) {
                fpSet.remove(fpOrder.removeFirst())
            }
            return
        }
        if (!set.add(hash)) return
        order.addLast(hash)
        while (order.size > CAPACITY) {
            set.remove(order.removeFirst())
        }
    }

    @Synchronized
    fun contains(hash: String): Boolean =
        if (hash.startsWith("fp:")) fpSet.contains(hash) else set.contains(hash)

    @Synchronized
    fun clear() {
        order.clear()
        set.clear()
        fpOrder.clear()
        fpSet.clear()
    }
}
