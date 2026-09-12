// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Point
import android.view.WindowManager
import com.traveler.miyou.store.SettingsStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 软件背景来源引擎（仅主界面使用，实时生效）：
 * 0=无  1=每日随机图片  2=必应每日一图
 */
object BackgroundEngine {

    private const val TTL_MS = 6 * 3600 * 1000L
    private const val CACHE_VERSION = 2

    private fun cacheFile(context: Context, source: Int): File =
        File(context.filesDir, "bgv${CACHE_VERSION}_$source.bin")

    /** 设置变化后作废对应缓存，下次进入主界面即拉新图。 */
    fun invalidate(context: Context, source: Int) {
        runCatching { cacheFile(context, source).delete() }
    }

    fun resolveUrl(source: Int): String? {
        return when (source) {
            1 -> "https://t.alcy.cc/mp"
            2 -> bingImageUrl()
            else -> null
        }
    }

    private fun bingImageUrl(): String? {
        return try {
            val resp = Http.get(
                "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN",
                mapOf("User-Agent" to ApiConst.UA_DESKTOP)
            )
            val obj = JSONObject(resp.body)
            val u = obj.optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: return null
            if (u.startsWith("http")) u else "https://www.bing.com$u"
        } catch (e: Exception) {
            null
        }
    }

    private fun download(url: String): ByteArray? {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", ApiConst.UA_DESKTOP)
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return null
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    return bytes
                }
                conn.disconnect()
                return null
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    /** 将图片按屏幕比例居中裁剪（CenterCrop），避免拉伸变形。 */
    private fun centerCrop(src: Bitmap, outW: Int, outH: Int): Bitmap {
        if (outW <= 0 || outH <= 0) return src
        val scale = maxOf(outW.toFloat() / src.width, outH.toFloat() / src.height)
        val dw = (src.width * scale).toInt()
        val dh = (src.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, dw, dh, true)
        if (scaled == src) return src
        val x = (dw - outW) / 2
        val y = (dh - outH) / 2
        return try {
            Bitmap.createBitmap(scaled, x.coerceAtLeast(0), y.coerceAtLeast(0), outW, outH)
        } catch (e: Exception) {
            src
        }
    }

    /** 异步应用到主界面窗口背景（CenterCrop）。 */
    fun apply(activity: Activity) {
        val source = SettingsStore(activity).backgroundSource
        if (source <= 0) return
        val cache = cacheFile(activity, source)
        Thread {
            try {
                val bytes = cachedOrDownload(cache, source) ?: return@Thread
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
                val size = Point()
                wm.defaultDisplay.getRealSize(size)
                val cropped = centerCrop(bmp, size.x, size.y)
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        activity.window.setBackgroundDrawable(BitmapDrawable(activity.resources, cropped))
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun cachedOrDownload(cache: File, source: Int): ByteArray? {
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < TTL_MS) {
            val cached = cache.readBytes()
            if (cached.isNotEmpty()) return cached
        }
        val url = resolveUrl(source) ?: return null
        val bytes = download(url) ?: return null
        runCatching {
            FileOutputStream(cache).use { it.write(bytes) }
        }
        return bytes
    }
}
