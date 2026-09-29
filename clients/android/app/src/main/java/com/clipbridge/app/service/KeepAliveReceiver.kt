package com.clipbridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clipbridge.app.data.StatusHolder

/**
 * 看门狗闹钟的接收端。
 *
 * 每次被唤醒做三件事：
 *   1. 服务不在 / 连接断了 → 重新拉起后台保护服务
 *   2. 续订下一次自检（闹钟是一次性的，必须自己续）
 *   3. 处理完就把自己忘掉 —— 它不持有任何状态，进程重启也不影响
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Log.i(TAG, "看门狗唤醒（action=${intent.action}）")

        val serviceAlive = SyncService.running
        val connected = ClipClient.connected

        if (!serviceAlive || !connected) {
            StatusHolder.addDiag(
                "看门狗自检：服务${if (serviceAlive) "在" else "不在"}，" +
                    "连接${if (connected) "正常" else "已断"} → 尝试恢复"
            )
            // 两条路一起走：
            //   ① 服务不在 → 把它拉起来（会顺带 acquire 连接）
            //   ② 服务在但连接断了 → 直接补一次连接
            // 只做 ① 是不够的：引用计数还大于 0 时 acquire 不会再触发连接。
            KeepAlive.tryStartService(ctx, "watchdog")
            SyncEngine.ensureConnected()
        }

        // 续订下一次。放在最后 —— 即使上面的启动失败，也要继续自检，
        // 不能因为一次失败就永久停止。
        if (KeepAlive.superviseEnabled(ctx)) {
            KeepAlive.scheduleWatchdog(ctx)
        } else {
            StatusHolder.addDiag("后台保护已关闭，看门狗停止")
        }
    }

    private companion object {
        const val TAG = "KeepAliveReceiver"
    }
}
