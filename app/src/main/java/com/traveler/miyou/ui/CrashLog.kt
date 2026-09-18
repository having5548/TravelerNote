// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简崩溃记录。
 *
 * release 包混淆过、`Log` 也被 R8 裁掉，真机闪退没有任何可看的信息。
 * 在 MainActivity 启动时安装一次未捕获异常处理器（**刻意不动 Application**，
 * 冷启动路径保持和以前完全一致），崩溃时把堆栈写两处：
 * 1. `cacheDir/last_crash.txt` —— 下次启动弹窗显示，用户可以截图；
 * 2. 系统「下载」目录（MediaStore，API 29+ 不需要权限）—— 万一应用连启动都起不来，
 *    用户还能用文件管理器把 `travelernote_crash_*.txt` 取出来发给作者。
 */
object CrashLog {

    const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stack = StringWriter()
                error.printStackTrace(PrintWriter(stack))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                val text = "time: $stamp\nthread: ${thread.name}\n\n$stack"
                runCatching { File(app.cacheDir, FILE_NAME).writeText(text) }
                runCatching { writeToDownloads(app, text) }
            } catch (ignored: Throwable) {
                // 记录失败不能影响正常崩溃流程
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 写一份到系统下载目录，方便在没有 adb 的情况下把日志取出来。 */
    private fun writeToDownloads(context: Context, text: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "travelernote_crash_$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }
}
