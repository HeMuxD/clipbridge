package com.clipbridge.app

import android.app.Application
import com.clipbridge.app.service.KeepAlive

/**
 * Application 入口。
 *
 * 这里只做一件事：保证「后台保护」的状态与用户的选择一致。
 * 进程可能因为很多原因被拉起来（点开图标、开机广播、看门狗闹钟），
 * 无论哪种，都应该顺手确认一下服务在不在、看门狗还挂着 ——
 * 这两件事都是幂等的，重复执行没有副作用。
 */
class ClipBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val ctx = applicationContext

        if (KeepAlive.superviseEnabled(ctx)) {
            KeepAlive.tryStartService(ctx, "application")
            KeepAlive.scheduleWatchdog(ctx)
        } else {
            // 用户关掉了后台保护：把看门狗一并撤掉，
            // 否则它会每 5 分钟白白唤醒一次做无用功。
            KeepAlive.cancelWatchdog(ctx)
        }
    }
}
