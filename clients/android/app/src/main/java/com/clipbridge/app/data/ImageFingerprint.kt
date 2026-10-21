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
     *
     * 关于内存：先用 inJustDecodeBounds 读尺寸，再按 2 的幂降采样到"至少 9×8"。
     * 直接把一张 4000×3000 的截图全尺寸解码再缩放，单个位图就要 48 MB，
     * 在低端机或连续处理多张图时很容易 OOM —— 而这里只需要 9×8 的灰度信息。
     */
    fun dHash(bytes: ByteArray): String? {
        // 先只读尺寸，不解码像素
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        // 降采样到"宽高都不小于目标尺寸"的最小 2 的幂
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 9 && opts.outHeight / (sample * 2) >= 8) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

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
            if (scaled !== bmp) scaled.recycle()
            sb.toString()
        } finally {
            bmp.recycle()
        }
    }
}
