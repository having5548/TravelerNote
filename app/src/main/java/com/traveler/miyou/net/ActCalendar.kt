// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏内活动日历 + UP 池。
 *
 * 对齐胡桃工具箱（Snap.Hutao）的 `GameRecordClient.GetActCalendarAsync`：
 * POST `game_record/app/genshin/api/act_calendar`，body 为 `{"role_id":..,"server":..}`，
 * X4 盐 Gen2 且 body 参与签名；响应里
 * `act_list` / `fixed_act_list` 是活动，`avatar_card_pool_list` / `weapon_card_pool_list` /
 * `mixed_card_pool_list` 是 UP 池。
 */
data class NewsAct(
    val id: Int,
    val name: String,
    val desc: String,
    val startMs: Long,
    val endMs: Long,
    /** 1 未开始 / 2 进行中。 */
    val status: Int,
    val countdownSeconds: Int,
    val rewards: List<String>,
    val strategy: String
)

data class PoolItem(val name: String, val icon: String, val rarity: Int)

data class NewsCardPool(
    val poolId: Int,
    val poolName: String,
    val versionName: String,
    /** 1 角色活动祈愿 / 2 武器活动祈愿 / 3 集录祈愿。 */
    val poolType: Int,
    val startMs: Long,
    val endMs: Long,
    /** 1 未开始 / 2 进行中。 */
    val poolStatus: Int,
    val countdownSeconds: Int,
    val items: List<PoolItem>
)

data class ActCalendarResult(
    val acts: List<NewsAct>,
    val pools: List<NewsCardPool>,
    val retcode: Int,
    val message: String
) {
    val ok: Boolean get() = retcode == 0
    val needVerification: Boolean get() = retcode == 1034
}

fun cardPoolTypeName(type: Int): String = when (type) {
    1 -> "角色活动祈愿"
    2 -> "武器活动祈愿"
    3 -> "集录祈愿"
    else -> "祈愿"
}

private fun parseRewards(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val r = arr.optJSONObject(i) ?: continue
        val name = r.optString("name", "")
        if (name.isBlank()) continue
        val num = r.optInt("num", 0)
        out.add(if (num > 1) "$name×$num" else name)
    }
    return out
}

private fun parseAct(obj: JSONObject): NewsAct? {
    val name = obj.optString("name", "")
    if (name.isBlank()) return null
    return NewsAct(
        id = obj.optInt("id", 0),
        name = name,
        desc = obj.optString("desc", ""),
        startMs = obj.optLong("start_timestamp", 0L) * 1000L,
        endMs = obj.optLong("end_timestamp", 0L) * 1000L,
        status = obj.optInt("status", 0),
        countdownSeconds = obj.optInt("countdown_seconds", 0),
        rewards = parseRewards(obj.optJSONArray("reward_list")),
        strategy = obj.optString("strategy", "")
    )
}

private fun parseActs(src: JSONObject): List<NewsAct> {
    val out = ArrayList<NewsAct>()
    for (key in arrayOf("act_list", "fixed_act_list", "selected_act_list")) {
        val arr = src.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            parseAct(a)?.let { act -> if (out.none { it.id == act.id }) out.add(act) }
        }
    }
    return out
}

private fun parsePools(arr: JSONArray?, type: Int): List<NewsCardPool> {
    if (arr == null) return emptyList()
    val out = ArrayList<NewsCardPool>(arr.length())
    for (i in 0 until arr.length()) {
        val p = arr.optJSONObject(i) ?: continue
        val name = p.optString("pool_name", "")
        if (name.isBlank()) continue
        val items = ArrayList<PoolItem>()
        for (key in arrayOf("avatars", "weapon")) {
            val list = p.optJSONArray(key) ?: continue
            for (j in 0 until list.length()) {
                val it = list.optJSONObject(j) ?: continue
                val itemName = it.optString("name", "")
                if (itemName.isBlank()) continue
                items.add(
                    PoolItem(
                        name = itemName,
                        icon = it.optString("icon", ""),
                        rarity = it.optInt("rarity", 0)
                    )
                )
            }
        }
        out.add(
            NewsCardPool(
                poolId = p.optInt("pool_id", 0),
                poolName = name,
                versionName = p.optString("version_name", ""),
                poolType = if (p.optInt("pool_type", 0) != 0) p.optInt("pool_type", type) else type,
                startMs = p.optLong("start_timestamp", 0L) * 1000L,
                endMs = p.optLong("end_timestamp", 0L) * 1000L,
                poolStatus = p.optInt("pool_status", 0),
                countdownSeconds = p.optInt("countdown_seconds", 0),
                items = items
            )
        )
    }
    return out
}

fun parseActCalendar(raw: String): ActCalendarResult {
    return try {
        val obj = JSONObject(raw)
        val retcode = obj.optInt("retcode", -1)
        val message = obj.optString("message", "")
        if (retcode != 0) return ActCalendarResult(emptyList(), emptyList(), retcode, message)

        val data = obj.optJSONObject("data")
            ?: return ActCalendarResult(emptyList(), emptyList(), 0, message)

        val acts = parseActs(data)
        val pools = ArrayList<NewsCardPool>()
        pools.addAll(parsePools(data.optJSONArray("avatar_card_pool_list"), 1))
        pools.addAll(parsePools(data.optJSONArray("weapon_card_pool_list"), 2))
        pools.addAll(parsePools(data.optJSONArray("mixed_card_pool_list"), 3))
        ActCalendarResult(acts, pools, 0, message)
    } catch (e: Exception) {
        ActCalendarResult(emptyList(), emptyList(), -1, "响应解析失败")
    }
}

/** 拉取活动日历；[challenge] 为 1034 人机验证换来的 x-rpc-challenge。 */
fun fetchActCalendar(
    cookie: String,
    deviceId: String,
    deviceFp: String,
    uid: String,
    region: String,
    challenge: String? = null
): ActCalendarResult {
    val body = JSONObject().apply {
        put("role_id", uid)
        put("server", region)
    }.toString()

    val headers = MiyouApi.recordHeaders(cookie, deviceId, deviceFp, "", body)
    if (!challenge.isNullOrBlank()) headers["x-rpc-challenge"] = challenge

    val resp = Http.post(ApiConst.ACT_CALENDAR_URL, body, headers)
    return parseActCalendar(resp.body)
}
