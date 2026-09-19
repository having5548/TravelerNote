// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.traveler.miyou.store.SettingsStore

/**
 * 看门狗：闹钟触发 / 开机广播时检查保活服务，不在就拉起来。
 *
 * - 常规 `startForegroundService` 在 Android 12+ 后台会被拒（`ForegroundServiceStartNotAllowedException`），
 *   此时若 Shizuku 可用就改用 shell 权限 `am start-foreground-service`（不受后台启动限制）。
 * - 每次触发都会**重排下一次**一次性闹钟：比 `setRepeating` 更可靠（Repeating 在 Doze 下会被大幅推迟）。
 *   即便服务正常销毁，闹钟也保留，所以被杀后还有机会被拉回来。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val settings = SettingsStore(app)

        // 先排下一次，避免这次处理过程中被系统回收导致再也不触发
        if (settings.watchSyncEnabled) {
            WatchSyncService.scheduleWatchdog(app, settings.watchRefreshMinutes)
        }
        if (!settings.watchSyncEnabled) return
        if (WatchSyncService.running) return

        // 后台 startForegroundService 被拒时 Android 会**同步抛异常**，据此决定是否用 Shizuku 兜底
        val started = runCatching { WatchSyncService.start(app) }.isSuccess
        if (started) return

        // 用 Shizuku（shell uid）拉起，绕过后台启动限制
        if (ShizukuKeeper.granted()) {
            ShizukuKeeper.exec(
                app,
                "am start-foreground-service -n ${app.packageName}/.watch.WatchSyncService"
            )
        }
    }
}
