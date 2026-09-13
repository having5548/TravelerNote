// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * 原神官方 B 站账号的内容。
 *
 * B 站把空间动态接口（`x/polymer/web-dynamic/v1/feed/space`）加上了风控：
 * 不带登录态直接请求会返回 **-412 request was banned**，加上 `buvid3` / `bili_ticket`
 * 与 wbi 签名也一样（实测）。所以这里分两条路：
 *
 * 1. **官方投稿 + 专栏**：走客户端接口 `app.bilibili.com/x/v2/space`（appkey 签名，无需登录）。
 *    返回 735 条投稿视频与 246 篇专栏，每条都自带官方深链
 *    `bilibili://video/<aid>`、`bilibili://article/<id>` —— 正好用来唤起哔哩哔哩客户端。
 * 2. **真实动态（图文）**：由 ui/BiliDynamicWeb.kt 用一个隐藏 WebView 打开官方动态页，
 *    拦截页面自己发出的 `feed/space` 响应（页面里带着 B 站自己发的风控 cookie），
 *    再交给 [parsePolymer] 解析。拿不到就只显示第 1 条路的内容。
 */
data class BiliDynamicItem(
    val id: String,
    val pubMs: Long,
    /** 动态正文 / 专栏摘要 / 视频标题。 */
    val text: String,
    val images: List<String>,
    /** 动态 / 视频 / 专栏。 */
    val kind: String,
    val isVideo: Boolean,
    val bvid: String,
    val aid: Long,
    /** 视频时长、播放量等一行补充信息。 */
    val meta: String,
    /** B 站官方深链（bilibili://video/... 或 bilibili://article/...）。 */
    val deepLink: String,
    val webUrl: String
)

data class BiliDynamicResult(
    val items: List<BiliDynamicItem>,
    val retcode: Int,
    val message: String
) {
    val ok: Boolean get() = retcode == 0
}

private const val BILI_MID = ApiConst.BILI_GENSHIN_UID

private const val BILI_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Mobile Safari/537.36"

/** 客户端接口用 appkey + md5(query + appsecret) 签名，不需要登录态。 */
private fun appSign(query: String): String = DS.md5(query + ApiConst.BILI_APP_SECRET)

/**
 * 拼 query 时**所有参数（含 ts）必须按 key 字母序排列**：
 * 服务端是按排序后的参数重算签名的，顺序不对会直接返回 `-3 签名错误`（实测）。
 */
private fun appApiUrl(path: String, params: List<Pair<String, String>>): String {
    val ts = (System.currentTimeMillis() / 1000).toString()
    val all = params + ("appkey" to ApiConst.BILI_APP_KEY) + ("ts" to ts)
    val query = all.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
    return "${ApiConst.BILI_APP_API}$path?$query&sign=${appSign(query)}"
}

private fun appHeaders(): Map<String, String> = mapOf(
    "User-Agent" to BILI_UA,
    "Accept" to "application/json"
)

private fun durationText(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}

private fun parseVideos(data: JSONObject): List<BiliDynamicItem> {
    val items = data.optJSONObject("archive")?.optJSONArray("item") ?: return emptyList()
    val out = ArrayList<BiliDynamicItem>(items.length())
    for (i in 0 until items.length()) {
        val v = items.optJSONObject(i) ?: continue
        val title = v.optString("title", "")
        val bvid = v.optString("bvid", "")
        val aid = v.optLong("param", 0L)
        if (title.isBlank() || (bvid.isBlank() && aid == 0L)) continue
        val cover = v.optString("cover", "").replace("http://", "https://")
        val meta = buildString {
            append(durationText(v.optInt("duration", 0)))
            val view = v.optString("view_content", "")
            if (view.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(view).append("播放")
            }
        }
        val uri = v.optString("uri", "")
        out.add(
            BiliDynamicItem(
                id = bvid.ifBlank { aid.toString() },
                pubMs = v.optLong("ctime", 0L) * 1000L,
                text = title,
                images = if (cover.isBlank()) emptyList() else listOf(cover),
                kind = "视频",
                isVideo = true,
                bvid = bvid,
                aid = aid,
                meta = meta,
                // 官方深链形如 bilibili://video/<aid>?cid=...
                deepLink = if (uri.startsWith("bilibili://")) uri else "bilibili://video/$aid",
                webUrl = if (bvid.isNotBlank()) "${ApiConst.BILI_WWW}/video/$bvid" else ""
            )
        )
    }
    return out
}

private fun parseArticles(data: JSONObject): List<BiliDynamicItem> {
    val items = data.optJSONObject("article")?.optJSONArray("item") ?: return emptyList()
    val out = ArrayList<BiliDynamicItem>(items.length())
    for (i in 0 until items.length()) {
        val a = items.optJSONObject(i) ?: continue
        val title = a.optString("title", "")
        val id = a.optLong("id", 0L)
        if (title.isBlank() || id == 0L) continue
        val cover = a.optString("banner_url", "").ifBlank {
            a.optJSONArray("image_urls")?.optString(0, "").orEmpty()
        }.replace("http://", "https://")
        val view = a.optJSONObject("stats")?.optInt("view", 0) ?: 0
        out.add(
            BiliDynamicItem(
                id = id.toString(),
                pubMs = a.optLong("publish_time", 0L) * 1000L,
                text = title + "\n" + a.optString("summary", ""),
                images = if (cover.isBlank()) emptyList() else listOf(cover),
                kind = "专栏",
                isVideo = false,
                bvid = "",
                aid = 0L,
                meta = if (view > 0) "${view}阅读" else "",
                deepLink = "bilibili://article/$id",
                webUrl = "${ApiConst.BILI_WWW}/read/cv$id"
            )
        )
    }
    return out
}

/** 官方投稿 + 专栏（客户端接口，无需登录，稳定可用）。 */
fun fetchOfficialBiliContent(): BiliDynamicResult {
    return try {
        val url = appApiUrl(
            "/x/v2/space",
            listOf(
                "build" to "7860000",
                "mobi_app" to "android",
                "platform" to "android",
                "ps" to "20",
                "vmid" to BILI_MID
            )
        )
        val obj = JSONObject(Http.get(url, appHeaders()).body)
        val code = obj.optInt("code", -1)
        if (code != 0) return BiliDynamicResult(emptyList(), code, obj.optString("message", ""))
        val data = obj.optJSONObject("data") ?: return BiliDynamicResult(emptyList(), 0, "")
        val items = ArrayList<BiliDynamicItem>()
        items.addAll(parseVideos(data))
        items.addAll(parseArticles(data))
        items.sortByDescending { it.pubMs }
        BiliDynamicResult(items, 0, "")
    } catch (e: Exception) {
        BiliDynamicResult(emptyList(), -1, e.message ?: "网络错误")
    }
}

private fun collectImages(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val url = o.optString("url", "").ifBlank { o.optString("src", "") }
        if (url.isNotBlank()) out.add(url.replace("http://", "https://"))
    }
    return out
}

/**
 * 解析隐藏 WebView 捕获到的动态（ui/BiliDynamicWeb.kt 里的 JS 已经把
 * `feed/space` 响应裁剪成 `{items:[{id_str,type,pub_ts,text,pics,aid,bvid}]}`，
 * 这样过桥的数据量小很多）。
 */
fun parseCapturedDynamics(raw: String): BiliDynamicResult {
    return try {
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("items") ?: return BiliDynamicResult(emptyList(), 0, "")
        val items = ArrayList<BiliDynamicItem>(arr.length())
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val type = it.optString("type", "")
            val aid = it.optLong("aid", 0L)
            val bvid = it.optString("bvid", "")
            val isVideo = aid > 0 || bvid.isNotBlank() || type == "DYNAMIC_TYPE_AV"
            val images = ArrayList<String>()
            it.optJSONArray("pics")?.let { pics ->
                for (j in 0 until pics.length()) {
                    val url = pics.optString(j, "")
                    if (url.isNotBlank()) images.add(url.replace("http://", "https://"))
                }
            }
            val idStr = it.optString("id_str", "")
            val text = it.optString("text", "").trim()
            if (idStr.isBlank() && text.isBlank() && images.isEmpty()) continue
            items.add(
                BiliDynamicItem(
                    id = idStr,
                    pubMs = it.optLong("pub_ts", 0L) * 1000L,
                    text = text,
                    images = images.distinct(),
                    kind = if (isVideo) "视频" else "动态",
                    isVideo = isVideo,
                    bvid = bvid,
                    aid = aid,
                    meta = "",
                    deepLink = if (isVideo && aid > 0) "bilibili://video/$aid" else "",
                    webUrl = if (isVideo && bvid.isNotBlank()) {
                        "${ApiConst.BILI_WWW}/video/$bvid"
                    } else {
                        "${ApiConst.BILI_T}/$idStr"
                    }
                )
            )
        }
        BiliDynamicResult(items, 0, "")
    } catch (e: Exception) {
        BiliDynamicResult(emptyList(), -1, "动态解析失败")
    }
}
