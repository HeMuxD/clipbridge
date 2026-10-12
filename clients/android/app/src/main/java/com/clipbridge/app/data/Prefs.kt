package com.clipbridge.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地配置与 Token 的持久化（SharedPreferences）。
 * MVP 阶段不做加密存储，后续可换 EncryptedSharedPreferences / Keystore。
 */
object Prefs {
    private const val NAME = "clipbridge"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PAIR_CODE = "pair_code"
    private const val KEY_BG_PROTECT = "background_protect"
    private const val KEY_SCREENSHOT = "screenshot_sync"
    private const val KEY_DIAG_MAX = "diag_max"
    private const val KEY_DIAG_TTL_MIN = "diag_ttl_minutes"

    /** 诊断记录默认条数上限 */
    const val DEFAULT_DIAG_MAX = 200

    /** 诊断记录默认保留时长（分钟） */
    const val DEFAULT_DIAG_TTL_MIN = 120

    /** 界面上可选的条数 / 时长预设 */
    val DIAG_MAX_CHOICES = listOf(100, 200, 500, 1000)
    val DIAG_TTL_CHOICES_MIN = listOf(30, 120, 360, 1440)

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getServerUrl(ctx: Context): String = sp(ctx).getString(KEY_SERVER_URL, "") ?: ""
    fun getToken(ctx: Context): String = sp(ctx).getString(KEY_TOKEN, "") ?: ""
    fun getDeviceName(ctx: Context): String = sp(ctx).getString(KEY_DEVICE_NAME, "") ?: ""

    /**
     * 上次成功配对时用的配对码。
     *
     * 只在**配对成功后**才写入（不是边输入边存），这样输错的部分不会被记住。
     * 存下来的好处是重装/重启后不用再去翻服务端日志找那个码。
     */
    fun getPairCode(ctx: Context): String = sp(ctx).getString(KEY_PAIR_CODE, "") ?: ""

    fun hasToken(ctx: Context): Boolean = getToken(ctx).isNotEmpty()

    /**
     * 取消配对：只清掉 Token。
     *
     * 地址、配对码、设备名**故意保留** —— 大多数取消配对是为了改其中某一项再重配，
     * 保留下来等于预填好表单，不用重新敲一遍。
     * 清掉 Token 之后连接自然不成立（`SyncEngine.doConnect` 会因为 token 为空直接跳过），
     * 后台保护也会因为 `KeepAlive.superviseEnabled` 返回 false 而不再拉起服务。
     */
    fun clearPairing(ctx: Context) {
        sp(ctx).edit().remove(KEY_TOKEN).commit()
    }

    /**
     * 是否启用「后台保护」（前台服务 + 常驻通知）。
     *
     * **默认开启**：不做这一步的话，App 从最近任务里被划掉后连接就断了，
     * 而那正是"同步时好时坏"最常见的原因。代价是通知栏里会常驻一条静默通知 ——
     * 用户可以在界面上关掉。
     */
    fun isBackgroundProtectEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_BG_PROTECT, true)

    fun setBackgroundProtect(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_BG_PROTECT, enabled).apply()
    }

    /**
     * 是否启用「截图同步」。
     *
     * 默认关闭：监听相册需要媒体读取权限（Android 13+ 是 READ_MEDIA_IMAGES），
     * 这是个「读你所有照片」级别的权限，不该在用户没明确要求时静默索取。
     */
    fun isScreenshotSyncEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SCREENSHOT, false)

    fun setScreenshotSync(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SCREENSHOT, enabled).apply()
    }

    // ---------- 诊断记录：条数与保留时长可配 ----------

    /**
     * 诊断流水最多保留多少条。
     *
     * 做成可配是因为"要留多少"取决于用户想拿它做什么：
     * 只是想知道刚才为什么没同步，200 条足够；
     * 想回顾一整天的同步情况，就得往上加。
     */
    fun diagMax(ctx: Context): Int =
        sp(ctx).getInt(KEY_DIAG_MAX, DEFAULT_DIAG_MAX).coerceIn(20, 10_000)

    fun setDiagMax(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_DIAG_MAX, value.coerceIn(20, 10_000)).apply()
    }

    /** 诊断流水的保留时长（分钟）。超过这个时间的记录会被自动淘汰。 */
    fun diagTtlMinutes(ctx: Context): Int =
        sp(ctx).getInt(KEY_DIAG_TTL_MIN, DEFAULT_DIAG_TTL_MIN).coerceIn(1, 7 * 24 * 60)

    fun setDiagTtlMinutes(ctx: Context, value: Int) {
        sp(ctx).edit()
            .putInt(KEY_DIAG_TTL_MIN, value.coerceIn(1, 7 * 24 * 60))
            .apply()
    }

    fun diagTtlMs(ctx: Context): Long = diagTtlMinutes(ctx) * 60_000L

    /** 把时长格式化成给人看的样子：30 分钟 / 2 小时 / 1 天 */
    fun formatTtl(minutes: Int): String = when {
        minutes < 60 -> "$minutes 分钟"
        minutes == 60 -> "1 小时"
        minutes < 1440 && minutes % 60 == 0 -> "${minutes / 60} 小时"
        minutes == 1440 -> "1 天"
        minutes % 1440 == 0 -> "${minutes / 1440} 天"
        else -> "${minutes / 60} 小时"
    }

    /**
     * 保存配对结果。
     *
     * ⚠️ 这里用 commit() 而不是 apply()：apply() 只保证写进内存，
     * 落盘是异步的，进程若在其完成前被杀（清理后台、崩溃），这批设置就丢了。
     * 配对是一次性的低频操作，同步落盘的开销完全可以接受 ——
     * 而"配对成功但重启后又要重新配对"是用户直接能感知到的故障。
     */
    fun save(
        ctx: Context,
        serverUrl: String,
        token: String,
        deviceId: String,
        deviceName: String,
        pairCode: String,
    ) {
        sp(ctx).edit()
            .putString(KEY_SERVER_URL, serverUrl.trim().trimEnd('/'))
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putString(KEY_PAIR_CODE, pairCode.trim())
            .commit()
    }

    /** 生成并持久化一个稳定的设备 ID（首次运行） */
    fun deviceId(ctx: Context): String {
        val s = sp(ctx)
        val existing = s.getString(KEY_DEVICE_ID, "") ?: ""
        if (existing.isNotEmpty()) return existing
        val id = "android-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        s.edit().putString(KEY_DEVICE_ID, id).commit()
        return id
    }
}
