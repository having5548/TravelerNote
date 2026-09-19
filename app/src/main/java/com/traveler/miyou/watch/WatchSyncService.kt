// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.traveler.miyou.R
import com.traveler.miyou.store.SettingsStore
import com.traveler.miyou.ui.WatchSyncActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手表同步保活服务（前台服务）：
 * - 启动时**立刻推一次便签**给手表（不看连接检查结果，失败会把原因记进 [WatchSyncState]）
 * - 之后按用户选择的间隔：刷新手表连接状态 **并再推一次便签**（连接失败下一次继续尝试）
 * - 期间保持消息监听，手表端 requestNote 自动回发便签
 *
 * 三种保活方式共用本服务，只差通知呈现：0 磁贴（静默）/ 1 常驻通知 / 2 Shizuku（静默 + 闹钟看门狗）。
 * 闹钟看门狗在服务被杀后负责把它拉回来，所以**服务正常销毁时不会取消闹钟**（只有用户关掉同步才取消）。
 */
class WatchSyncService : Service() {

    companion object {
        const val ACTION_STOP = "com.traveler.miyou.watch.action.STOP"
        const val NOTIFICATION_ID = 42
        private const val CHANNEL_SILENT = "watch_sync_silent"
        private const val CHANNEL_STATUS = "watch_sync_status"
        private const val WATCHDOG_REQUEST_CODE = 4242
        private const val STOP_REQUEST_CODE = 4243

        @Volatile
        var running: Boolean = false
            private set

        /** 启动保活服务。后台受限场景（Android 12+）会抛异常，调用方自己兜。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchSyncService::class.java))
        }

        /** 停止保活服务并取消看门狗闹钟（不改设置项，重启类调用方用）。 */
        fun stop(context: Context) {
            cancelWatchdog(context)
            context.stopService(Intent(context, WatchSyncService::class.java))
        }

        /**
         * 排下一次看门狗闹钟（一次性 + 每次触发后重排，比 setRepeating 更可靠）。
         * 有精确闹钟权限就用精确的（顺带能在 Doze 下唤醒），没有就退到 allowWhileIdle。
         */
        fun scheduleWatchdog(context: Context, minutes: Int) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = watchdogPendingIntent(context) ?: return
            runCatching { am.cancel(pi) }
            val interval = minutes.coerceAtLeast(1) * 60_000L
            val trigger = SystemClock.elapsedRealtime() + interval
            val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            } else {
                true
            }
            try {
                if (exactAllowed) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
            } catch (e: SecurityException) {
                runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi) }
            } catch (_: Exception) {
            }
        }

        fun cancelWatchdog(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            watchdogPendingIntent(context)?.let { runCatching { am.cancel(it) } }
        }

        private fun watchdogPendingIntent(context: Context): PendingIntent? = runCatching {
            PendingIntent.getBroadcast(
                context, WATCHDOG_REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull()

        internal fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "手表同步（静默）", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    description = "磁贴 / Shizuku 模式的无感保活通知，不发声、不弹横幅"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "手表同步（常驻）", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "常驻通知模式：显示手表连接状态（低频，不打扰）"
                }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private lateinit var settings: SettingsStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        settings = SettingsStore(this)
        if (intent?.action == ACTION_STOP) {
            // 通知栏上的「关闭同步」：连同设置项一起关掉，页面上的开关也会跟着变
            settings.watchSyncEnabled = false
            cancelWatchdog(this)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels(this)
        try {
            startForeground(
                NOTIFICATION_ID, buildNotification(getString(R.string.watch_service_running)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            WatchSyncState.update { it.copy(startError = null) }
        } catch (e: Exception) {
            // 后台启动前台服务被拒（Android 12+）：把原因记下来，页面能看到，并让看门狗继续尝试
            val reason = (e.message ?: e.javaClass.simpleName)
            WatchSyncState.update { it.copy(startError = reason) }
            stopSelf()
            return START_NOT_STICKY
        }

        scheduleWatchdog(this, settings.watchRefreshMinutes)
        refreshJob?.cancel()
        refreshJob = scope.launch {
            runCatching { WatchNoteSync.refreshStatus(applicationContext) }
            // 启动就推一次：不看连接检查结果，失败会把原因写进状态
            runCatching { WatchNoteSync.pushNote(applicationContext, forceRefresh = true) }
            updateNotification()
            refreshLoop()
        }
        return START_STICKY
    }

    private suspend fun refreshLoop() {
        // scope 在 onDestroy 里 cancel，delay 会抛 CancellationException 退出，这里只查业务条件
        while (running && settings.watchSyncEnabled) {
            delay(settings.watchRefreshMinutes * 60_000L)
            if (!running) return
            runCatching { WatchNoteSync.refreshStatus(applicationContext) }
            // 按用户选择的间隔也推一次便签；连不上就下一次再来
            runCatching { WatchNoteSync.pushNote(applicationContext, forceRefresh = false) }
            updateNotification()
        }
    }

    private fun updateNotification() {
        val st = WatchSyncState.status.value
        val text = when {
            !st.connected -> getString(R.string.watch_service_disconnected)
            st.noteError != null -> getString(R.string.watch_service_push_failed, st.noteError)
            st.battery in 1..100 -> getString(
                R.string.watch_service_connected_battery_fmt, st.deviceName ?: "", st.battery
            )
            else -> getString(R.string.watch_service_connected_fmt, st.deviceName ?: "")
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val silent = settings.watchKeepAliveMode != 1
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, WatchSyncActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, STOP_REQUEST_CODE,
            Intent(this, WatchSyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, if (silent) CHANNEL_SILENT else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_sign)
            .setContentTitle(getString(R.string.watch_service_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(silent)
            // 同一 id 反复更新时不要再响/震（否则每个刷新间隔都会打扰一次）
            .setOnlyAlertOnce(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.watch_notification_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        running = false
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
