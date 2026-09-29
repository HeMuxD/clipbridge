package com.clipbridge.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clipbridge.app.data.StatusHolder
import com.clipbridge.app.service.KeepAlive

/**
 * 开机自启 + 应用更新后自启。
 *
 * 手机重启是"同步失效"最容易被忽略的一种：用户不会觉得是 App 的问题，
 * 只会觉得"今天怎么不同步了"。开机广播不注册的话，重启后就一直是离线状态。
 *
 * ⚠️ 两个现实约束：
 *   1. 用户没开过 App 就重启（例如恢复出厂后）时，SharedPreferences 里没有 Token，
 *      这里会直接跳过 —— 没配对过的东西没有拉起来的必要。
 *   2. Android 15 起 `dataSync` 类型的前台服务**不允许**由 BOOT_COMPLETED 启动，
 *      会抛 ForegroundServiceStartNotAllowedException。SyncService.start() 已经
 *      把异常吞掉并返回 false，此时看门狗闹钟会在下一次自检时再试 ——
 *      而那时进程已由广播拉起，"从后台启动前台服务"的限制便不再适用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val ctx = context.applicationContext
        if (!KeepAlive.superviseEnabled(ctx)) {
            Log.i(TAG, "开机广播：未开启后台保护或尚未配对，跳过")
            return
        }

        val ok = KeepAlive.tryStartService(ctx, "boot")
        StatusHolder.addDiag(
            if (ok) "开机自启：后台保护已恢复" else "开机自启：系统拒绝启动后台服务，已安排重试"
        )

        // 无论这次成没成，都把看门狗挂上：成了就常规自检，没成就是一次重试机会。
        KeepAlive.scheduleWatchdog(ctx, if (ok) KeepAlive.WATCHDOG_INTERVAL_MS else 10_000L)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
