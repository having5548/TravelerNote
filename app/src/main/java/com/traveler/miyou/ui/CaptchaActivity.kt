// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.traveler.miyou.databinding.ActivityCaptchaBinding

class CaptchaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptchaBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityCaptchaBinding.inflate(layoutInflater)
        setContentView(binding.root)


        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        val gt = intent.getStringExtra("gt") ?: ""
        val challenge = intent.getStringExtra("challenge") ?: ""
        val riskType = intent.getStringExtra("risk_type") ?: ""
        val sessionId = intent.getStringExtra("session_id") ?: ""

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.webView.addJavascriptInterface(Bridge(), "Android")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                Log.w("CaptchaWeb", "load error ${error?.description}")
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("CaptchaWeb", "${m.messageLevel()}: ${m.message()} @${m.lineNumber()}")
                return true
            }
        }
        binding.webView.loadDataWithBaseURL(
            "https://www.miyoushe.com/",
            buildHtml(gt, challenge, riskType, sessionId),
            "text/html",
            "utf-8",
            null
        )
    }

    private fun buildHtml(gt: String, challenge: String, riskType: String, sessionId: String): String {
        val isV4 = challenge.isBlank()
        val escaped = { s: String -> s.replace("\\", "\\\\").replace("'", "\\'") }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
            <style>
              body{margin:0;padding:20px 12px;background:#fff;font-family:sans-serif}
              #box{margin:0 auto;max-width:320px}
            </style>
            </head>
            <body>
            <div id="box"></div>
            <script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script>
            <script src="https://static.geetest.com/v4/gt4.js"></script>
            <script>
              var succeeded = false;
              var reported = false;
              function report(o){ if(reported) return; reported = true; if(window.Android){ Android.onResult(JSON.stringify(o)); } }
              function done(o){ succeeded = true; report(o); }
              // 延迟兜底：popup 关闭若早于 onSuccess，等 1.2s 再判定取消，避免覆盖成功
              function cancelIfUnsolved(){ setTimeout(function(){ if(!succeeded && !reported){ reported = true; if(window.Android){ Android.onClose(); } } }, 1200); }
              function resolveV3(c){
                var r = (c.getValidate && c.getValidate()) || {};
                done({
                  challenge: r.geetest_challenge || r.challenge || '${escaped(challenge)}',
                  validate:  r.geetest_validate || r.validate || '',
                  seccode:   r.geetest_seccode || (r.geetest_validate ? r.geetest_validate + '|jordan' : '')
                });
              }
              var IS_V4 = ${if (isV4) "true" else "false"};
              if(IS_V4){
                window.initGeetest4({
                  captchaId: '${escaped(gt)}',
                  riskType: '${escaped(riskType)}',
                  product: 'popup',
                  nextWidth: '280px',
                  lang: 'zho',
                  userInfo: JSON.stringify({session_id:'${escaped(sessionId)}'}),
                  https: true,
                  protocol: 'https'
                }, function(c){
                  c.appendTo('#box');
                  c.onSuccess(function(){ done(c.getValidate()); });
                  c.onClose(cancelIfUnsolved);
                });
              } else {
                window.initGeetest({
                  gt: '${escaped(gt)}',
                  challenge: '${escaped(challenge)}',
                  offline: false,
                  new_captcha: true,
                  product: 'popup',
                  width: '280px',
                  https: true
                }, function(c){
                  c.appendTo('#box');
                  c.onSuccess(function(){ resolveV3(c); });
                  c.onClose(cancelIfUnsolved);
                });
              }
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    inner class Bridge {
        @JavascriptInterface
        fun onResult(validate: String) {
            Log.d("CaptchaWeb", "onResult len=${validate.length} head=${validate.take(120)}")
            runOnUiThread {
                val data = Intent().putExtra("validate", validate)
                setResult(RESULT_OK, data)
                finish()
            }
        }

        @JavascriptInterface
        fun onClose() {
            Log.d("CaptchaWeb", "onClose")
            runOnUiThread {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
