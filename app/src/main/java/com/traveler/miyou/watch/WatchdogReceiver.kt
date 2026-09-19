// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.traveler.miyou.store.SettingsStore

/**
 * 闹钟看门狗：按刷新间隔被 AlarmManager 唤醒，发现保活服务不在就重新拉起。
 * 常规 startForegroundService 被后台限制拒绝时，若 Shizuku 可用则改用 shell 启动（无后台启动限制）。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (WatchSyncService.running) return
        val settings = SettingsStore(app)
        if (!settings.watchSyncEnabled) return
        runCatching {
            WatchSyncService.start(app)
        }.onFailure {
            if (ShizukuKeeper.granted()) {
                ShizukuKeeper.exec(
                    app,
                    "am start-foreground-service -n ${app.packageName}/.watch.WatchSyncService"
                )
            }
        }
    }
}
