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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手表同步保活服务（前台服务）：
 * - **不再主动推数据**：数据由手表端主动拉取（手表打开应用 / 点刷新 / 数据超过 4 小时自动获取）
 * - 服务只负责保持消息监听与连接状态最新，手表来 `requestNote` 时能立刻把数据回给它
 * - 按用户选择的间隔刷新连接状态，并更新常驻通知
 *
 * 三种保活方式共用本服务，只差通知呈现：0 磁贴（静默）/ 1 常驻通知 / 2 Shizuku（静默 + 闹钟看门狗）。
 * 闹钟看门狗在服务被杀后负责把它拉回来，所以**服务正常销毁时不会取消闹钟**（只有用户关掉同步才取消）。
 */
class WatchSyncService : Service() {

    companion object {
        const val ACTION_STOP = "com.traveler.miyou.watch.action.STOP"

        /** 让正在运行的服务立刻刷一次连接状态（磁贴/通知用到）。 */
        const val ACTION_REFRESH = "com.traveler.miyou.watch.action.REFRESH"
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

        /** 已经在跑的服务：让它立刻刷一次状态（没在跑就什么都不做，别去后台起服务）。 */
        fun requestRefresh(context: Context) {
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, WatchSyncService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }

        /** 是否已被系统允许后台运行（忽略电池优化）。没加白名单时清后台很容易被杀。 */
        fun ignoringBatteryOptimizations(context: Context): Boolean = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

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
        if (intent?.action == ACTION_REFRESH) {
            // 已经在跑：只刷一次状态（磁贴每次展开时用），不动刷新循环
            scope.launch {
                runCatching { WatchNoteSync.refreshStatus(applicationContext) }
                updateNotification()
            }
            return START_STICKY
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
            // 只刷新连接状态：数据由手表端主动拉取（见 WatchNoteSync 的 requestNote 处理）
            runCatching { WatchNoteSync.refreshStatus(applicationContext) }
            updateNotification()
            refreshLoop()
        }
        return START_STICKY
    }

    /**
     * 被从最近任务里划掉时（用户"清后台"）：国产 ROM 往往会顺手把前台服务一起杀掉，
     * 这里立刻重排看门狗闹钟并尝试把服务拉回来。没加白名单时不保证成功，
     * 但至少闹钟还在 —— 到点由 WatchdogReceiver（必要时走 Shizuku）再拉一次。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::settings.isInitialized && settings.watchSyncEnabled) {
            scheduleWatchdog(this, settings.watchRefreshMinutes)
            runCatching { start(this) }
        }
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun refreshLoop() {
        // scope 在 onDestroy 里 cancel，delay 会抛 CancellationException 退出，这里只查业务条件
        while (running && settings.watchSyncEnabled) {
            delay(settings.watchRefreshMinutes * 60_000L)
            if (!running) return
            // 手机端不推数据：这里只让连接状态/权限/消息监听保持最新，手表来拉时能立刻回
            runCatching { WatchNoteSync.refreshStatus(applicationContext) }
            updateNotification()
        }
    }

    private fun updateNotification() {
        val st = WatchSyncState.status.value
        val base = when {
            // 断连：进入准备状态，等手表回来
            st.preparing -> getString(R.string.watch_service_preparing)
            !st.connected -> getString(R.string.watch_service_disconnected)
            st.noteError != null -> getString(R.string.watch_service_push_failed, st.noteError)
            st.battery in 1..100 -> getString(
                R.string.watch_service_connected_battery_fmt, st.deviceName ?: "", st.battery
            )
            else -> getString(R.string.watch_service_connected_fmt, st.deviceName ?: "")
        }
        // 没加白名单时把风险直接写在通知里：清后台被杀多半就是这个原因
        val text = if (ignoringBatteryOptimizations(this)) {
            base
        } else {
            getString(R.string.watch_service_no_whitelist, base)
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val silent = settings.watchKeepAliveMode != 1
        // 点通知回到主界面的「手表同步」页（页内内容，不再是独立 Activity）
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.traveler.miyou.ui.MainActivity::class.java)
                .putExtra(
                    com.traveler.miyou.ui.MainActivity.EXTRA_PAGE,
                    com.traveler.miyou.ui.MainActivity.PAGE_WATCH
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
