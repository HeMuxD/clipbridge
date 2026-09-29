package com.clipbridge.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.clipbridge.app.data.Prefs

/**
 * 后台保护的"最后一道保险"。
 *
 * ## 先说清楚能做到什么、做不到什么
 *
 * Android 没有任何机制能让一个被用户**强行停止**（设置 → 强行停止，或某些 ROM 的
 * 一键清理）的 App 自动复活 —— 这是系统设计，不是 bug，任何 App 都绕不过去。
 *
 * 但下面三种最常见的"掉线"是可以扛住的：
 *
 * | 场景 | 靠什么扛 |
 * | --- | --- |
 * | 从最近任务里划掉 App | 前台服务声明了 `stopWithTask=false`，不会被连带销毁 |
 * | 通知被手动清掉 / ROM 悄悄杀进程 | 看门狗闹钟把服务重新拉起来 |
 * | 手机重启 | 开机广播 |
 *
 * 看门狗用 `AlarmManager` 而不是自己起协程，关键就在这：**闹钟由系统持有，
 * 不随进程消亡**。进程已经被杀掉之后，它是唯一还能把我们叫醒的东西。
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    /**
     * 看门狗自检间隔。
     *
     * `setAndAllowWhileIdle` 在 Doze 下系统最短允许约 9 分钟一次，
     * 这里填 5 分钟 —— 系统会在允许时尽快送达，实际间隔可能被拉长，属于预期。
     */
    const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L

    /** 刚刚被划掉/被杀时用的短延迟，尽快把自己拉回来 */
    const val RESTART_DELAY_MS = 2_000L

    private const val ACTION_TICK = "com.clipbridge.app.action.KEEPALIVE_TICK"
    private const val REQUEST_CODE = 0xCB01

    private fun tickIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, KeepAliveReceiver::class.java).setAction(ACTION_TICK)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)
    }

    /** 预约下一次自检。可重复调用（会替换掉上一次的预约）。 */
    fun scheduleWatchdog(ctx: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                tickIntent(ctx),
            )
        } catch (e: Exception) {
            Log.w(TAG, "预约看门狗失败", e)
        }
    }

    fun cancelWatchdog(ctx: Context) {
        try {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(ctx))
        } catch (e: Exception) {
            Log.w(TAG, "取消看门狗失败", e)
        }
    }

    /** 是否已加入电池优化白名单。在名单里，后台进程才不会被 Doze 静默掐掉。 */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    } catch (e: Exception) {
        false
    }

    /**
     * 申请加入电池优化白名单。
     *
     * 用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹对话框，
     * 比让用户自己去设置里翻要省事。个别 ROM 屏蔽了这个入口，
     * 那就退回到电池优化列表页。
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        val pkgUri = Uri.parse("package:${ctx.packageName}")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(pkgUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launchSafely(ctx, direct)) return
        launchSafely(
            ctx,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * 打开厂商的「自启动 / 后台运行」设置页。
     *
     * 国产 ROM 对这一项的控制是独立的：即使给了电池优化白名单，
     * 没开自启动的 App 也会在划掉后被彻底禁止拉起。
     * 没有统一接口，只能按厂商逐个尝试，全都失败就退到应用详情页。
     */
    fun openAutoStartSettings(ctx: Context) {
        val candidates = listOf(
            // 小米 / Redmi / POCO
            "com.miui.securitycenter" to
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // 荣耀
            "com.hihonor.systemmanager" to
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // OPPO / 一加 / realme
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // 联想 / 乐视
            "com.lenovo.security" to
                "com.lenovo.security.purebackground.PureBackgroundActivity",
        )

        for ((pkg, cls) in candidates) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchSafely(ctx, intent)) return
        }

        // 兜底：应用详情页，用户自己找「自启动 / 省电策略」
        launchSafely(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun launchSafely(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (e: Exception) {
        // 该 ROM 没有这个页面，很正常，继续试下一个
        false
    }

    /**
     * 是否应该继续自检。
     *
     * 两个条件缺一不可：用户开着后台保护，且已经配对过。
     * 没配对时拉起服务毫无意义（连不上任何东西），只会白白常驻一条通知。
     */
    fun superviseEnabled(ctx: Context): Boolean =
        Prefs.isBackgroundProtectEnabled(ctx) && Prefs.getToken(ctx).isNotEmpty()

    /**
     * 尝试把后台保护服务拉起来。
     *
     * Android 12+ 限制"从后台启动前台服务"，闹钟触发并不在豁免之列，
     * 因此在有些机型上这里会抛 `ForegroundServiceStartNotAllowedException`。
     * 那种情况下只能退而求其次：发一条可点击的普通通知提醒用户手动打开 ——
     * 与其静默失败，不如让用户知道连接断了。
     */
    fun tryStartService(ctx: Context, from: String): Boolean {
        if (!superviseEnabled(ctx)) return false
        return SyncService.start(ctx).also { ok ->
            Log.i(TAG, "后台保护服务启动（来源：$from）成功=$ok")
        }
    }
}
