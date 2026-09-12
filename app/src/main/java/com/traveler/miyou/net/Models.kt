package com.traveler.miyou.net

import org.json.JSONObject

/** 原神游戏角色。 */
data class GameRole(
    val uid: String,
    val region: String,
    val nickname: String,
    val level: Int
)

/** 派遣探索。 */
data class Expedition(
    val avatarSideIcon: String,
    val status: String,
    val remainedTime: Int
)

/** 质变仪恢复时间。 */
data class RecoveryTime(
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val reached: Boolean
)

/** 质变仪。 */
data class Transformer(
    val obtained: Boolean,
    val recoveryTime: RecoveryTime?
)

/** 原神实时便签。 */
data class DailyNote(
    val currentResin: Int,
    val maxResin: Int,
    val resinRecoveryTime: Int,
    val finishedTaskNum: Int,
    val totalTaskNum: Int,
    val isExtraTaskRewardReceived: Boolean,
    val currentExpeditionNum: Int,
    val maxExpeditionNum: Int,
    val expeditions: List<Expedition>,
    val currentHomeCoin: Int,
    val maxHomeCoin: Int,
    val homeCoinRecoveryTime: Int,
    val remainResinDiscountNum: Int,
    val resinDiscountNumLimit: Int,
    val transformer: Transformer?
)

/** 二维码登录票据。 */
data class QrTicket(val url: String, val ticket: String)

/** 二维码登录状态结果。 */
data class QrStatus(
    val status: String,
    val stoken: String,
    val mid: String,
    val stuid: String
)

/** 短信验证码发送结果。 */
data class CaptchaSent(val actionType: String, val countdown: String, val sentNew: String)

/** 登录凭证（stoken 体系）。 */
data class LoginTokens(val stoken: String, val mid: String, val stuid: String)

/** AIGIS 人机验证信息。 */
data class AigisInfo(val sessionId: String, val gt: String, val challenge: String, val riskType: String)

fun parseQrTicket(raw: String): QrTicket? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        QrTicket(d.optString("url", ""), d.optString("ticket", ""))
    } catch (e: Exception) {
        null
    }
}

fun parseQrStatus(raw: String): QrStatus? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        val userInfo = d.optJSONObject("user_info")
        val tokens = d.optJSONArray("tokens")
        var stoken = ""
        if (tokens != null && tokens.length() > 0) {
            for (i in 0 until tokens.length()) {
                val t = tokens.optJSONObject(i) ?: continue
                if (t.optInt("token_type", -1) == 1) {
                    stoken = t.optString("token", "")
                    break
                }
            }
        }
        if (stoken.isEmpty() && tokens != null && tokens.length() > 0) {
            stoken = tokens.optJSONObject(0)?.optString("token", "") ?: ""
        }
        QrStatus(
            status = d.optString("status", ""),
            stoken = stoken,
            mid = userInfo?.optString("mid", "") ?: "",
            stuid = userInfo?.optString("aid", "") ?: ""
        )
    } catch (e: Exception) {
        null
    }
}

fun parseCaptchaSent(raw: String): CaptchaSent? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        CaptchaSent(
            actionType = d.optString("action_type", ""),
            countdown = d.optString("countdown", ""),
            sentNew = d.optString("sent_new", "")
        )
    } catch (e: Exception) {
        null
    }
}

fun parseLoginTokens(raw: String): LoginTokens? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        val userInfo = d.optJSONObject("user_info")
        val token = d.optJSONObject("token")
        LoginTokens(
            stoken = token?.optString("token", "") ?: "",
            mid = userInfo?.optString("mid", "") ?: "",
            stuid = userInfo?.optString("aid", "") ?: ""
        )
    } catch (e: Exception) {
        null
    }
}

/** 解析接口返回的 x-rpc-aigis 头（JSON 字符串）。 */
fun parseAigis(aigis: String): AigisInfo? {
    return try {
        val outer = JSONObject(aigis)
        val sessionId = outer.optString("session_id", "")
        val dataRaw = outer.optString("data", "")
        val data = if (dataRaw.isNotEmpty()) JSONObject(dataRaw) else outer.optJSONObject("data")
        AigisInfo(
            sessionId = sessionId,
            gt = data?.optString("gt", "") ?: "",
            challenge = data?.optString("challenge", "") ?: "",
            riskType = data?.optString("risk_type", "") ?: ""
        )
    } catch (e: Exception) {
        null
    }
}

fun parseGameRoles(raw: String): List<GameRole> {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val list = ArrayList<GameRole>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            list.add(
                GameRole(
                    uid = it.optString("game_uid", ""),
                    region = it.optString("region", ""),
                    nickname = it.optString("nickname", ""),
                    level = it.optInt("level", 0)
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

/** widget v2 返回：树脂/家园/派遣/委托 + 是否已签到。 */
data class WidgetResult(val note: DailyNote?, val hasSigned: Boolean, val message: String)

fun parseWidgetResult(raw: String): WidgetResult {
    return try {
        val obj = JSONObject(raw)
        val retcode = obj.optInt("retcode", -1)
        val message = obj.optString("message", "")
        if (retcode != 0) return WidgetResult(null, false, "retcode=$retcode $message".trim())
        val d = obj.optJSONObject("data") ?: return WidgetResult(null, false, "无数据")
        WidgetResult(parseDailyNoteData(d), d.optBoolean("has_signed", false), message)
    } catch (e: Exception) {
        WidgetResult(null, false, "解析失败")
    }
}

private fun parseDailyNoteData(d: JSONObject): DailyNote {
    val expeditions = ArrayList<Expedition>()
        d.optJSONArray("expeditions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                expeditions.add(
                    Expedition(
                        avatarSideIcon = e.optString("avatar_side_icon", ""),
                        status = e.optString("status", ""),
                        remainedTime = e.optInt("remained_time", 0)
                    )
                )
            }
        }

        val transformerObj = d.optJSONObject("transformer")
        val transformer = transformerObj?.let { t ->
            val rtObj = t.optJSONObject("recovery_time")
            val rt = rtObj?.let { r ->
                RecoveryTime(
                    day = r.optInt("Day", 0),
                    hour = r.optInt("Hour", 0),
                    minute = r.optInt("Minute", 0),
                    second = r.optInt("Second", 0),
                    reached = r.optBoolean("reached", false)
                )
            }
            Transformer(
                obtained = t.optBoolean("obtained", false),
                recoveryTime = rt
            )
        }

        return DailyNote(
            currentResin = d.optInt("current_resin", 0),
            maxResin = d.optInt("max_resin", 0),
            resinRecoveryTime = d.optInt("resin_recovery_time", 0),
            finishedTaskNum = d.optInt("finished_task_num", 0),
            totalTaskNum = d.optInt("total_task_num", 0),
            isExtraTaskRewardReceived = d.optBoolean("is_extra_task_reward_received", false),
            currentExpeditionNum = d.optInt("current_expedition_num", 0),
            maxExpeditionNum = d.optInt("max_expedition_num", 0),
            expeditions = expeditions,
            currentHomeCoin = d.optInt("current_home_coin", 0),
            maxHomeCoin = d.optInt("max_home_coin", 0),
            homeCoinRecoveryTime = d.optInt("home_coin_recovery_time", 0),
            remainResinDiscountNum = d.optInt("remain_resin_discount_num", 0),
            resinDiscountNumLimit = d.optInt("resin_discount_num_limit", 0),
            transformer = transformer
        )
}

/** 解析 getMultiTokenByLoginTicket 回包中的 token_type -> token 映射。 */
fun parseTokens(raw: String): Map<Int, String> {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return emptyMap()
        val arr = obj.optJSONObject("data")?.optJSONArray("list") ?: return emptyMap()
        val map = HashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val type = it.optInt("token_type", -1)
            val token = it.optString("token", "")
            if (type >= 0 && token.isNotEmpty()) map[type] = token
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}

/** 解析 getCookieAccountInfoBySToken 回包。 */
data class CookieAccountInfo(val cookieToken: String, val accountId: String, val uid: String)

fun parseCookieAccountInfo(raw: String): CookieAccountInfo? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        CookieAccountInfo(
            cookieToken = d.optString("cookie_token", ""),
            accountId = d.optString("account_id", ""),
            uid = d.optString("uid", "")
        )
    } catch (e: Exception) {
        null
    }
}

// ---------- 提瓦特小助手（lelaer） ----------

data class LelaerChar(
    val name: String,
    val level: String,
    val roleImg: String,
    val weapon: String,
    val weaponLevel: String,
    val weaponClass: String,
    val artifacts: String,
    val hp: String,
    val attack: String,
    val crit: String,
    val critDmg: String,
    val recharge: String,
    val detail: String
)

fun parseLelaerCharacters(raw: String): List<LelaerChar> {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("code", -1) != 200) return emptyList()
        val arr = obj.optJSONObject("result")?.optJSONArray("role_data") ?: return emptyList()
        val list = ArrayList<LelaerChar>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val sb = StringBuilder()
            sb.append("武器：").append(it.optString("weapon", ""))
                .append("  Lv.").append(it.optString("weapon_level", ""))
                .append(' ').append(it.optString("weapon_class", ""))
            sb.append("\n生命值 ").append(it.optString("hp", ""))
                .append("  攻击力 ").append(it.optString("attack", ""))
                .append("  防御力 ").append(it.optString("defend", ""))
            sb.append("\n暴击率 ").append(it.optString("crit", ""))
                .append("  暴击伤害 ").append(it.optString("crit_dmg", ""))
                .append("  元素充能 ").append(it.optString("recharge", ""))
            sb.append("\n元素精通 ").append(it.optString("mastery", it.optString("element_mastery", "")))
            it.optJSONArray("artifacts_detail")?.let { arr2 ->
                for (j in 0 until arr2.length()) {
                    val a = arr2.optJSONObject(j) ?: continue
                    sb.append("\n\n【").append(a.optString("artifacts_name", ""))
                        .append("】").append(a.optString("artifacts_type", ""))
                        .append(" +").append(a.optString("level", ""))
                    sb.append("\n主词条 ").append(a.optString("maintips", ""))
                        .append(' ').append(a.optString("mainvalue", ""))
                    for (k in 1..4) {
                        val t = a.optString("tips$k", "")
                        if (t.isNotBlank()) sb.append("\n").append(t)
                    }
                }
            }
            list.add(
                LelaerChar(
                    name = it.optString("role", ""),
                    level = it.optString("level", ""),
                    roleImg = it.optString("role_img", ""),
                    weapon = it.optString("weapon", ""),
                    weaponLevel = it.optString("weapon_level", ""),
                    weaponClass = it.optString("weapon_class", ""),
                    artifacts = it.optString("artifacts", ""),
                    hp = it.optString("hp", ""),
                    attack = it.optString("attack", ""),
                    crit = it.optString("crit", ""),
                    critDmg = it.optString("crit_dmg", ""),
                    recharge = it.optString("recharge", ""),
                    detail = sb.toString()
                )
            )
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

// ---------- 原神游戏每日签到（luna） ----------

data class LunaInfo(
    val isSign: Boolean,
    val totalSignDay: Int,
    val today: String,
    val firstBind: Boolean,
    val message: String
)

fun parseLunaInfo(raw: String): LunaInfo {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) {
            return LunaInfo(false, 0, "", false, "retcode=${obj.optInt("retcode")} ${obj.optString("message")}".trim())
        }
        val d = obj.optJSONObject("data") ?: return LunaInfo(false, 0, "", false, "无数据")
        LunaInfo(
            isSign = d.optBoolean("is_sign", false),
            totalSignDay = d.optInt("total_sign_day", 0),
            today = d.optString("today", ""),
            firstBind = d.optBoolean("first_bind", false),
            message = ""
        )
    } catch (e: Exception) {
        LunaInfo(false, 0, "", false, "解析失败")
    }
}

data class LunaSignResult(
    val ok: Boolean,
    val already: Boolean,
    val needCaptcha: Boolean,
    val gt: String,
    val challenge: String,
    val message: String
)

fun parseLunaSign(raw: String): LunaSignResult {
    return try {
        val obj = JSONObject(raw)
        val retcode = obj.optInt("retcode", -1)
        val message = obj.optString("message", "")
        when {
            retcode == 0 -> {
                val d = obj.optJSONObject("data")
                val success = d?.optInt("success", 0) ?: 0
                if (success == 1) {
                    LunaSignResult(false, false, true, d?.optString("gt", "") ?: "", d?.optString("challenge", "") ?: "", message)
                } else {
                    LunaSignResult(true, false, false, "", "", message)
                }
            }
            retcode == -5003 -> LunaSignResult(true, true, false, "", "", message.ifBlank { "今日已签到" })
            else -> LunaSignResult(false, false, false, "", "", "retcode=$retcode $message".trim())
        }
    } catch (e: Exception) {
        LunaSignResult(false, false, false, "", "", "解析失败")
    }
}

// ---------- 原神签到奖励日历（event/luna/home） ----------

/** 单日签到奖励。 */
data class LunaAward(val icon: String, val name: String, val cnt: Int)

/** 本月签到奖励列表，按"第几天"顺序排列；message 只用于失败时的诊断提示。 */
data class LunaHome(val awards: List<LunaAward>, val message: String = "")

fun parseLunaHome(raw: String): LunaHome {
    return try {
        val obj = JSONObject(raw)
        val retcode = obj.optInt("retcode", -1)
        if (retcode != 0) {
            return LunaHome(emptyList(), "retcode=$retcode ${obj.optString("message", "")}".trim())
        }
        val arr = obj.optJSONObject("data")?.optJSONArray("awards")
            ?: return LunaHome(emptyList(), "响应缺少 awards 字段")
        val list = ArrayList<LunaAward>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            list.add(
                LunaAward(
                    icon = it.optString("icon", ""),
                    name = it.optString("name", ""),
                    cnt = it.optInt("cnt", 0)
                )
            )
        }
        LunaHome(list)
    } catch (e: Exception) {
        LunaHome(emptyList(), "解析失败")
    }
}

// ---------- 社区（论坛）签到 ----------

/** 通用接口回包。 */
data class ApiResp(val retcode: Int, val message: String, val raw: String)

/** 社区签到结果。 */
data class SignResult(
    val ok: Boolean,
    val alreadySigned: Boolean,
    val needCaptcha: Boolean,
    val message: String,
    val raw: String
)

/** 极验验证码初始化信息。 */
data class GeetestChallenge(val gt: String, val challenge: String)

/** 验证码校验结果：challenge 用于 x-rpc-challenge。 */
data class VerificationPass(val challenge: String)

fun parseApiResp(raw: String): ApiResp {
    return try {
        val obj = JSONObject(raw)
        ApiResp(
            retcode = obj.optInt("retcode", -1),
            message = obj.optString("message", ""),
            raw = raw
        )
    } catch (e: Exception) {
        ApiResp(-1, "响应解析失败", raw)
    }
}

fun parseGeetestChallenge(raw: String): GeetestChallenge? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        GeetestChallenge(
            gt = d.optString("gt", ""),
            challenge = d.optString("challenge", "")
        )
    } catch (e: Exception) {
        null
    }
}

fun parseVerificationPass(raw: String): VerificationPass? {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return null
        val d = obj.optJSONObject("data") ?: return null
        VerificationPass(d.optString("challenge", ""))
    } catch (e: Exception) {
        null
    }
}
