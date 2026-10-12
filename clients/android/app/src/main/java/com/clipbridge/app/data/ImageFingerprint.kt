package com.clipbridge.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * 图片的「像素指纹」（dHash，64 位）。
 *
 * 与按字节的 SHA-256 不同，这个指纹对**重新压缩 / 缩放 / 轻微调色**不敏感：
 * 同一张截图，无论被小米相册云同步压成多大、转成什么编码，算出来的 dHash 都一样。
 *
 * 用途：阻断「云同步回环」。
 * 用户多台小米设备登录同一账号时，A 设备截的图会被小米云**压缩后同步到 B 设备的相册**，
 * B 的截图监听会把这份云同步来的文件当成"新截图"再上行 —— 于是三端各多一份。
 * 字节 hash 挡不住（云同步压缩后字节全变），只有像素指纹能挡。
 */
object ImageFingerprint {

    /**
     * 计算 dHash。解码失败（不是图片）返回 null。
     *
     * 算法：缩放到 9×8 → 灰度（Rec.601）→ 每行相邻比较（右>左记 1）→ 64 位 → 16 个十六进制字符。
     */
    fun dHash(bytes: ByteArray): String? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val w = 9
            val h = 8
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, false)
            val gray = IntArray(w * h)
            var i = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = scaled.getPixel(x, y)
                    val r = (p shr 16) and 0xff
                    val g = (p shr 8) and 0xff
                    val b = p and 0xff
                    gray[i++] = (r * 299 + g * 587 + b * 114) / 1000
                }
            }

            val sb = StringBuilder(16)
            for (y in 0 until h) {
                var byte = 0
                for (x in 0 until w - 1) {
                    byte = (byte shl 1) or (if (gray[y * w + x + 1] > gray[y * w + x]) 1 else 0)
                }
                sb.append(String.format("%02x", byte))
            }
            scaled.recycle()
            sb.toString()
        } finally {
            bmp.recycle()
        }
    }
}
