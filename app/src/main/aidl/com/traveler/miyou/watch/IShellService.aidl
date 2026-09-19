// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch;

/**
 * Shizuku 用户服务接口：方法体在 shell（uid 2000）进程内执行。
 * destroy() 固定使用事务号 16777114（Shizuku 约定，aidl 工具允许的最大值）。
 */
interface IShellService {
    String exec(String command) = 1;
    void destroy() = 16777114;
}
