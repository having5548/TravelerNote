// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku 辅助：检测可用性 / 授权，并以 shell 权限执行命令（用于保活看门狗绕过后台启动限制）。
 * 命令通过 Shizuku 用户服务 [ShellUserService] 在 shell uid 进程里执行。
 */
object ShizukuKeeper {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Shizuku 服务是否在运行（未安装或未启动返回 false）。 */
    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean =
        available() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    /** 以 shell 权限执行一条命令，结果通过回调返回（binder 事务上限 1MB，别拿来拉大输出）。 */
    fun exec(context: Context, command: String, onResult: (String) -> Unit = {}) {
        if (!granted()) return
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
            .processNameSuffix("shell")
            .version(1)
        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val output = runCatching {
                    IShellService.Stub.asInterface(service).exec(command)
                }.getOrElse { "exec failed: ${it.message}" }
                onResult(output)
                // 用完即解绑：之前每次都只 bind 不 unbind，看门狗跑久了会一直堆积绑定
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        runCatching { Shizuku.bindUserService(args, connection) }
    }
}
