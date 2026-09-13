// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * 用**隐藏的 WebView** 抓取原神官方 B 站账号的真实动态。
 *
 * 为什么不用直接请求接口：B 站的 `polymer/web-dynamic/v1/feed/space` 对没有登录态的
 * 请求一律回 **-412 request was banned**（实测加 `buvid3` / `bili_ticket` / wbi 签名都没用）。
 * 而页面自己在浏览器里是能正常拉到数据的 —— 因为 B 站前端会带上它自己下发的风控 cookie。
 * 所以这里让 WebView 真的把官方动态页打开，在页面脚本执行前 hook 掉 `fetch` / `XMLHttpRequest`，
 * 页面自己发出的那次 `feed/space` 响应就被我们截下来了，再裁剪成小 JSON 交回原生层渲染。
 *
 * 页面不可见（1dp、全透明），用户看到的一直是原生排版的列表。
 */
class BiliDynamicWeb(private val activity: Activity, private val container: ViewGroup) {

    private var web: WebView? = null
    private var finished = false
    private var onResult: ((String?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensure(): WebView {
        web?.let { return it }
        val view = WebView(activity)
        view.layoutParams = ViewGroup.LayoutParams(1, 1)
        view.alpha = 0f
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = DESKTOP_UA
        }
        view.addJavascriptInterface(Bridge(), "AndroidBili")
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // 必须在页面脚本跑起来之前注入 hook
                view?.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(HOOK_JS, null)
            }
        }
        container.addView(view)
        web = view
        return view
    }

    /**
     * 开始抓取；[callback] 拿到裁剪后的动态 JSON，超时或失败给 null。
     */
    fun capture(callback: (String?) -> Unit) {
        val view = ensure()
        finished = false
        onResult = callback
        handler.removeCallbacksAndMessages(null)
        // 每次进入页签都重新加载一次：页面自己不一定重新发请求
        view.loadUrl(PAGE_URL)

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                val v = web ?: return
                v.evaluateJavascript("window.__biliDyn || ''") { value ->
                    val json = decodeJsString(value)
                    if (json != null && json.contains("\"items\"")) finish(json)
                }
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, POLL_MS)
                } else {
                    finish(null)
                }
            }
        }
        handler.postDelayed(poll, POLL_MS)
    }

    private fun finish(json: String?) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        val cb = onResult
        onResult = null
        if (activity.isFinishing || activity.isDestroyed) return
        cb?.invoke(json)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        finished = true
        onResult = null
        web?.let { container.removeView(it); it.destroy() }
        web = null
    }

    /** evaluateJavascript 返回的是 JSON 编码过的字符串，这里解回来。 */
    private fun decodeJsString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return try {
            val tokener = org.json.JSONTokener(value)
            val obj = tokener.nextValue()
            if (obj is String && obj.isNotBlank()) obj else null
        } catch (e: Exception) {
            null
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun onData(json: String) {
            if (json.contains("\"items\"")) {
                handler.post { finish(json) }
            }
        }
    }

    companion object {
        private const val PAGE_URL = "https://space.bilibili.com/401742377/dynamic"
        private const val TIMEOUT_MS = 15_000L
        private const val POLL_MS = 800L

        /** 桌面版 UA：桌面动态页不需要登录也能渲染出动态列表。 */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36"

        /**
         * 拦截页面自己的请求，并把响应裁剪成 `{items:[{...}]}`：
         * 只留原生渲染需要的字段，避免几 MB 的原始 JSON 过桥。
         */
        private val HOOK_JS = """
            (function () {
              if (window.__biliHooked) return;
              window.__biliHooked = true;
              window.__biliDyn = '';
              function project(text) {
                try {
                  var o = JSON.parse(text);
                  var d = o && o.data;
                  if (!d || !d.items) return null;
                  var out = [];
                  for (var i = 0; i < d.items.length && i < 20; i++) {
                    var it = d.items[i] || {};
                    var m = it.modules || {};
                    var dy = m.module_dynamic || {};
                    var au = m.module_author || {};
                    var major = dy.major || {};
                    var pics = [];
                    if (major.draw && major.draw.items) {
                      for (var j = 0; j < major.draw.items.length; j++) {
                        if (major.draw.items[j].src) pics.push(major.draw.items[j].src);
                      }
                    }
                    if (major.opus && major.opus.pics) {
                      for (var k = 0; k < major.opus.pics.length; k++) {
                        if (major.opus.pics[k].url) pics.push(major.opus.pics[k].url);
                      }
                    }
                    var text = (dy.desc && dy.desc.text) || '';
                    if (!text && major.opus && major.opus.summary) text = major.opus.summary.text || '';
                    var ar = major.archive || null;
                    if (ar) {
                      if (ar.cover) pics = [ar.cover].concat(pics);
                      if (!text) text = ar.title || '';
                    }
                    if (!text && major.article) text = major.article.title || '';
                    out.push({
                      id_str: it.id_str || '',
                      type: it.type || '',
                      pub_ts: au.pub_ts || 0,
                      text: text,
                      pics: pics,
                      aid: ar ? (ar.aid || 0) : 0,
                      bvid: ar ? (ar.bvid || '') : ''
                    });
                  }
                  return JSON.stringify({ items: out });
                } catch (e) { return null; }
              }
              function save(text) {
                var p = project(text);
                if (!p) return;
                window.__biliDyn = p;
                try { if (window.AndroidBili) window.AndroidBili.onData(p); } catch (e) {}
              }
              function hit(u) { return String(u || '').indexOf('feed/space') >= 0; }
              var of = window.fetch;
              if (of) {
                window.fetch = function () {
                  var url = arguments[0];
                  if (url && typeof url === 'object' && url.url) url = url.url;
                  var p = of.apply(this, arguments);
                  if (hit(url)) {
                    try { p.then(function (r) { try { r.clone().text().then(save); } catch (e) {} }); } catch (e) {}
                  }
                  return p;
                };
              }
              var oo = XMLHttpRequest.prototype.open;
              var os = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (m, u) { this.__u = u; return oo.apply(this, arguments); };
              XMLHttpRequest.prototype.send = function () {
                var self = this;
                if (hit(this.__u)) {
                  this.addEventListener('load', function () {
                    try { save(self.responseText); } catch (e) {}
                  });
                }
                return os.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
