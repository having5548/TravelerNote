// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.content.Context

/**
 * 跑在 Shizuku（shell uid 2000）里的用户服务：用 Runtime.exec 执行命令并回传输出。
 */
class ShellUserService(context: Context?) : IShellService.Stub() {

    override fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return (out + err).ifBlank { "ok" }
    }

    override fun destroy() {
        // Shizuku 销毁服务时回调，无需额外清理
    }
}
