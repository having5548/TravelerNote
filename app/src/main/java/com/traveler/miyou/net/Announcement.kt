// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 游戏公告（与米哈游启动器同一份数据）。
 *
 * 对齐胡桃工具箱（Snap.Hutao）的 `AnnouncementService`：
 * 1. `getAnnList` 拿公告分组与条目（标题 / 副标题 / 横幅 / 起止时间 / 标签）；
 * 2. `getAnnContent` 拿公告正文 HTML，按 `ann_id` 合并进列表。
 *
 * 两个接口都是普通 GET，不需要登录、不需要 DS 签名，只需要带 UA。
 * 正文在应用内用 WebView 打开（见 ui/AnnouncementActivity.kt），不跳外部浏览器。
 */
data class NewsNotice(
    val annId: Int,
    val title: String,
    val subtitle: String,
    val banner: String,
    val typeLabel: String,
    val tagLabel: String,
    /** 1 = 活动公告（有起止时间）/ 其它为游戏公告、版本信息等。 */
    val type: Int,
    val startMs: Long,
    val endMs: Long,
    val content: String
)

data class AnnouncementGroup(
    val typeId: Int,
    val typeLabel: String,
    val notices: List<NewsNotice>
)

data class AnnouncementResult(
    val groups: List<AnnouncementGroup>,
    val retcode: Int,
    val message: String
) {
    val ok: Boolean get() = retcode == 0
    val total: Int get() = groups.sumOf { it.notices.size }
}

/**
 * 清掉正文里被转义的时间标签（形如 `&lt;t class="t_gl"&gt;7天&lt;/t&gt;`），只保留文字。
 *
 * 正则在函数内编译并用 `Throwable` 兜底：Android 的 `java.util.regex` 与桌面 JVM 并不完全一致，
 * 万一哪台设备上编译不过，也只是显示原文，绝不会因为这个把应用搞崩
 * （放在顶层 val 里的话，类初始化失败会抛 `ExceptionInInitializerError`，那是 Error，接不住的）。
 */
fun cleanNoticeHtml(html: String): String {
    if (html.isBlank()) return html
    return try {
        Regex(
            "&lt;t class=\"t_(?:gl|lc)\".*?&gt;(?:<span .*?>)?(.*?)(?:</span>)?&lt;/t&gt;",
            RegexOption.DOT_MATCHES_ALL
        ).replace(html) { it.groupValues[1] }
    } catch (t: Throwable) {
        html
    }
}

private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

private fun parseTime(raw: String): Long = try {
    if (raw.isBlank()) 0L else TIME_FORMAT.parse(raw)?.time ?: 0L
} catch (e: Exception) {
    0L
}

private fun cleanSubtitle(raw: String): String =
    raw.replace("\r<br>", "").replace("<br />", "").replace("\n", "").trim()

private fun mergeContent(contentMap: Map<Int, String>, typeId: Int, typeLabel: String, arr: JSONArray): AnnouncementGroup {
    val notices = ArrayList<NewsNotice>(arr.length())
    for (i in 0 until arr.length()) {
        val a = arr.optJSONObject(i) ?: continue
        val annId = a.optInt("ann_id", 0)
        val title = a.optString("title", "")
        if (title.isBlank()) continue
        val html = contentMap[annId].orEmpty()
        notices.add(
            NewsNotice(
                annId = annId,
                title = title,
                subtitle = cleanSubtitle(a.optString("subtitle", "")),
                banner = a.optString("banner", ""),
                typeLabel = a.optString("type_label", typeLabel),
                tagLabel = a.optString("tag_label", ""),
                type = a.optInt("type", 0),
                startMs = parseTime(a.optString("start_time", "")),
                endMs = parseTime(a.optString("end_time", "")),
                content = cleanNoticeHtml(html)
            )
        )
    }
    return AnnouncementGroup(typeId, typeLabel, notices)
}

fun parseAnnouncementList(raw: String): List<AnnouncementGroup> {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return emptyList()
        val arr = obj.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val groups = ArrayList<AnnouncementGroup>(arr.length())
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val typeId = g.optInt("type_id", i + 1)
            val typeLabel = g.optString("type_label", "")
            val list = g.optJSONArray("list") ?: continue
            val group = mergeContent(emptyMap(), typeId, typeLabel, list)
            if (group.notices.isNotEmpty()) groups.add(group)
        }
        groups
    } catch (e: Exception) {
        emptyList()
    }
}

fun parseAnnouncementContent(raw: String): Map<Int, String> {
    return try {
        val obj = JSONObject(raw)
        if (obj.optInt("retcode", -1) != 0) return emptyMap()
        val arr = obj.optJSONObject("data")?.optJSONArray("list") ?: return emptyMap()
        val map = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optInt("ann_id", 0)
            val content = c.optString("content", "")
            if (id != 0 && content.isNotBlank()) map[id] = content
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}

/** 拉取公告列表；正文按需再取（见 [fetchAnnouncementContents]），避免每次进页签都下 200 KB HTML。 */
fun fetchAnnouncements(region: String): AnnouncementResult {
    return try {
        val listResp = Http.get(ApiConst.annListUrl(region), announcementHeaders())
        val listObj = JSONObject(listResp.body)
        val retcode = listObj.optInt("retcode", -1)
        if (retcode != 0) {
            return AnnouncementResult(emptyList(), retcode, listObj.optString("message", ""))
        }

        val groups = parseAnnouncementList(listResp.body)
        if (groups.isEmpty()) return AnnouncementResult(emptyList(), 0, "")

        // 活动公告（type_id = 1）排最前，其次游戏公告（2），其余保持官方顺序；
        // 活动公告内部按截止时间升序，快结束的排前面
        val sorted = groups.sortedBy { g ->
            when (g.typeId) {
                1 -> 0
                2 -> 1
                else -> 2
            }
        }.map { g ->
            if (g.typeId == 1) {
                g.copy(notices = g.notices.sortedBy { if (it.endMs == 0L) Long.MAX_VALUE else it.endMs })
            } else {
                g
            }
        }
        AnnouncementResult(sorted, 0, "")
    } catch (e: Exception) {
        AnnouncementResult(emptyList(), -1, e.message ?: "网络错误")
    }
}

/** 公告正文缓存：进详情时才拉，30 分钟内复用。 */
@Volatile
private var noticeCache: Map<Int, String>? = null

@Volatile
private var noticeCacheAt = 0L

/** 拉取全部公告正文并按 ann_id 索引（带 30 分钟内存缓存）。 */
fun fetchAnnouncementContents(region: String): Map<Int, String> {
    val cached = noticeCache
    if (cached != null && System.currentTimeMillis() - noticeCacheAt < 30 * 60_000L) {
        return cached
    }
    return try {
        val map = parseAnnouncementContent(
            Http.get(ApiConst.annContentUrl(region), announcementHeaders()).body
        )
        if (map.isNotEmpty()) {
            noticeCache = map
            noticeCacheAt = System.currentTimeMillis()
        }
        map
    } catch (e: Exception) {
        cached ?: emptyMap()
    }
}

private fun announcementHeaders(): Map<String, String> = mapOf(
    "User-Agent" to ApiConst.UA_DESKTOP,
    "Accept" to "application/json"
)
