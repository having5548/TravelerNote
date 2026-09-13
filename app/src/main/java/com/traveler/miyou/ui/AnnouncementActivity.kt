// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.traveler.miyou.databinding.ActivityAnnouncementBinding

/**
 * 游戏公告详情：**应用内**打开，不跳外部浏览器（对齐米哈游启动器的行为）。
 *
 * 正文是官方返回的 HTML（见 net/Announcement.kt），这里套一层与胡桃工具箱同款的
 * 模板：图片自适应宽度、深色模式把正文里的深色字反色、`rem` 字号放大到手机可读。
 *
 * 页面**先打开、再取正文**：点开列表立刻能看到标题与横幅，正文在后台拉，
 * 成功就替换、失败就把原因写在正文位置 —— 不会出现点开一片空白的情况。
 */
class AnnouncementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnnouncementBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityAnnouncementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        val banner = intent.getStringExtra(EXTRA_BANNER).orEmpty()
        val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
        val annId = intent.getIntExtra(EXTRA_ANN_ID, 0)
        val region = intent.getStringExtra(EXTRA_REGION).orEmpty().ifBlank { "cn_gf01" }

        binding.toolbar.title = title
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.web.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // 正文里的图片来自官方静态资源域名，需要联网；HTML 本身是本地字符串
        }
        // 明确给个底色：透明底 + 深色字在某些主题组合下会看起来"一片空白"
        binding.web.setBackgroundColor(if (isNight()) Color.parseColor("#121212") else Color.WHITE)
        binding.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        when {
            content.isNotBlank() -> show(title, subtitle, banner, content)
            annId <= 0 -> show(title, subtitle, banner, "<p>该公告没有可显示的正文。</p>")
            else -> {
                binding.progress.visibility = View.VISIBLE
                show(title, subtitle, banner, "<p>正在加载正文…</p>")
                loadContent(title, subtitle, banner, annId, region)
            }
        }
    }

    private fun loadContent(title: String, subtitle: String, banner: String, annId: Int, region: String) {
        Thread {
            var html = ""
            var error = ""
            try {
                html = com.traveler.miyou.net.cleanNoticeHtml(
                    com.traveler.miyou.net.fetchAnnouncementContents(region)[annId].orEmpty()
                )
            } catch (e: Exception) {
                error = e.message ?: "网络错误"
            }
            val finalHtml = html.ifBlank {
                "<p>正文加载失败" + (if (error.isBlank()) "（该公告正文为空）" else "（$error）") + "</p>"
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.progress.visibility = View.GONE
                show(title, subtitle, banner, finalHtml)
            }
        }.start()
    }

    private fun show(title: String, subtitle: String, banner: String, content: String) {
        binding.web.loadDataWithBaseURL(
            "https://webstatic.mihoyo.com/",
            buildHtml(title, subtitle, banner, content),
            "text/html",
            "utf-8",
            null
        )
    }

    private fun isNight(): Boolean {
        val mask = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 与胡桃工具箱 AnnouncementWebView2ContentProvider 相同的处理思路。 */
    private fun buildHtml(title: String, subtitle: String, banner: String, content: String): String {
        var body = content
        body = Regex(" style=\"(?!\")*?vertical-align:middle;\"").replace(body, "")
        body = Regex("[0-9]+\\.[0-9]+rem").replace(body) { "calc(${it.value} * 10)" }

        val night = isNight()
        if (night) {
            val reverts = listOf(
                "color:rgba(0,0,0,1)" to "color:rgba(255,255,255,1)",
                "color:rgba(17,17,17,1)" to "color:rgba(238,238,238,1)",
                "color:rgba(51,51,51,1)" to "color:rgba(204,204,204,1)",
                "color:rgba(57,59,64,1)" to "color:rgba(198,196,191,1)",
                "color:rgba(73,73,73,1)" to "color:rgba(182,182,182,1)",
                "color:rgba(85,85,85,1)" to "color:rgba(170,170,170,1)",
                "background-color: rgb(255, 215, 185)" to "background-color: rgb(0,40,70)",
                "background-color: rgb(254, 245, 231)" to "background-color: rgb(1,40,70)",
                "background-color:rgb(244, 244, 245)" to "background-color:rgba(11, 11, 10)"
            )
            reverts.forEach { (from, to) -> body = body.replace(from, to) }
        }

        val textColor = if (night) "rgba(255,255,255,1)" else "rgba(0,0,0,1)"
        val bgColor = if (night) "#121212" else "#FFFFFF"
        val titleHtml = if (title.isBlank()) "" else "<h3>$title</h3>"
        val subHtml = if (subtitle.isBlank()) "" else "<div class=\"sub\">$subtitle</div>"
        val bannerHtml = if (banner.isBlank()) "" else "<img src=\"$banner\"/>"

        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { margin: 0; padding: 14px; font-size: 15px; line-height: 1.7;
                     color: $textColor; background-color: $bgColor;
                     font-family: sans-serif; word-break: break-word; }
              img { border: none; vertical-align: middle; width: 100%; height: auto; }
              .sub { opacity: .7; font-size: 13px; margin-bottom: 10px; }
              table { width: 100% !important; }
            </style>
            </head>
            <body>
            $titleHtml
            $subHtml
            $bannerHtml
            <br>
            $body
            </body>
            </html>
        """.trimIndent()
    }

    override fun onDestroy() {
        binding.web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "ann_title"
        const val EXTRA_SUBTITLE = "ann_subtitle"
        const val EXTRA_BANNER = "ann_banner"
        const val EXTRA_CONTENT = "ann_content"
        const val EXTRA_ANN_ID = "ann_id"
        const val EXTRA_REGION = "ann_region"

        fun intent(
            context: Context,
            annId: Int,
            title: String,
            subtitle: String,
            banner: String,
            region: String,
            content: String = ""
        ): Intent = Intent(context, AnnouncementActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SUBTITLE, subtitle)
            putExtra(EXTRA_BANNER, banner)
            putExtra(EXTRA_CONTENT, content)
            putExtra(EXTRA_ANN_ID, annId)
            putExtra(EXTRA_REGION, region)
        }
    }
}
