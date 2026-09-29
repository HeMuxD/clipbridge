package com.clipbridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clipbridge.app.MainActivity
import com.clipbridge.app.R

/**
 * **可选的**保活前台服务。
 *
 * 默认不启用：连接由上层的无障碍服务持有，不产生任何常驻通知 —— 那才是真正的无感。
 * 但部分国产 ROM 会定时掐掉没有前台身份的后台进程，若遇到频繁掉线，
 * 可以在设置里打开「常驻通知保活」，用一条低优先级通知换取更稳的存活。
 *
 * 通知上**没有任何操作按钮**：同步是全自动的，不需要用户去点「复制」。
 */
class SyncService : Service() {

    companion object {
        private const val CHANNEL_SYNC = "sync"
        private const val NOTIF_ID = 1
        private const val HOLDER = "keepalive-service"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, SyncService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须在 5 秒内 startForeground，否则会被系统判为 ANR
        startAsForeground()
        SyncEngine.acquire(this, HOLDER)
        return START_STICKY
    }

    override fun onDestroy() {
        SyncEngine.release(HOLDER)
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // LOW：不出声、不弹横幅，只安静地挂在通知栏
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_sync_desc) }
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_sync_title))
            .setContentText(getString(R.string.notif_sync_desc))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
