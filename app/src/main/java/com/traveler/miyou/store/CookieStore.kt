// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.store

import android.content.Context
import android.content.SharedPreferences
import com.traveler.miyou.net.MiyouApi
import java.util.UUID

/**
 * 登录态与设备标识持久化。
 *
 * 凭证按账号隔离（`session_<id>`，见 [AccountStore]），设备标识全局共用（`device`），
 * 这样同时缓存多个账号也不会互相串号，换账号时设备指纹保持不变。
 */
class CookieStore(context: Context, accountId: String? = null) {

    private val appContext = context.applicationContext
    private val accounts = AccountStore(appContext)

    /** 账号级：登录凭证 + 角色缓存。accountId 为空时用当前账号（没有就新建）。 */
    private val sp: SharedPreferences = appContext.getSharedPreferences(
        AccountStore.prefsName(accountId ?: accounts.activeOrCreate()),
        Context.MODE_PRIVATE
    )

    /** 设备级：与账号无关。 */
    private val device: SharedPreferences =
        appContext.getSharedPreferences("device", Context.MODE_PRIVATE)

    // ---- 设备标识（全局共用） ----
    fun deviceId(): String {
        var id = device.getString("device_id", null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            device.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun deviceFp(): String {
        var fp = device.getString("device_fp", null)
        if (fp.isNullOrBlank()) {
            val hex = "0123456789abcdef"
            fp = buildString {
                repeat(13) { append(hex[kotlin.random.Random.nextInt(16)]) }
            }
            device.edit().putString("device_fp", fp).apply()
        }
        return fp
    }

    fun deviceName(): String {
        device.getString("device_name", null)?.let { return it }
        val name = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
        device.edit().putString("device_name", name).apply()
        return name
    }

    fun deviceModel(): String {
        device.getString("device_model", null)?.let { return it }
        val model = android.os.Build.MODEL.ifBlank { "Android" }
        device.edit().putString("device_model", model).apply()
        return model
    }

    /** 已在官方注册过的设备指纹（7 天有效期），见 net/DeviceFp.kt。 */
    fun registeredFp(): String? =
        device.getString("device_fp_registered", null)?.takeIf { it.isNotBlank() }

    fun registeredFpAt(): Long = device.getLong("device_fp_registered_at", 0L)

    fun saveRegisteredFp(fp: String) {
        device.edit()
            .putString("device_fp_registered", fp)
            .putLong("device_fp_registered_at", System.currentTimeMillis())
            .apply()
    }

    // ---- 登录态（当前账号） ----
    fun isLoggedIn(): Boolean =
        !get("cookie_token").isNullOrBlank() && !get("account_id").isNullOrBlank()

    fun hasAnyCredential(): Boolean =
        !get("cookie_token").isNullOrBlank() ||
            !get("stoken").isNullOrBlank() ||
            !get("login_ticket").isNullOrBlank()

    fun get(key: String): String? = sp.getString(key, null)?.takeIf { it.isNotBlank() }
    fun put(key: String, value: String) = sp.edit().putString(key, value).apply()
    fun remove(key: String) = sp.edit().remove(key).apply()

    /** 只清当前账号的凭证，不影响其他账号。 */
    fun clear() = sp.edit().clear().apply()

    fun roleUid(): String? = get("role_uid")
    fun roleRegion(): String? = get("role_region")
    fun roleNickname(): String? = get("role_nickname")

    fun saveRole(uid: String, region: String, nickname: String) {
        put("role_uid", uid)
        put("role_region", region)
        put("role_nickname", nickname)
    }

    /** 保存扫码/短信登录得到的 stoken 体系凭证。 */
    fun saveLoginTokens(stoken: String, mid: String, stuid: String) {
        put("stoken", stoken)
        put("mid", mid)
        put("stuid", stuid)
        if (ltuid().isNullOrBlank()) put("ltuid", stuid)
        // 换了账号就清掉旧角色缓存，避免 uid/region 串号导致签到 10002
        remove("role_uid")
        remove("role_region")
        remove("role_nickname")
    }

    /** 解析原始 Cookie 串并落盘。 */
    fun saveRawCookie(raw: String) {
        val map = parseCookie(raw)
        val editor = sp.edit()
        map.forEach { (k, v) -> if (v.isNotBlank()) editor.putString(k, v) }
        editor.apply()
    }

    /** 优先取 v1 key，缺失时回退 v2 key。 */
    private fun pick(vararg keys: String): String? {
        for (k in keys) {
            get(k)?.let { return it }
        }
        return null
    }

    fun stuid(): String? = pick("stuid", "stuid_v2")
    fun stoken(): String? = pick("stoken", "stoken_v2")
    fun mid(): String? = pick("mid", "mid_v2")

    /** 仅 stoken 体系的 Cookie（widget 实时数据用）。 */
    fun stokenCookieStr(): String {
        val parts = mutableListOf<String>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank()) parts.add("$key=$value")
        }
        add("stuid", stuid())
        add("stoken", stoken())
        add("mid", mid())
        return parts.joinToString(";")
    }

    /** 胡桃工具箱风格：仅 account_id + cookie_token。 */
    fun cookieTokenCookieStr(): String {
        val parts = mutableListOf<String>()
        accountId()?.let { parts.add("account_id=$it") }
        cookieToken()?.let { parts.add("cookie_token=$it") }
        return parts.joinToString(";")
    }

    /**
     * 战绩接口用 Cookie：对齐 Snap.Hutao 的 CookieType.Cookie（cookie_token 体系 + ltoken 体系）。
     */
    fun recordCookieStr(): String {
        val parts = mutableListOf<String>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank()) parts.add("$key=$value")
        }
        add("account_id", accountId())
        add("cookie_token", cookieToken())
        add("ltuid", ltuid())
        add("ltoken", ltoken())
        return parts.joinToString(";")
    }

    fun ltoken(): String? = pick("ltoken", "ltoken_v2")
    fun ltuid(): String? = pick("ltuid", "ltuid_v2")
    fun cookieToken(): String? = get("cookie_token")
    fun accountId(): String? = get("account_id")
    fun loginTicket(): String? = get("login_ticket")
    fun loginUid(): String? = get("login_uid")

    /** 生成携带全部已归一化 token 的 Cookie 串。 */
    fun fullCookie(): String {
        val parts = mutableListOf<String>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank()) parts.add("$key=$value")
        }
        add("stuid", stuid())
        add("stoken", stoken())
        add("mid", mid())
        add("ltuid", ltuid())
        add("ltoken", ltoken())
        add("account_id", accountId())
        add("cookie_token", cookieToken())
        return parts.joinToString(";")
    }

    /**
     * 归一化登录态：优先用 login_ticket 换 stoken，再用 stoken 换 cookie_token / ltoken。
     * 返回是否成功获得可用的 cookie_token。
     */
    suspend fun normalize(): Boolean {
        if (!cookieToken().isNullOrBlank() && !accountId().isNullOrBlank()) return true

        var curStoken = stoken()
        var curStuid = stuid()
        var curMid = mid()

        val ticket = loginTicket()
        val loginUid = loginUid()
        if (curStoken.isNullOrBlank() && !ticket.isNullOrBlank() && !loginUid.isNullOrBlank()) {
            val tokens = MiyouApi.fetchTokensByLoginTicket(ticket, loginUid)
            // token_type 1 = stoken
            tokens[1]?.let {
                curStoken = it
                put("stoken", it)
                if (curStuid.isNullOrBlank()) {
                    put("stuid", loginUid)
                    curStuid = loginUid
                }
            }
            tokens[2]?.let {
                put("ltoken", it)
                if (ltuid().isNullOrBlank()) put("ltuid", loginUid)
            }
        }

        if (!curStoken.isNullOrBlank() && !curStuid.isNullOrBlank()) {
            val suid: String = curStuid!!
            val stk: String = curStoken!!
            val info = MiyouApi.fetchCookieTokenByStoken(
                suid, stk, curMid.orEmpty(), deviceId(), deviceFp()
            )
            if (info != null && info.cookieToken.isNotBlank()) {
                put("cookie_token", info.cookieToken)
                put("account_id", info.accountId.ifBlank { info.uid.ifBlank { suid } })
            }
            val lt = MiyouApi.fetchLtokenByStoken(suid, stk, curMid.orEmpty(), deviceId(), deviceFp())
            if (!lt.isNullOrBlank()) {
                put("ltoken", lt)
                if (ltuid().isNullOrBlank()) put("ltuid", suid)
            }
        }

        return !cookieToken().isNullOrBlank() && !accountId().isNullOrBlank()
    }

    /** 后台统一刷新所有账号的凭证，避免长期不用导致 cookie 失效（由 AccountStore 节流）。 */
    fun accountIdForDisplay(): String? = accountId()

    companion object {
        fun parseCookie(raw: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            raw.split(';').forEach { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@forEach
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
            return map
        }
    }
}
