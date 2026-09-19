// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 手表连接状态的全局共享状态：服务/页面/设置入口都从这里读写，
 * 页面用 StateFlow 订阅，服务每次定时刷新后更新。
 */
object WatchSyncState {

    data class WatchStatus(
        val connected: Boolean = false,
        val deviceName: String? = null,
        /** -1 表示未知。 */
        val battery: Int = -1,
        /** null=未查询；true/false=手表端快应用是否已安装。 */
        val installed: Boolean? = null,
        val lastCheck: Long = 0L,
        // 下面三项来自手表端 getStorageInfo 回包
        val versionName: String? = null,
        val buildTime: String? = null,
        /** 手表端应用自身文件占用（KB），-1 表示不可用。 */
        val storageUsedKb: Long = -1
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
