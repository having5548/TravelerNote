// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.traveler.miyou.R
import com.traveler.miyou.store.SettingsStore

/**
 * 下拉快捷栏磁贴（类似 GKD 的无感保活开关）：
 * 亮 = 保活服务运行中；灭 = 已停止。点击切换，首次开启会自动打开数据同步总开关。
 */
class WatchSyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
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
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.state = if (WatchSyncService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.watch_service_title)
        tile.subtitle = getString(
            if (WatchSyncService.running) R.string.watch_tile_on else R.string.watch_tile_off
        )
        tile.updateTile()
    }
}
