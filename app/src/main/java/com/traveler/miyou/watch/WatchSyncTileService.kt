// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.traveler.miyou.R
import com.traveler.miyou.store.SettingsStore

/**
 * 下拉快捷栏磁贴（类似 GKD 的无感保活开关）：
 * 亮 = 保活服务在跑。副标题会区分「保活中 / 已连接手表 / 等手表连接」——
 * 以前磁贴只反映服务是否在跑，服务被系统杀了磁贴还可能显示亮着，
 * 看起来就像"保活正常但手表一直未连接"。
 */
class WatchSyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // 展开快捷面板时让服务刷一次真实连接状态，别显示过期状态
        runCatching { WatchSyncService.requestRefresh(applicationContext) }
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val app = applicationContext
        val store = SettingsStore(app)
        if (WatchSyncService.running) {
            // 磁贴熄灭 = 关掉同步总开关，避免出现"开关还亮着但服务已死"的假状态
            store.watchSyncEnabled = false
            WatchSyncService.stop(app)
        } else {
            store.watchSyncEnabled = true
            runCatching { WatchSyncService.start(app) }
            // 起来之后顺手把看门狗排上，避免"服务起来了但没人守着"
            WatchSyncService.scheduleWatchdog(app, store.watchRefreshMinutes)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val running = WatchSyncService.running
        val st = WatchSyncState.status.value
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.watch_service_title)
        tile.subtitle = when {
            !running -> getString(R.string.watch_tile_off)
            st.connected -> getString(R.string.watch_tile_connected)
            else -> getString(R.string.watch_tile_waiting)
        }
        tile.updateTile()
    }
}
