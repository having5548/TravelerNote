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
 * - 按用户设置的间隔定时刷新手表连接状态（写进 [WatchSyncState] 与通知栏文案）
 * - 期间保持消息监听，手表端 requestNote 自动回发便签
 * - 三种保活方式共用本服务，只差通知呈现：
 *   0 磁贴（静默 MIN 通道，无感）/ 1 常驻通知（DEFAULT 通道，显示连接状态）/ 2 Shizuku（静默 + 闹钟看门狗）
 */
class WatchSyncService : Service() {

    companion object {
        const val ACTION_STOP = "com.traveler.miyou.watch.action.STOP"
        const val NOTIFICATION_ID = 42
        private const val CHANNEL_SILENT = "watch_sync_silent"
        private const val CHANNEL_STATUS = "watch_sync_status"
        private const val WATCHDOG_REQUEST_CODE = 4242

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WatchSyncService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchSyncService::class.java).setAction(ACTION_STOP))
            context.stopService(Intent(context, WatchSyncService::class.java))
        }

        internal fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "手表同步（静默）", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    description = "磁贴 / Shizuku 模式的无感保活通知，不发声不弹横幅"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "手表同步（常驻）", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "常驻通知模式：显示手表连接状态"
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        settings = SettingsStore(this)
        createChannels(this)
        // startForeground 在后台启动受限场景（12+）可能抛异常：失败则放弃本次启动
        try {
            startForeground(
                NOTIFICATION_ID, buildNotification(getString(R.string.watch_service_running)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            runCatching { WatchNoteSync.refreshStatus(applicationContext) }
            refreshLoop()
        }
        scheduleWatchdog()
        return START_STICKY
    }

    private suspend fun refreshLoop() {
        // scope 在 onDestroy 里 cancel，delay 会抛 CancellationException 退出，这里只查业务条件
        while (running && settings.watchSyncEnabled) {
            delay(settings.watchRefreshMinutes * 60_000L)
            if (!running) return
            runCatching { WatchNoteSync.refreshStatus(applicationContext) }
            val st = WatchSyncState.status.value
            val text = if (st.connected) {
                getString(R.string.watch_service_connected_fmt, st.deviceName ?: "")
            } else {
                getString(R.string.watch_service_disconnected)
            }
            try {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(text))
            } catch (_: Exception) {
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val silent = settings.watchKeepAliveMode != 1
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, WatchSyncActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, if (silent) CHANNEL_SILENT else CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_sign)
            .setContentTitle(getString(R.string.watch_service_title))
            .setContentText(text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(silent)
            .build()
    }

    /** 闹钟看门狗：服务被杀后按刷新间隔尝试拉起；有 Shizuku 时用 shell 启动可无视后台启动限制。 */
    private fun scheduleWatchdog() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            this, WATCHDOG_REQUEST_CODE,
            Intent(this, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
        val interval = settings.watchRefreshMinutes * 60_000L
        am.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + interval, interval, pi
        )
    }

    private fun cancelWatchdog() {
        val am = getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            this, WATCHDOG_REQUEST_CODE,
            Intent(this, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
    }

    override fun onDestroy() {
        running = false
        cancelWatchdog()
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
