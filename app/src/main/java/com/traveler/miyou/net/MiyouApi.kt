// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import org.json.JSONObject

/**
 * 米游社相关接口封装。
 */
object MiyouApi {

    private fun baseHeaders(deviceId: String, clientType: String, version: String, ua: String): MutableMap<String, String> {
        return mutableMapOf(
            "x-rpc-client_type" to clientType,
            "x-rpc-app_version" to version,
            "x-rpc-device_id" to deviceId,
            "User-Agent" to ua,
            "Accept-Encoding" to "gzip",
            "Accept" to "application/json"
        )
    }

    // ---------- 社区（论坛）签到：只在用户手动点击时调用，不参与自动流程 ----------

    private fun bbsSignHeaders(deviceId: String, deviceFp: String): MutableMap<String, String> {
        val headers = baseHeaders(deviceId, "2", ApiConst.BBS_VERSION, ApiConst.UA_MOBILE)
        headers["DS"] = DS.gen1(ApiConst.BBS_SALT)
        headers["x-rpc-channel"] = "miyousheluodi"
        headers["x-rpc-verify_key"] = ApiConst.APP_ID
        headers["x-rpc-device_fp"] = deviceFp
        headers["x-rpc-device_name"] = "Mi 10"
        headers["x-rpc-device_model"] = "Mi 10"
        headers["x-rpc-sys_version"] = "12"
        headers["x-rpc-h265_supported"] = "1"
        headers["x-rpc-csm_source"] = "home"
        headers["Referer"] = ApiConst.APP_REFERER
        return headers
    }

    /** 米游社社区签到（仅原神分区 gids=2）。 */
    fun signIn(cookie: String, deviceId: String, deviceFp: String): SignResult {
        val body = "{\"gids\": \"2\"}"
        val headers = bbsSignHeaders(deviceId, deviceFp)
        headers["DS"] = DS.gen2(ApiConst.X6_SALT, body, "")
        headers["Cookie"] = cookie

        val resp = Http.post(ApiConst.SIGN_IN_URL, body, headers)
        val parsed = parseApiResp(resp.body)
        return when {
            parsed.retcode == 0 -> SignResult(true, false, false, parsed.message.ifBlank { "社区签到成功" }, resp.body)
            parsed.retcode == 1034 -> SignResult(false, false, true, "需要人机验证", resp.body)
            parsed.retcode == -5003 -> SignResult(true, true, false, parsed.message.ifBlank { "社区今日已签到" }, resp.body)
            parsed.retcode == -100 -> SignResult(false, false, false, "登录态已过期", resp.body)
            else -> SignResult(false, false, false, "签到失败：" + parsed.message.ifBlank { "retcode=${retcodeOf(parsed.raw)}" }, resp.body)
        }
    }

    private fun retcodeOf(raw: String): String {
        return try {
            org.json.JSONObject(raw).optString("retcode", "未知")
        } catch (e: Exception) {
            "未知"
        }
    }

    /**
     * 查询社区每日打卡任务状态（mission 58 = 今日签到），用于显示"今日是否已签"。
     * 这是只读查询，不会触发签到；返回 null 表示查询失败。
     */
    fun fetchBbsSignedToday(cookie: String): Boolean? {
        return try {
            val url = "${ApiConst.BBS_API}/apihub/wapi/getUserMissionsState?point_sn=myb"
            val headers = mutableMapOf(
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to ApiConst.UA_WEB,
                "Referer" to ApiConst.WEBSTATIC,
                "Cookie" to cookie
            )
            val resp = Http.get(url, headers)
            val obj = org.json.JSONObject(resp.body)
            if (obj.optInt("retcode", -1) != 0) return null
            val states = obj.optJSONObject("data")?.optJSONArray("states") ?: return null
            for (i in 0 until states.length()) {
                val s = states.optJSONObject(i) ?: continue
                if (s.optInt("mission_id", -1) == 58) {
                    return s.optBoolean("is_get_award", false)
                }
            }
            false
        } catch (e: Exception) {
            null
        }
    }

    /** 用已通过验证的 challenge 重试社区签到。 */
    fun signInWithChallenge(cookie: String, deviceId: String, deviceFp: String, challenge: String): SignResult {
        val body = "{\"gids\": \"2\"}"
        val headers = bbsSignHeaders(deviceId, deviceFp)
        headers["DS"] = DS.gen2(ApiConst.X6_SALT, body, "")
        headers["x-rpc-challenge"] = challenge
        headers["Cookie"] = cookie

        val resp = Http.post(ApiConst.SIGN_IN_URL, body, headers)
        val parsed = parseApiResp(resp.body)
        return when {
            parsed.retcode == 0 -> SignResult(true, false, false, parsed.message.ifBlank { "社区签到成功" }, resp.body)
            parsed.retcode == 1034 -> SignResult(false, false, true, "需要人机验证", resp.body)
            parsed.retcode == -5003 -> SignResult(true, true, false, parsed.message.ifBlank { "社区今日已签到" }, resp.body)
            else -> SignResult(false, false, false, "签到失败：" + parsed.message.ifBlank { "retcode=${parsed.retcode}" }, resp.body)
        }
    }

    /** 初始化社区签到的人机验证（极验）。 */
    fun createVerification(cookie: String, deviceId: String, deviceFp: String): GeetestChallenge? {
        val headers = bbsSignHeaders(deviceId, deviceFp)
        headers["Cookie"] = cookie
        val resp = Http.get(ApiConst.CREATE_VERIFICATION_URL, headers)
        return parseGeetestChallenge(resp.body)
    }

    /** 提交极验结果，换取可直接放进 x-rpc-challenge 的 challenge。 */
    fun verifyVerification(cookie: String, deviceId: String, deviceFp: String, gtChallenge: String, validate: String, seccode: String): VerificationPass? {
        val body = JSONObject().apply {
            put("geetest_challenge", gtChallenge)
            put("geetest_validate", validate)
            put("geetest_seccode", seccode)
        }.toString()
        val headers = bbsSignHeaders(deviceId, deviceFp)
        headers["Cookie"] = cookie
        val resp = Http.post(ApiConst.VERIFY_VERIFICATION_URL, body, headers)
        return parseVerificationPass(resp.body)
    }

    /** 备用：用 stoken 获取绑定的原神角色（cookie 方式拿不到时使用）。 */
    fun fetchRolesByStoken(stokenCookie: String, deviceId: String): List<GameRole> {
        val url = "${ApiConst.TAKUMI_API}/binding/api/getUserGameRolesByStoken"
        val headers = baseHeaders(deviceId, "5", ApiConst.BBS_VERSION, ApiConst.UA_WEB)
        headers["Cookie"] = stokenCookie
        val resp = Http.get(url, headers)
        return parseGameRoles(resp.body)
    }

    /** 获取账号绑定的原神角色列表。 */
    fun fetchRoles(cookie: String, deviceId: String): List<GameRole> {
        val url = "${ApiConst.GAME_ROLES_URL}?game_biz=hk4e_cn"
        val headers = baseHeaders(deviceId, "5", ApiConst.BBS_VERSION, ApiConst.UA_WEB)
        headers["DS"] = DS.gen1(ApiConst.BBS_WEB_SALT)
        headers["Referer"] = "https://act.mihoyo.com/"
        headers["Origin"] = "https://act.mihoyo.com"
        headers["x-rpc-channel"] = "miyousheluodi"
        headers["X-Requested-With"] = "com.mihoyo.hyperion"
        headers["Cookie"] = cookie
        val resp = Http.get(url, headers)
        return parseGameRoles(resp.body)
    }

    /** 用 login_ticket 换取多类型 token。 */
    fun fetchTokensByLoginTicket(loginTicket: String, loginUid: String): Map<Int, String> {
        val encTicket = java.net.URLEncoder.encode(loginTicket, "UTF-8")
        val encUid = java.net.URLEncoder.encode(loginUid, "UTF-8")
        val url = "${ApiConst.MULTI_TOKEN_URL}?login_ticket=$encTicket&uid=$encUid&token_types=3"
        val headers = baseHeaders("", "5", ApiConst.BBS_VERSION, ApiConst.UA_WEB)
        headers.remove("x-rpc-device_id")
        val resp = Http.get(url, headers)
        return parseTokens(resp.body)
    }

    /** 用 stoken 换取 cookie_token / account_id。 */
    fun fetchCookieTokenByStoken(stuid: String, stoken: String, mid: String, deviceId: String, deviceFp: String): CookieAccountInfo? {
        val url = "${ApiConst.COOKIE_TOKEN_URL}?stoken=${java.net.URLEncoder.encode(stoken, "UTF-8")}"
        val headers = passportTokenHeaders(stuid, stoken, mid, deviceId, deviceFp, clientType = "2")
        headers["DS"] = DS.gen2(ApiConst.X4_SALT, "", "stoken=$stoken")
        headers["x-rpc-aigis"] = ""
        val resp = Http.get(url, headers)
        return parseCookieAccountInfo(resp.body)
    }

    /** 用 stoken 换取 ltoken。 */
    fun fetchLtokenByStoken(stuid: String, stoken: String, mid: String, deviceId: String, deviceFp: String): String? {
        val url = "${ApiConst.LTOKEN_URL}?stoken=${java.net.URLEncoder.encode(stoken, "UTF-8")}"
        val headers = passportTokenHeaders(stuid, stoken, mid, deviceId, deviceFp, clientType = "5")
        headers["DS"] = DS.gen2(ApiConst.X4_SALT, "", "stoken=$stoken")
        val resp = Http.get(url, headers)
        return try {
            val obj = org.json.JSONObject(resp.body)
            if (obj.optInt("retcode", -1) == 0) obj.optJSONObject("data")?.optString("ltoken", "") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun passportTokenHeaders(stuid: String, stoken: String, mid: String, deviceId: String, deviceFp: String, clientType: String): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>(
            "User-Agent" to ApiConst.UA_DESKTOP,
            "x-rpc-app_version" to ApiConst.BBS_VERSION,
            "x-rpc-client_type" to clientType,
            "x-requested-with" to "com.mihoyo.hyperion",
            "Referer" to ApiConst.WEBSTATIC,
            "x-rpc-device_id" to deviceId,
            "x-rpc-device_fp" to deviceFp,
            "Accept-Encoding" to "gzip"
        )
        headers["Cookie"] = buildStokenCookie(stuid, stoken, mid)
        return headers
    }

    /**
     * 通过 widget v2 接口获取树脂等实时数据（stoken 通道，替代易风控的 dailyNote）。
     * 部分机型/版本会出现 10001 invalid request，这里尝试多种官方参数组合。
     */
    fun fetchWidgetResin(cookie: String, deviceId: String, deviceFp: String): WidgetResult {
        val query = "game_id=2"
        val url = "${ApiConst.TAKUMI_RECORD_API}/game_record/app/genshin/aapi/widget/v2?$query"

        data class Try(val clientType: String, val version: String, val ua: String, val referer: String?, val salt: String)

        val attempts = listOf(
            Try("5", ApiConst.RECORD_VERSION, ApiConst.UA_DESKTOP, null, ApiConst.X6_SALT),
            Try("2", ApiConst.BBS_VERSION, ApiConst.UA_MOBILE, ApiConst.APP_REFERER, ApiConst.X6_SALT),
            Try("2", "2.11.1", ApiConst.UA_MOBILE, ApiConst.APP_REFERER, ApiConst.X6_SALT),
            Try("2", "2.40.0", ApiConst.UA_MOBILE, ApiConst.APP_REFERER, ApiConst.X6_SALT),
            Try("5", ApiConst.RECORD_VERSION, ApiConst.UA_DESKTOP, null, ApiConst.X4_SALT)
        )

        var lastMsg = ""
        for (a in attempts) {
            val headers = baseHeaders(deviceId, a.clientType, a.version, a.ua)
            headers["DS"] = DS.gen2(a.salt, "", query)
            headers["x-rpc-device_fp"] = deviceFp
            headers["Cookie"] = cookie
            a.referer?.let { headers["Referer"] = it }
            val resp = Http.get(url, headers)
            val parsed = parseWidgetResult(resp.body)
            if (parsed.note != null) return parsed
            lastMsg = if (lastMsg.isBlank()) parsed.message else "$lastMsg | ${parsed.message}"
        }
        return WidgetResult(null, false, lastMsg.ifBlank { "retcode=10001 invalid request" })
    }

    // ---------- 登录：二维码 ----------

    private fun qrHeaders(body: String, deviceId: String, deviceFp: String, deviceName: String, deviceModel: String): MutableMap<String, String> {
        return mutableMapOf(
            "User-Agent" to ApiConst.UA_PASSPORT_APP,
            "Accept" to "*/*",
            "Accept-Language" to "zh-cn",
            "x-rpc-client_type" to "3",
            "x-rpc-app_version" to ApiConst.PASSPORT_APP_VERSION,
            "x-rpc-device_id" to deviceId,
            "x-rpc-device_fp" to deviceFp,
            "x-rpc-game_biz" to "bbs_cn",
            "x-rpc-app_id" to ApiConst.APP_ID,
            "x-rpc-sdk_version" to ApiConst.PASSPORT_APP_VERSION,
            "x-rpc-device_model" to deviceModel,
            "x-rpc-device_name" to deviceName,
            "x-rpc-account_version" to ApiConst.PASSPORT_APP_VERSION,
            "DS" to DS.gen2(ApiConst.APP_SALT, body, ""),
            "Content-Type" to "application/json; charset=UTF-8"
        )
    }

    /** 生成登录二维码。 */
    fun createQrLogin(deviceId: String, deviceFp: String, deviceName: String, deviceModel: String): QrTicket? {
        val body = "{}"
        val headers = qrHeaders(body, deviceId, deviceFp, deviceName, deviceModel)
        val resp = Http.post(ApiConst.QR_FETCH_URL, body, headers)
        return parseQrTicket(resp.body)
    }

    /** 查询二维码登录状态。 */
    fun queryQrLogin(ticket: String, deviceId: String, deviceFp: String, deviceName: String, deviceModel: String): QrStatus? {
        val body = JSONObject().put("ticket", ticket).toString()
        val headers = qrHeaders(body, deviceId, deviceFp, deviceName, deviceModel)
        val resp = Http.post(ApiConst.QR_QUERY_URL, body, headers)
        return parseQrStatus(resp.body)
    }

    // ---------- 登录：手机号 ----------

    private fun smsHeaders(aigis: String, deviceId: String, deviceFp: String, deviceName: String, deviceModel: String, create: Boolean): MutableMap<String, String> {
        val headers = mutableMapOf(
            "x-rpc-aigis" to aigis,
            "x-rpc-app_version" to ApiConst.BBS_VERSION,
            "x-rpc-client_type" to "2",
            "x-rpc-app_id" to ApiConst.APP_ID,
            "x-rpc-device_fp" to deviceFp,
            "x-rpc-device_name" to deviceName,
            "x-rpc-device_id" to deviceId,
            "x-rpc-device_model" to deviceModel,
            "User-Agent" to ApiConst.UA_WEB,
            "Content-Type" to "application/json"
        )
        if (create) {
            headers["Referer"] = "https://user.miyoushe.com/"
            headers["x-rpc-game_biz"] = "hk4e_cn"
        }
        return headers
    }

    data class SmsCaptchaResp(val sent: CaptchaSent?, val aigis: String?)

    /** 发送短信验证码。 */
    fun sendSmsCaptcha(phone: String, aigis: String, deviceId: String, deviceFp: String, deviceName: String, deviceModel: String): SmsCaptchaResp {
        val body = JSONObject().apply {
            put("area_code", Rsa.encrypt("+86"))
            put("mobile", Rsa.encrypt(phone))
        }.toString()
        val headers = smsHeaders(aigis, deviceId, deviceFp, deviceName, deviceModel, create = true)
        val resp = Http.post(ApiConst.CAPTCHA_SEND_URL, body, headers)
        return SmsCaptchaResp(parseCaptchaSent(resp.body), resp.headers["x-rpc-aigis"])
    }

    data class SmsLoginResp(val tokens: LoginTokens?, val aigis: String?)

    /** 用短信验证码登录。 */
    fun loginBySmsCaptcha(phone: String, captcha: String, actionType: String, aigis: String, deviceId: String, deviceFp: String, deviceName: String, deviceModel: String): SmsLoginResp {
        val body = JSONObject().apply {
            put("area_code", Rsa.encrypt("+86"))
            put("mobile", Rsa.encrypt(phone))
            put("action_type", actionType)
            put("captcha", captcha)
        }.toString()
        val headers = smsHeaders(aigis, deviceId, deviceFp, deviceName, deviceModel, create = false)
        val resp = Http.post(ApiConst.CAPTCHA_LOGIN_URL, body, headers)
        return SmsLoginResp(parseLoginTokens(resp.body), resp.headers["x-rpc-aigis"])
    }

    private fun buildStokenCookie(stuid: String, stoken: String, mid: String): String {
        val sb = StringBuilder()
        if (stuid.isNotEmpty()) sb.append("stuid=$stuid;")
        if (stoken.isNotEmpty()) sb.append("stoken=$stoken;")
        if (mid.isNotEmpty()) sb.append("mid=$mid;")
        return sb.toString().trimEnd(';')
    }

    /** 通过提瓦特小助手获取角色列表。 */
    fun fetchLelaerCharacters(uid: String): List<LelaerChar> {
        val url = "https://api.lelaer.com/ys/getPlayerRecord.php?uid=$uid"
        val resp = Http.get(url, mapOf("User-Agent" to ApiConst.UA_DESKTOP))
        return parseLelaerCharacters(resp.body)
    }

    // ---------- 战绩（api-takumi-record）：签名方式对齐 Snap.Hutao ----------

    /**
     * 战绩类请求头。与 Snap.Hutao 的 GameRecordClient 一致：
     * DS 用 X4 盐的 Gen2，且参与签名的 query 要按 & 拆开后字母序重新拼接。
     */
    private fun recordHeaders(cookie: String, deviceId: String, deviceFp: String, query: String): MutableMap<String, String> {
        val sortedQuery = query.split('&').sorted().joinToString("&")
        val headers = baseHeaders(deviceId, "5", ApiConst.RECORD_VERSION, ApiConst.UA_DESKTOP)
        headers["DS"] = DS.gen2(ApiConst.X4_SALT, "", sortedQuery)
        headers["Referer"] = "${ApiConst.WEBSTATIC}/app/community-game-records/index.html"
        headers["x-rpc-device_fp"] = deviceFp
        headers["x-rpc-tool_verison"] = "v5.0.1-ys"
        headers["Cookie"] = cookie
        return headers
    }

    /** 深境螺旋战绩。scheduleType：1 = 本期，2 = 上期。 */
    fun fetchSpiralAbyss(
        cookie: String,
        deviceId: String,
        deviceFp: String,
        uid: String,
        region: String,
        scheduleType: Int = 1
    ): SpiralAbyss {
        val query = "role_id=$uid&schedule_type=$scheduleType&server=$region"
        val resp = Http.get("${ApiConst.SPIRAL_ABYSS_URL}?$query", recordHeaders(cookie, deviceId, deviceFp, query))
        return parseSpiralAbyss(resp.body)
    }

    /** 幻想真境剧诗战绩。 */
    fun fetchRoleCombat(cookie: String, deviceId: String, deviceFp: String, uid: String, region: String): RoleCombat {
        val query = "active=1&need_detail=true&role_id=$uid&server=$region"
        val resp = Http.get("${ApiConst.ROLE_COMBAT_URL}?$query", recordHeaders(cookie, deviceId, deviceFp, query))
        return parseRoleCombat(resp.body)
    }

    // ---------- 原神游戏每日签到（luna，发游戏内邮件奖励） ----------

    private fun lunaHeaders(cookie: String, deviceId: String): MutableMap<String, String> {
        // 胡桃工具箱风格：XRpc 头 + x-rpc-signgame + LK2 盐的 Gen1 签名
        return mutableMapOf(
            "Accept" to "application/json",
            "User-Agent" to ApiConst.UA_DESKTOP,
            "x-rpc-app_version" to ApiConst.RECORD_VERSION,
            "x-rpc-client_type" to "5",
            "x-rpc-device_id" to deviceId,
            "x-rpc-signgame" to "hk4e",
            "DS" to DS.gen1(ApiConst.LK2_SALT),
            "Cookie" to cookie
        )
    }

    /** 查询原神每日签到状态（uid/region 必须与账号绑定一致）。 */
    fun fetchLunaInfo(cookie: String, deviceId: String, uid: String, region: String): LunaInfo {
        val url = "${ApiConst.TAKUMI_API}/event/luna/info?lang=zh-cn&act_id=${ApiConst.GENSHIN_ACT_ID}&uid=$uid&region=$region"
        val resp = Http.get(url, lunaHeaders(cookie, deviceId))
        return parseLunaInfo(resp.body)
    }

    /** 本月签到奖励日历：按"第几天"顺序返回每天能领什么。 */
    fun fetchLunaHome(cookie: String, deviceId: String): LunaHome {
        val url = "${ApiConst.TAKUMI_API}/event/luna/home?lang=zh-cn&act_id=${ApiConst.GENSHIN_ACT_ID}"
        val resp = Http.get(url, lunaHeaders(cookie, deviceId))
        return parseLunaHome(resp.body)
    }

    /** 原神每日签到（必要时带极验参数重试）。 */
    fun signLuna(
        cookie: String,
        deviceId: String,
        uid: String,
        region: String,
        challenge: String? = null,
        validate: String? = null,
        seccode: String? = null
    ): LunaSignResult {
        val body = JSONObject().apply {
            put("act_id", ApiConst.GENSHIN_ACT_ID)
            put("region", region)
            put("uid", uid)
        }.toString()
        val headers = lunaHeaders(cookie, deviceId)
        if (!challenge.isNullOrBlank()) {
            headers["x-rpc-challenge"] = challenge
            headers["x-rpc-validate"] = validate ?: ""
            headers["x-rpc-seccode"] = seccode ?: "${validate ?: ""}|jordan"
        }
        val resp = Http.post("${ApiConst.TAKUMI_API}/event/luna/sign", body, headers)
        return parseLunaSign(resp.body)
    }
}
