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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clipbridge.app.MainActivity
import com.clipbridge.app.R
import com.clipbridge.app.data.StatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台保护前台服务。
 *
 * ## 它解决什么问题
 *
 * 连接本来是由无障碍服务持有的（零通知、最干净）。但无障碍服务只在
 * "用户在系统设置里开着它"时才被系统保活，而很多 ROM 在 App 被划出最近任务后
 * 会把整个进程连同无障碍服务一起掐掉 —— 表现就是"后台待一会儿就掉线了"。
 *
 * 这个服务把连接**同时也**持有一份（`SyncEngine` 用引用计数管理，两边都持有时
 * 谁退出都不会断），并靠下面几点扛住常见的清理：
 *
 * | 手段 | 作用 |
 * | --- | --- |
 * | `START_STICKY` | 被系统回收后请求重建 |
 * | `stopWithTask=false` | 划出最近任务时不被连带销毁 |
 * | `onTaskRemoved` 预约闹钟 | 万一还是被杀了，闹钟把服务拉回来 |
 * | 常驻通知 | 给系统一个"前台身份"，同时让用户知道它在跑 |
 *
 * ## 关于通知
 *
 * Android 要求前台服务必须显示通知，**无法隐藏**。这里用 IMPORTANCE_LOW
 * 渠道 + 不响不震，尽量安静；Android 14 起用户可以手动划掉这条通知，
 * 但服务本身不会因此停止 —— 真被 ROM 连带杀掉时由看门狗负责恢复。
 *
 * 通知上**没有任何操作按钮**：同步是全自动的，不需要用户去点"复制"。
 */
class SyncService : Service() {

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL_SYNC = "sync"
        private const val NOTIF_ID = 1
        private const val HOLDER = "keepalive-service"

        /** 服务当前是否活着。看门狗靠它判断"要不要拉一把"。 */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * 请求启动。
         *
         * 所有调用点都必须容忍失败：Android 12+ 限制"从后台启动前台服务"，
         * 由闹钟/广播触发的启动可能在部分机型上直接被系统拒绝，
         * 这里把异常吞掉并返回 false，由调用方决定后续（通常是等下一次自检）。
         */
        fun start(ctx: Context): Boolean = try {
            val intent = Intent(ctx, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "启动后台保护服务被拒绝", e)
            false
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, SyncService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止后台保护服务失败", e)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserver: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须在 5 秒内 startForeground，否则会被判 ANR
        startAsForeground()
        running = true

        SyncEngine.acquire(this, HOLDER)
        observeConnectionState()

        // 无论现在连没连上，都把常规自检挂上 —— 这样即使之后进程被杀，
        // 闹钟也会把它叫回来，不必等到用户手动打开 App。
        KeepAlive.scheduleWatchdog(this)

        // START_STICKY：被系统回收后请求重建（onStartCommand 会以 null intent 再来一次）。
        // 注意它对"用户在设置里强行停止"无效 —— 那之后任何东西都拉不起来。
        return START_STICKY
    }

    /**
     * 用户把 App 从最近任务里划掉了。
     *
     * 因为清单里声明了 `stopWithTask=false`，服务本身不会被连带销毁，
     * 连接也就继续保持着 —— 这是"划掉后台照样同步"的关键。
     *
     * 但部分 ROM 仍然会强杀进程，所以这里顺手预约一次几秒后的自检：
     * 进程真死了，闹钟会在系统层面把我们叫回来（闹钟不随进程消亡）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        StatusHolder.addDiag("App 被划出最近任务，后台保护继续运行")
        KeepAlive.scheduleWatchdog(this, KeepAlive.RESTART_DELAY_MS)
    }

    override fun onDestroy() {
        running = false
        stateObserver?.cancel()
        stateObserver = null
        scope.cancel()
        SyncEngine.release(HOLDER)

        // 走到这里通常意味着进程即将被回收。只要用户没主动关掉后台保护，
        // 就预约一次很快的自检，让服务尽快回来。
        // 用户主动关闭时 Prefs 已经是 false，这里会自然跳过，不会"关不掉"。
        if (KeepAlive.superviseEnabled(this)) {
            StatusHolder.addDiag("后台保护服务被销毁，已安排自动恢复")
            KeepAlive.scheduleWatchdog(this, KeepAlive.RESTART_DELAY_MS)
        }

        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // LOW：不出声、不弹横幅，只安静地挂在通知栏
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.channel_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_sync_desc)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(connected: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SYNC)
            // 通知栏小图标必须是纯白剪影，用彩色图标会被系统渲染成一块白方块
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_sync_title))
            .setContentText(
                getString(
                    if (connected) R.string.notif_sync_connected
                    else R.string.notif_sync_connecting
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startAsForeground() {
        val n = buildNotification(StatusHolder.connected.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** 连接状态变化时刷新通知文案 —— 用户在通知栏就能看出当前通不通 */
    private fun observeConnectionState() {
        if (stateObserver?.isActive == true) return
        stateObserver = scope.launch {
            StatusHolder.connected.collect { connected ->
                try {
                    NotificationManagerCompat.from(this@SyncService)
                        .notify(NOTIF_ID, buildNotification(connected))
                } catch (e: SecurityException) {
                    // Android 13+ 用户拒绝了通知权限：前台服务照跑，只是通知不可见
                }
            }
        }
    }
}
