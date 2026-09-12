// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「我的角色」的官方数据源。
 *
 * 对齐 Snap.Hutao（胡桃工具箱）的 GameRecordClient：
 * 1. `character/list`（POST）拿角色列表（等级、命座、好感、武器）；
 * 2. `character/detail`（POST，body 带 character_ids）拿**面板属性**
 *    （selected_properties）、**圣遗物详情**（relics，含主副词条）与天赋等级。
 *
 * 两处签名都是 X4 盐 Gen2，且 **body 也要参与签名**（`&b=<body>&q=<排序后的 query>`）。
 */
data class OfficialCharacter(
    val id: Int,
    val name: String,
    val element: Int,
    val level: Int,
    val rarity: Int,
    val constellation: Int,
    val fetter: Int,
    val image: String,
    val weaponName: String,
    val weaponLevel: Int,
    val weaponRarity: Int,
    val weaponAffix: Int,
    val reliquaryNames: List<String>,
    /** 面板属性，来自 character/detail。 */
    val properties: List<CharProperty> = emptyList(),
    /** 圣遗物详情，来自 character/detail。 */
    val relics: List<RelicInfo> = emptyList(),
    /** 天赋等级（普攻 / 战技 / 爆发），来自 character/detail。 */
    val skills: List<SkillLevel> = emptyList()
)

/**
 * 面板属性一条：[base] 基础值、[add] 加成值、[total] 总值。
 * miHoYo 返回的已经是可直接显示的字符串（百分比也带好了量纲），不要自己再乘 100。
 */
data class CharProperty(
    val type: Int,
    val name: String,
    val base: String,
    val add: String,
    val total: String
)

/** 圣遗物词条（主词条或副词条）。 */
data class RelicProperty(val type: Int, val name: String, val value: String)

/** 一件圣遗物。 */
data class RelicInfo(
    val pos: Int,
    val posName: String,
    val setName: String,
    val name: String,
    val rarity: Int,
    val level: Int,
    val main: RelicProperty?,
    val subs: List<RelicProperty>
)

/** 天赋等级：skill_type 1 / 2 / 3 → 普通攻击 / 元素战技 / 元素爆发。 */
data class SkillLevel(val name: String, val level: Int)

/**
 * 角色列表请求结果。
 *
 * 保留 retcode / message 是刻意的：release 包做了混淆且 [android.util.Log] 被裁剪，
 * 一旦失败只能把服务端的真实原因显示到界面上，否则用户只会看到"暂无展示角色"。
 */
data class CharacterListResult(
    val characters: List<OfficialCharacter>,
    val retcode: Int,
    val message: String
) {
    val ok: Boolean get() = retcode == 0

    /** 1034：需要人机验证，走 [CardVerification] 过验证后带 x-rpc-challenge 重放。 */
    val needVerification: Boolean get() = retcode == 1034
}

/** 角色详情（面板 / 圣遗物 / 天赋）请求结果，按角色 id 索引。 */
data class CharacterDetailResult(
    val details: Map<Int, CharacterDetail>,
    val retcode: Int,
    val message: String
) {
    val ok: Boolean get() = retcode == 0
    val needVerification: Boolean get() = retcode == 1034
}

data class CharacterDetail(
    val properties: List<CharProperty>,
    val relics: List<RelicInfo>,
    val skills: List<SkillLevel>
)

/** 元素 id → 中文名（官方接口里 element 是数字）。 */
fun elementName(element: Int): String = when (element) {
    1 -> "风"
    2 -> "岩"
    3 -> "雷"
    4 -> "草"
    5 -> "水"
    6 -> "火"
    7 -> "冰"
    else -> ""
}

/**
 * 属性 id → 中文名。沿用胡桃工具箱 FightProperty 的本地化文案（与游戏内一致）：
 * 百分比类词条的名词与"小词条"共用同一个名字，量纲由接口返回的字符串自带。
 */
fun fightPropertyName(type: Int): String = when (type) {
    1 -> "基础生命值"
    2, 3, 2000 -> "生命值"
    4 -> "基础攻击力"
    5, 6, 2001 -> "攻击力"
    7 -> "基础防御力"
    8, 9, 2002 -> "防御力"
    20 -> "暴击率"
    21 -> "抗暴击率"
    22 -> "暴击伤害"
    23 -> "元素充能效率"
    24 -> "伤害加成"
    26 -> "治疗加成"
    27 -> "受治疗加成"
    28, 3006 -> "元素精通"
    29 -> "物理抗性"
    30, 3024 -> "物理伤害加成"
    40 -> "火元素伤害加成"
    41 -> "雷元素伤害加成"
    42 -> "水元素伤害加成"
    43 -> "草元素伤害加成"
    44 -> "风元素伤害加成"
    45 -> "岩元素伤害加成"
    46 -> "冰元素伤害加成"
    50 -> "火元素抗性"
    51 -> "雷元素抗性"
    52 -> "水元素抗性"
    53 -> "草元素抗性"
    54 -> "风元素抗性"
    55 -> "岩元素抗性"
    56 -> "冰元素抗性"
    80 -> "冷却缩减"
    else -> ""
}

/** 圣遗物部位：1 生之花 / 2 死之羽 / 3 时之沙 / 4 空之杯 / 5 理之冠。 */
fun relicPosName(pos: Int, fromApi: String): String = when {
    fromApi.isNotBlank() -> fromApi
    pos == 1 -> "生之花"
    pos == 2 -> "死之羽"
    pos == 3 -> "时之沙"
    pos == 4 -> "空之杯"
    pos == 5 -> "理之冠"
    else -> ""
}

private fun parseProperties(arr: JSONArray?): List<CharProperty> {
    if (arr == null) return emptyList()
    val out = ArrayList<CharProperty>(arr.length())
    for (i in 0 until arr.length()) {
        val p = arr.optJSONObject(i) ?: continue
        val type = p.optInt("property_type", 0)
        val name = fightPropertyName(type)
        if (name.isBlank()) continue
        out.add(
            CharProperty(
                type = type,
                name = name,
                base = p.optString("base", ""),
                add = p.optString("add", ""),
                // "final" 是 Kotlin 关键字，属性名退一步叫 total
                total = p.optString("final", "")
            )
        )
    }
    return out
}

private fun parseRelicProperty(obj: JSONObject?): RelicProperty? {
    if (obj == null) return null
    val type = obj.optInt("property_type", 0)
    val name = fightPropertyName(type)
    val value = obj.optString("value", "")
    if (name.isBlank() || value.isBlank()) return null
    return RelicProperty(type, name, value)
}

private fun parseRelics(arr: JSONArray?): List<RelicInfo> {
    if (arr == null) return emptyList()
    val out = ArrayList<RelicInfo>(arr.length())
    for (i in 0 until arr.length()) {
        val r = arr.optJSONObject(i) ?: continue
        val subs = ArrayList<RelicProperty>()
        r.optJSONArray("sub_property_list")?.let { list ->
            for (j in 0 until list.length()) {
                parseRelicProperty(list.optJSONObject(j))?.let { subs.add(it) }
            }
        }
        val pos = r.optInt("pos", 0)
        out.add(
            RelicInfo(
                pos = pos,
                posName = relicPosName(pos, r.optString("pos_name", "")),
                setName = r.optJSONObject("set")?.optString("name", "") ?: "",
                name = r.optString("name", ""),
                rarity = r.optInt("rarity", 0),
                level = r.optInt("level", 0),
                main = parseRelicProperty(r.optJSONObject("main_property")),
                subs = subs
            )
        )
    }
    return out.sortedBy { it.pos }
}

private fun parseSkills(arr: JSONArray?): List<SkillLevel> {
    if (arr == null) return emptyList()
    val out = ArrayList<SkillLevel>(3)
    for (i in 0 until arr.length()) {
        val s = arr.optJSONObject(i) ?: continue
        val name = when (s.optInt("skill_type", 0)) {
            1 -> "普通攻击"
            2 -> "元素战技"
            3 -> "元素爆发"
            else -> continue
        }
        out.add(SkillLevel(name, s.optInt("level", 0)))
    }
    return out
}

fun parseCharacterList(raw: String): CharacterListResult {
    return try {
        val obj = JSONObject(raw)
        val retcode = obj.optInt("retcode", -1)
        val message = obj.optString("message", "")
        if (retcode != 0) return CharacterListResult(emptyList(), retcode, message)

        val arr = obj.optJSONObject("data")?.optJSONArray("list")
            ?: return CharacterListResult(emptyList(), 0, message)

        val list = ArrayList<OfficialCharacter>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val weapon = c.optJSONObject("weapon")
            val reliquaries = ArrayList<String>()
            c.optJSONArray("reliquaries")?.let { rs ->
                for (j in 0 until rs.length()) {
                    val r = rs.optJSONObject(j) ?: continue
                    val setName = r.optJSONObject("set")?.optString("name", "") ?: ""
                    val name = r.optString("name", "")
                    val label = if (setName.isNotBlank()) setName else name
                    if (label.isNotBlank()) reliquaries.add(label)
                }
            }
            list.add(
                OfficialCharacter(
                    id = c.optInt("id", 0),
                    name = c.optString("name", ""),
                    element = c.optInt("element", 0),
                    level = c.optInt("level", 0),
                    rarity = c.optInt("rarity", 0),
                    constellation = c.optInt("actived_constellation_num", 0),
                    fetter = c.optInt("fetter", 0),
                    image = c.optString("image", ""),
                    weaponName = weapon?.optString("name", "") ?: "",
                    weaponLevel = weapon?.optInt("level", 0) ?: 0,
                    weaponRarity = weapon?.optInt("rarity", 0) ?: 0,
                    weaponAffix = weapon?.optInt("affix_level", 0) ?: 0,
                    reliquaryNames = reliquaries
                )
            )
        }
        CharacterListResult(list, 0, message)
    } catch (e: Exception) {
        CharacterListResult(emptyList(), -1, "响应解析失败")
    }
}

/** 按角色 id 合并详情（面板 / 圣遗物 / 天赋）。 */
fun mergeCharacterDetails(
    characters: List<OfficialCharacter>,
    details: Map<Int, CharacterDetail>
): List<OfficialCharacter> = characters.map { c ->
    val d = details[c.id] ?: return@map c
    c.copy(properties = d.properties, relics = d.relics, skills = d.skills)
}

/**
 * 拉取角色列表；query 为空，签名只带 body。
 * [challenge] 为 1034 人机验证换来的 x-rpc-challenge，首次请求传 null。
 */
fun fetchCharacterList(
    cookie: String,
    deviceId: String,
    deviceFp: String,
    uid: String,
    region: String,
    challenge: String? = null
): CharacterListResult {
    val body = JSONObject().apply {
        put("role_id", uid)
        put("server", region)
    }.toString()

    val headers = MiyouApi.recordHeaders(cookie, deviceId, deviceFp, "", body)
    if (!challenge.isNullOrBlank()) headers["x-rpc-challenge"] = challenge

    val resp = Http.post(ApiConst.CHARACTER_LIST_URL, body, headers)
    return parseCharacterList(resp.body)
}

/**
 * 拉取角色详情（面板属性 + 圣遗物 + 天赋）。一次请求可以带全部角色 id
 * （胡桃工具箱也是把所有 id 塞进一个请求里）。
 */
fun fetchCharacterDetail(
    cookie: String,
    deviceId: String,
    deviceFp: String,
    uid: String,
    region: String,
    ids: List<Int>,
    challenge: String? = null
): CharacterDetailResult {
    if (ids.isEmpty()) return CharacterDetailResult(emptyMap(), 0, "")

    val body = JSONObject().apply {
        put("role_id", uid)
        put("server", region)
        put("character_ids", JSONArray().apply { ids.forEach { put(it) } })
    }.toString()

    val headers = MiyouApi.recordHeaders(cookie, deviceId, deviceFp, "", body)
    if (!challenge.isNullOrBlank()) headers["x-rpc-challenge"] = challenge

    val resp = Http.post(ApiConst.CHARACTER_DETAIL_URL, body, headers)
    return parseCharacterDetail(resp.body)
}

fun parseCharacterDetail(raw: String): CharacterDetailResult {
    return try {
        val obj = JSONObject(raw)
        val retcode = obj.optInt("retcode", -1)
        val message = obj.optString("message", "")
        if (retcode != 0) return CharacterDetailResult(emptyMap(), retcode, message)

        val arr = obj.optJSONObject("data")?.optJSONArray("list")
            ?: return CharacterDetailResult(emptyMap(), 0, message)

        val map = LinkedHashMap<Int, CharacterDetail>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optJSONObject("base")?.optInt("id", 0) ?: 0
            if (id == 0) continue
            map[id] = CharacterDetail(
                properties = parseProperties(c.optJSONArray("selected_properties")),
                relics = parseRelics(c.optJSONArray("relics")),
                skills = parseSkills(c.optJSONArray("skills"))
            )
        }
        CharacterDetailResult(map, 0, message)
    } catch (e: Exception) {
        CharacterDetailResult(emptyMap(), -1, "响应解析失败")
    }
}
