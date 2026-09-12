// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多账号凭证的"定时刷新"。
 *
 * Android 上不引入 WorkManager（避免新增依赖），采用**前台触发 + 节流**的方式：
 * 每次打开应用检查一次，距上次刷新超过 [INTERVAL_MS] 就把所有已保存账号的
 * `stoken → cookie_token / ltoken` 重新换一遍，降低长期不用导致 cookie 失效的概率。
 */
object AccountRefresher {

    /** 刷新间隔：6 小时。 */
    private const val INTERVAL_MS = 6 * 3600 * 1000L

    fun refreshIfDue(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch {
            val accounts = AccountStore(appContext)
            if (System.currentTimeMillis() - accounts.lastRefreshAt() < INTERVAL_MS) return@launch
            withContext(Dispatchers.IO) {
                accounts.ids()
                    .filter { accounts.hasCredential(it) }
                    .forEach { id ->
                        runCatching { CookieStore(appContext, id).normalize() }
                    }
                accounts.markRefreshed()
            }
        }
    }

    /** 手动强制刷新所有账号，返回成功刷新的账号数。 */
    suspend fun refreshAll(context: Context): Int {
        val appContext = context.applicationContext
        val accounts = AccountStore(appContext)
        val count = withContext(Dispatchers.IO) {
            accounts.ids()
                .filter { accounts.hasCredential(it) }
                .count { id -> runCatching { CookieStore(appContext, id).normalize() }.getOrDefault(false) }
        }
        accounts.markRefreshed()
        return count
    }
}
