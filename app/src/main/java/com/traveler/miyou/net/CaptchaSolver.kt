package com.traveler.miyou.net

import com.traveler.miyou.store.SettingsStore
import org.json.JSONObject

/**
 * 无感验证：调用第三方打码接口自动识别极验验证码。
 * 默认关闭；未配置 userkey 时返回 null，由上层回退到网页手动验证。
 */
object CaptchaSolver {

    data class Solution(val validate: String, val challenge: String)

    fun solve(settings: SettingsStore, gt: String, challenge: String): Solution? {
        if (!settings.seamlessCaptcha) return null
        val userkey = settings.captchaUserkey.trim()
        val api = settings.captchaApi.trim()
        if (userkey.isEmpty() || api.isEmpty()) return null
        // 本应用全局禁用明文流量（usesCleartextTraffic=false），
        // 旧的 http 打码地址（含历史版本遗留的默认值）必然请求失败，直接当作未配置处理。
        if (!api.startsWith("https://", ignoreCase = true)) return null

        val url = buildString {
            append(api)
            append(if (api.contains('?')) '&' else '?')
            append("userkey=").append(java.net.URLEncoder.encode(userkey, "UTF-8"))
            append("&gt=").append(java.net.URLEncoder.encode(gt, "UTF-8"))
            append("&challenge=").append(java.net.URLEncoder.encode(challenge, "UTF-8"))
            append("&isJson=2")
        }
        val resp = Http.get(url, mapOf("User-Agent" to ApiConst.UA_WEB))
        return try {
            val obj = JSONObject(resp.body)
            if (obj.optString("status", "") != "0") return null
            val raw = obj.optString("data", "")
            val idx = raw.indexOf('|')
            if (idx <= 0) return null
            val c = raw.substring(0, idx).trim()
            val v = raw.substring(idx + 1).trim()
            if (c.isEmpty() || v.isEmpty()) null else Solution(v, c)
        } catch (e: Exception) {
            null
        }
    }
}
