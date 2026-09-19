// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 手表连接状态的全局共享状态：服务 / 页面 / 设置入口都从这里读写，
 * 页面用 StateFlow 订阅，服务每次刷新后更新。
 *
 * 除了"连接 / 电量 / 已安装"，这里还记录最近一次推送的时间与失败原因 ——
 * 后台保活出问题时用户至少能在页面上看到是哪一步断了。
 */
object WatchSyncState {

    data class WatchStatus(
        val connected: Boolean = false,
        val deviceName: String? = null,
        /** -1 表示未知。查询失败或数值不合理（0 / >100）都按未知处理，避免显示"电量 0%"。 */
        val battery: Int = -1,
        val charging: Boolean = false,
        /** 穿戴权限（DEVICE_MANAGER）是否已授予；未授予时查不到电量。 */
        val permissionGranted: Boolean = false,
        /** null=未查询；true/false=手表端快应用是否已安装。 */
        val installed: Boolean? = null,
        val lastCheck: Long = 0L,
        // 下面三项来自手表端 getStorageInfo 回包
        val versionName: String? = null,
        val buildTime: String? = null,
        /** 手表端应用自身文件占用（KB），-1 表示不可用。 */
        val storageUsedKb: Long = -1,
        /** 最近一次成功把便签推给手表的时间。 */
        val notePushedAt: Long = 0L,
        /** 最近一次推送包含的账号（最多 5 个，用「、」连接），null 表示没推过。 */
        val accountsSummary: String? = null,
        /** 最近一次推送失败的原因（null 表示上次推送成功）。 */
        val noteError: String? = null,
        /** 保活服务启动失败的原因（例如被系统/ROM 拒绝后台启动）。 */
        val startError: String? = null
    )

    private val _status = MutableStateFlow(WatchStatus())
    val status: StateFlow<WatchStatus> = _status

    fun update(transform: (WatchStatus) -> WatchStatus) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = WatchStatus()
    }
}
