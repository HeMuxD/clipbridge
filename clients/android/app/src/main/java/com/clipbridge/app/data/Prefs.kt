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
    private const val KEY_KEEPALIVE = "keepalive_fgs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getServerUrl(ctx: Context): String = sp(ctx).getString(KEY_SERVER_URL, "") ?: ""
    fun getToken(ctx: Context): String = sp(ctx).getString(KEY_TOKEN, "") ?: ""
    fun getDeviceName(ctx: Context): String = sp(ctx).getString(KEY_DEVICE_NAME, "") ?: ""

    fun hasToken(ctx: Context): Boolean = getToken(ctx).isNotEmpty()

    /**
     * 是否启用「常驻通知保活」（前台服务）。
     *
     * 默认关闭：连接由无障碍服务持有，不产生任何常驻通知。
     * 只有在 ROM 频繁掐后台导致掉线时，才建议打开。
     */
    fun isKeepAliveEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEEPALIVE, false)

    fun setKeepAlive(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_KEEPALIVE, enabled).apply()
    }

    fun save(ctx: Context, serverUrl: String, token: String, deviceId: String, deviceName: String) {
        sp(ctx).edit()
            .putString(KEY_SERVER_URL, serverUrl.trimEnd('/'))
            .putString(KEY_TOKEN, token)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .apply()
    }

    fun saveToken(ctx: Context, token: String) =
        sp(ctx).edit().putString(KEY_TOKEN, token).apply()

    /** 生成并持久化一个稳定的设备 ID（首次运行） */
    fun deviceId(ctx: Context): String {
        val existing = sp(ctx).getString(KEY_DEVICE_ID, "") ?: ""
        if (existing.isNotEmpty()) return existing
        val id = "android-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        sp(ctx).edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
