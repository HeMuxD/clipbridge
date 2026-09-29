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
