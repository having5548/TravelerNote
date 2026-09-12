// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivityMainBinding
import com.traveler.miyou.net.ApiConst
import com.traveler.miyou.store.CookieStore

/**
 * 「旅行工具」页签的内嵌网页（不新开 Activity）。
 *
 * 直接加载米游社官方的旅行工具页，并做三件事让网页认为自己在米游社 App 内：
 * 1. UA 换成米游社 App 的 WebView UA（含 `miHoYoBBS/<版本>`）；
 * 2. 加载前把应用里缓存的登录凭证写进 WebView 的 cookie（含 `*_v2` 别名与 domain）；
 * 3. 注入 `window.MiHoYoJSInterface`（协议对齐 Snap.Hutao 的 MiHoYoJavaScripts）+
 *    通过 `addJavascriptInterface` 提供宿主实现，网页就能取到 X-Rpc 头 / Cookie / DS 签名。
 */
class ToolsWeb(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val store: CookieStore
) {

    companion object {
        /** 米游社「旅行工具」页面（原神 tab）。 */
        const val TOOLBOX_URL =
            "https://webstatic.mihoyo.com/bbs/event/e20200511toolbox/index.html?game_biz=ys_cn"

        /** 注入给网页的 SDK 桩：把页面侧调用接到安卓的 JSBridge。 */
        private val SDK_SHIM = """
            window.MiHoYoJSInterface = {
                postMessage: function(arg) {
                    if (window.mhyAndroidBridge) {
                        window.mhyAndroidBridge.post(typeof arg === 'string' ? arg : JSON.stringify(arg));
                    }
                },
                closePage: function() { this.postMessage('{"method":"closePage"}') }
            };
            window.miHoYoGameJSSDK = {
                openInBrowser: function(url) {
                    window.MiHoYoJSInterface.postMessage(JSON.stringify({ method: 'openSystemBrowser', payload: { url: url }, callback: '' }));
                },
                openInWebview: function(url) { location.href = url }
            };
            window.mhyWebBridge = window.mhyWebBridge || function() {};
        """.trimIndent()
    }

    /** 已经注入过 cookie 的 host，避免重复写。 */
    private val injectedHosts = mutableSetOf<String>()

    fun show() {
        val web = binding.toolsWeb
        if (web.url == null) {
            configure(web)
        }
        // 每次点「旅行工具」都回到入口页重新加载，避免一直停在上一个子页面
        injectCookies(TOOLBOX_URL)
        web.loadUrl(TOOLBOX_URL)
    }

    private fun configure(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 伪装成米游社 App 内置 WebView
            userAgentString = ApiConst.UA_WEB
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(
            MihoyoBridge(web, store) { url -> openInBrowser(url) },
            "mhyAndroidBridge"
        )

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                url?.let { injectCookies(it) }
                view?.evaluateJavascript(SDK_SHIM, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleDeepLink(view, uri)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.toolsProgress.progress = newProgress
                binding.toolsProgress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        // 返回键 / 侧滑返回：优先网页后退，退到底再回首页页签
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.toolsWebContainer.visibility != View.VISIBLE) {
                        activity.finish()
                        return
                    }
                    if (binding.toolsWeb.canGoBack()) {
                        binding.toolsWeb.goBack()
                    } else {
                        binding.bottomNav.selectedItemId = R.id.tab_home
                    }
                }
            }
        )
    }

    /** mihoyobbs:// 深链在应用内消化，保持"就在这里"的体验。 */
    private fun handleDeepLink(view: WebView?, uri: Uri): Boolean {
        if (uri.scheme != "mihoyobbs") return false
        val target = when {
            uri.toString().startsWith("mihoyobbs://article/") ->
                uri.toString().replace("mihoyobbs://article/", "https://m.miyoushe.com/ys/#/article/")
            uri.toString().startsWith("mihoyobbs://webview?link=") -> runCatching {
                java.net.URLDecoder.decode(uri.toString().removePrefix("mihoyobbs://webview?link="), "UTF-8")
            }.getOrNull()
            else -> null
        }
        if (target.isNullOrBlank()) return true
        view?.loadUrl(target)
        return true
    }

    /** 把应用里缓存的登录凭证写进 WebView 的 cookie 罐（每个 host 一次）。 */
    private fun injectCookies(url: String) {
        val host = Uri.parse(url).host ?: return
        if (!injectedHosts.add(host)) return

        val pairs = mutableListOf<Pair<String, String>>()
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank()) pairs.add(key to value)
        }
        val uid = store.accountId() ?: store.ltuid() ?: store.stuid()
        add("account_id", uid)
        add("account_id_v2", uid)
        add("cookie_token", store.cookieToken())
        add("ltuid", store.ltuid())
        add("ltuid_v2", store.ltuid())
        add("ltoken", store.ltoken())
        add("ltoken_v2", store.ltoken())
        add("stuid", store.stuid())
        add("stoken", store.stoken())
        add("mid", store.mid())
        add("account_mid_v2", store.mid())
        add("ltmid_v2", store.mid())
        if (pairs.isEmpty()) return

        val domain = when {
            host.endsWith("miyoushe.com") -> ".miyoushe.com"
            host.endsWith("mihoyo.com") -> ".mihoyo.com"
            host.endsWith("hoyolab.com") -> ".hoyolab.com"
            else -> return
        }
        val jar = CookieManager.getInstance()
        pairs.forEach { (key, value) ->
            jar.setCookie("https://$host", "$key=$value; domain=$domain; path=/")
        }
        jar.flush()
    }

    private fun openInBrowser(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
