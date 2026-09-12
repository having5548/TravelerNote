// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
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
 *
 * 背景材质（设置里可选，两种材质各有独立程度）——思路对齐胡桃工具箱的窗口材质：
 * 材质只作用在「背景之上」，不会把壁纸糊掉：
 *  1 磨砂（云母风格）：**图片保持清晰**，只叠一层均匀雾面，程度 = 雾的浓淡
 *  2 亚克力：在雾面之外再加一点很克制的模糊，程度 = 模糊 + 雾面强度
 * 另外启用材质时会把 surface 换成半透明（ThemeOverlay.TravelerNote.Glass），
 * 卡片透出背景，才是"玻璃"的观感。
 *
 * 是否重绘由内部按「来源 + 材质 + 程度 + 明暗」的签名判断，调用方每次前台恢复直接调用即可。
 */
object BackgroundEngine {

    private const val TTL_MS = 6 * 3600 * 1000L
    private const val CACHE_VERSION = 3

    /** 材质档位上限（设置里的滑杆范围 0..MAX_LEVEL）。 */
    const val MAX_LEVEL = 10

    private var lastAppliedKey: String? = null

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

    /**
     * 很克制的模糊：降采样到 1/(1+0.18*程度) 再插值放大。
     * 程度 1 ≈ 0.85（几乎看不出），程度 5 ≈ 0.53，程度 10 ≈ 0.36（明显的亚克力）。
     * 之前的 1/(1+1.6*程度) 会降采样到 1/17，直接糊成一团，所以特意收窄了范围。
     */
    private fun subtleBlur(src: Bitmap, level: Int): Bitmap {
        val lv = level.coerceIn(1, MAX_LEVEL)
        val factor = 1f / (1f + lv * 0.18f)
        val w = (src.width * factor).toInt().coerceAtLeast(1)
        val h = (src.height * factor).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val restored = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small != src && small != restored) small.recycle()
        return restored
    }

    /** 在图片上叠一层均匀雾面（浅色白雾 / 深色黑雾）。 */
    private fun fog(src: Bitmap, alpha: Int, night: Boolean): Bitmap {
        if (alpha <= 0) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val color = if (night) Color.argb(alpha, 0, 0, 0) else Color.argb(alpha, 255, 255, 255)
        Canvas(out).drawColor(color)
        return out
    }

    /**
     * 磨砂（云母风格）：**不模糊**，只加雾面，图片始终保持清晰。
     * 玻璃感来自雾面 + 半透明卡片（Glass 主题叠加）。
     */
    private fun frosted(src: Bitmap, level: Int, night: Boolean): Bitmap {
        val lv = level.coerceIn(0, MAX_LEVEL)
        return fog(src, lv * 14, night)
    }

    /** 亚克力：轻模糊 + 更明显的雾面。 */
    private fun acrylic(src: Bitmap, level: Int, night: Boolean): Bitmap {
        val lv = level.coerceIn(0, MAX_LEVEL)
        if (lv <= 0) return src
        return fog(subtleBlur(src, lv), lv * 12, night)
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 异步把背景应用到某个 Activity 的窗口（CenterCrop + 可选材质）。 */
    fun apply(activity: Activity) {
        val settings = SettingsStore(activity)
        val source = settings.backgroundSource
        if (source <= 0) {
            if (lastAppliedKey != null) {
                lastAppliedKey = null
                activity.window.setBackgroundDrawable(null)
            }
            return
        }

        val effect = settings.bgEffect
        val level = if (effect == 2) settings.acrylicLevel else settings.frostLevel
        val night = isNight(activity)
        val key = "$source|$effect|$level|$night"
        if (key == lastAppliedKey) return
        lastAppliedKey = key

        val cache = cacheFile(activity, source)
        Thread {
            try {
                val bytes = cachedOrDownload(cache, source) ?: return@Thread
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
                val size = Point()
                wm.defaultDisplay.getRealSize(size)
                val cropped = centerCrop(bmp, size.x, size.y)
                val processed = when (effect) {
                    1 -> frosted(cropped, level, night)
                    2 -> acrylic(cropped, level, night)
                    else -> cropped
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        activity.window.setBackgroundDrawable(BitmapDrawable(activity.resources, processed))
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
