// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.traveler.miyou.net.ApiConst
import com.traveler.miyou.net.DS
import com.traveler.miyou.net.DeviceFp
import com.traveler.miyou.store.CookieStore
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * 米游社网页 JSBridge 的安卓实现，协议对齐 Snap.Hutao（MIT）的 MiHoYoJSBridge。
 *
 * 网页侧：`window.MiHoYoJSInterface.postMessage('{"method":..,"payload":..,"callback":..}')`
 * 宿主侧：`mhyWebBridge("<callback>", {"retcode":0,"message":"","data":{..}})`
 *
 * 有了这个桥，网页才会认为自己在米游社 App 内，并能拿到 X-Rpc 头、Cookie 与 DS 签名。
 */
class MihoyoBridge(
    private val webView: WebView,
    private val store: CookieStore,
    private val onOpenBrowser: (String) -> Unit
) {

    @JavascriptInterface
    fun post(message: String) {
        val param = runCatching { JSONObject(message) }.getOrNull()
        if (param == null) {
            // 兼容个别页面直接 post 一个裸 URL 的情况
            if (message.startsWith("http")) onOpenBrowser(message)
            return
        }
        val method = param.optString("method")
        val payload = param.optJSONObject("payload") ?: JSONObject()
        val callback = param.optString("callback").takeIf { it.isNotBlank() }

        val data = handle(method, payload)
        if (callback != null) {
            val result = JSONObject().apply {
                put("retcode", 0)
                put("message", "")
                put("data", data ?: JSONObject())
            }
            callBack(callback, result.toString())
        }
    }

    private fun handle(method: String, payload: JSONObject): JSONObject? = when (method) {
        "getHTTPRequestHeaders" -> JSONObject().apply {
            put("x-rpc-app_id", ApiConst.APP_ID)
            put("x-rpc-client_type", "5")
            put("x-rpc-device_id", store.deviceId())
            put("x-rpc-app_version", ApiConst.BBS_VERSION)
            put("x-rpc-sdk_version", "2.16.0")
            put("x-rpc-device_fp", DeviceFp.ensure(store))
        }

        "getCookieToken" -> JSONObject().put("cookie_token", store.cookieToken() ?: "")

        "getCookieInfo" -> JSONObject().apply {
            put("ltuid", store.ltuid() ?: "")
            put("ltoken", store.ltoken() ?: "")
            put("login_ticket", "")
        }

        // Gen1：LK2 盐，任意请求头签名用
        "getDS" -> JSONObject().put("DS", DS.gen1(ApiConst.LK2_SALT))

        // Gen2：X4 盐，body + query（query 需按字母序）
        "getDS2" -> {
            val body = payload.optString("body", "")
            val query = payload.optString("query", "")
                .split('&')
                .filter { it.isNotBlank() }
                .sorted()
                .joinToString("&")
            JSONObject().put("DS", DS.gen2(ApiConst.X4_SALT, body, query))
        }

        "getCurrentLocale" -> JSONObject().apply {
            val offsetHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000
            val language = Locale.getDefault().language
            put("language", if (language.startsWith("zh")) "zh-cn" else language)
            put("timeZone", String.format(Locale.US, "GMT%s%02d", if (offsetHours >= 0) "+" else "-", Math.abs(offsetHours)))
        }

        "getStatusBarHeight" -> JSONObject().put("statusBarHeight", 0)

        "isFloatingWindow" -> JSONObject().put("isFloatingWindow", false)

        // 页面拿不到用户信息也能正常渲染工具列表
        "getUserInfo" -> JSONObject()

        "pushPage" -> {
            val page = payload.optString("page", "")
            val target = when {
                page.startsWith("mihoyobbs://article/") ->
                    page.replace("mihoyobbs://article/", "https://m.miyoushe.com/ys/#/article/")
                page.startsWith("mihoyobbs://webview?link=") -> runCatching {
                    java.net.URLDecoder.decode(page.removePrefix("mihoyobbs://webview?link="), "UTF-8")
                }.getOrNull()
                page.startsWith("mihoyobbs://") -> null
                page.startsWith("http") -> page
                else -> null
            }
            if (!target.isNullOrBlank()) {
                webView.post { runCatching { webView.loadUrl(target) } }
            }
            JSONObject()
        }

        "openSystemBrowser" -> {
            val url = payload.optString("url", "")
            if (url.startsWith("http")) onOpenBrowser(url)
            JSONObject()
        }

        // 无副作用的空实现：返回成功即可，网页不会因此卡住
        else -> JSONObject()
    }

    private fun callBack(name: String, json: String) {
        val script = "mhyWebBridge(${JSONObject.quote(name)}, $json)"
        webView.post { runCatching { webView.evaluateJavascript(script, null) } }
    }
}
