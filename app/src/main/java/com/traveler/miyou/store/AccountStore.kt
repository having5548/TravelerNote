// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.store

import android.content.Context
import java.util.UUID

/**
 * 多账号管理（对齐胡桃工具箱"同时缓存多个账号"的思路）。
 *
 * - 索引存在 `accounts` 里：账号 id 列表 + 当前账号 + 上次刷新时间；
 * - 每个账号的凭证独立存在 `session_<id>` 里，互不干扰；
 * - 设备标识（device_id / 指纹）与账号无关，单独存在 `device` 里；
 * - 老版本只有一个 `session`：首次启动会把它迁移成第一个账号，保持已登录状态。
 */
class AccountStore(context: Context) {

    private val appContext = context.applicationContext
    private val index = appContext.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
    }

    private fun accountPrefs(id: String) =
        appContext.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE)

    fun ids(): List<String> =
        index.getString(KEY_IDS, "")!!.split(',').filter { it.isNotBlank() }

    private fun saveIds(list: List<String>) {
        index.edit().putString(KEY_IDS, list.joinToString(",")).apply()
    }

    fun activeId(): String? = index.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    fun setActive(id: String) {
        index.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 当前账号 id；没有就新建一个空账号。 */
    fun activeOrCreate(): String = activeId() ?: createActive()

    /** 新建一个空账号并设为当前账号（用于"添加账号"后走登录流程）。 */
    fun createActive(): String {
        val id = "u" + UUID.randomUUID().toString().replace("-", "").take(6)
        saveIds(ids() + id)
        setActive(id)
        return id
    }

    fun remove(id: String) {
        accountPrefs(id).edit().clear().apply()
        val rest = ids().filter { it != id }
        saveIds(rest)
        if (activeId() == id) {
            index.edit().putString(KEY_ACTIVE, rest.firstOrNull() ?: "").apply()
        }
    }

    fun hasCredential(id: String): Boolean {
        val p = accountPrefs(id)
        return !p.getString("cookie_token", null).isNullOrBlank() ||
            !p.getString("stoken", null).isNullOrBlank() ||
            !p.getString("login_ticket", null).isNullOrBlank()
    }

    /** 账号展示名：昵称（UID）→ UID → 短 id。 */
    fun label(id: String): String {
        val p = accountPrefs(id)
        val nick = p.getString("role_nickname", null)?.takeIf { it.isNotBlank() }
        val uid = p.getString("role_uid", null)?.takeIf { it.isNotBlank() }
        return when {
            nick != null && uid != null -> "$nick（UID $uid）"
            uid != null -> "UID $uid"
            nick != null -> nick
            else -> "账号 $id"
        }
    }

    /** 上次统一刷新凭证的时间（用于"定时刷新"节流）。 */
    fun lastRefreshAt(): Long = index.getLong(KEY_LAST_REFRESH, 0L)

    fun markRefreshed() {
        index.edit().putLong(KEY_LAST_REFRESH, System.currentTimeMillis()).apply()
    }

    /**
     * 老版本只有一个 `session`：迁移成第一个账号，
     * 并把其中的设备标识挪到 `device`（设备级），避免换账号时设备 id 变化触发风控。
     */
    private fun migrateLegacyIfNeeded() {
        if (ids().isNotEmpty()) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return

        val device = appContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE).edit()
        DEVICE_KEYS.forEach { key ->
            (legacy.all[key] as? String)?.let { device.putString(key, it) }
        }
        device.apply()

        val hasCredential = !legacy.getString("cookie_token", null).isNullOrBlank() ||
            !legacy.getString("stoken", null).isNullOrBlank() ||
            !legacy.getString("login_ticket", null).isNullOrBlank()
        if (hasCredential) {
            val id = "u" + UUID.randomUUID().toString().replace("-", "").take(6)
            val target = accountPrefs(id).edit()
            legacy.all.forEach { (k, v) -> if (v is String) target.putString(k, v) }
            target.apply()
            saveIds(listOf(id))
            setActive(id)
        }
        legacy.edit().clear().apply()
    }

    companion object {
        private const val INDEX_PREFS = "accounts"
        private const val LEGACY_PREFS = "session"
        private const val DEVICE_PREFS = "device"
        private const val KEY_IDS = "ids"
        private const val KEY_ACTIVE = "active"
        private const val KEY_LAST_REFRESH = "last_refresh"

        private val DEVICE_KEYS = listOf(
            "device_id", "device_fp", "device_name", "device_model",
            "device_fp_registered", "device_fp_registered_at"
        )

        fun prefsName(id: String) = "session_$id"
    }
}
