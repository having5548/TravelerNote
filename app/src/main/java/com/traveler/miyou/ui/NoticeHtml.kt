// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.Context
import android.content.res.Configuration

/**
 * 游戏公告正文的 HTML 包装：与胡桃工具箱 `AnnouncementWebView2ContentProvider` 同款处理 ——
 * 图片自适应宽度、`rem` 字号放大到手机可读、深色模式把正文里的深色字反色。
 *
 * 两个刻意的防御：
 * 1. **正则不在对象初始化时构造**：`object` 的 `<clinit>` 一旦抛异常，整个类就废了
 *    （`ExceptionInInitializerError`，且是 Error，`catch (Exception)` 都接不住），
 *    所以这里改成用的时候才编译，并且用 `runCatching` 兜住任何引擎差异。
 * 2. 胡桃工具箱那句 ` style="(?!")*?vertical-align:middle;"` 用**量词修饰零宽断言**，
 *    桌面 JVM 能编译，Android 的 `java.util.regex` 实现并不保证支持；
 *    这里换成等价但完全可移植的写法。
 */
object NoticeHtml {

    fun isNight(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    /** 去掉正文里 `style="…vertical-align:middle;"` 这类只影响网页端排版的属性。 */
    private fun stripVerticalAlignStyle(html: String): String = runCatching {
        Regex("\\s*style=\"[^\"]*vertical-align:middle;[^\"]*\"").replace(html, "")
    }.getOrDefault(html)

    private fun expandRem(html: String): String = runCatching {
        Regex("[0-9]+\\.[0-9]+rem").replace(html) { "calc(${it.value} * 10)" }
    }.getOrDefault(html)

    fun build(context: Context, title: String, subtitle: String, banner: String, content: String): String {
        val night = isNight(context)

        var body = stripVerticalAlignStyle(content)
        body = expandRem(body)

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

    /** 兜底：正文原样当纯文本显示，保证"至少能看到内容"。 */
    fun plainHtml(text: String): String {
        val bg = "<style>body{font-family:sans-serif;font-size:15px;line-height:1.7;padding:14px;}</style>"
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            bg + "</head><body><pre style=\"white-space:pre-wrap;word-break:break-word;\">" +
            android.text.TextUtils.htmlEncode(text) + "</pre></body></html>"
    }
}
