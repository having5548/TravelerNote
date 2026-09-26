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

    /** 正在下载/处理中的 key：避免重复下载，但失败后必须清掉，否则再也不会重试。 */
    @Volatile
    private var inFlightKey: String? = null

    /** 已经自动补过重试的 key（每个 key 只补一次，避免网络不通时无限重试）。 */
    private val retriedKeys =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * 顶栏那一条背后的背景是深色吗？（null = 还没算出来，调用方按主题明暗兜底）
     * 右上角设置图标与标题用它来选对比色：以前图标是矢量里写死的白色，浅色壁纸下完全看不见。
     */
    @Volatile
    var toolbarOnDark: Boolean? = null
        private set

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 主题强调色（M3 colorPrimary）。 */
    private fun accentColor(context: Context): Int {
        val tv = android.util.TypedValue()
        val ok = context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorPrimary, tv, true
        )
        return if (ok) tv.data else 0xFF5B6CF0.toInt()
    }

    private fun blend(base: Int, tint: Int, ratio: Float): Int {
        fun mix(a: Int, b: Int) = (a + (b - a) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(base), Color.red(tint)),
            mix(Color.green(base), Color.green(tint)),
            mix(Color.blue(base), Color.blue(tint))
        )
    }

    /**
     * 无背景（source = 0）时的纯色底：**按主题色混合出来的底色**，不再是纯白。
     * 纯白配深色卡片/半透明材质很割裂，而且顶栏白图标在纯白底上根本看不见。
     */
    fun noBackgroundColor(context: Context): Int {
        val night = isNight(context)
        val base = if (night) 0xFF12141C.toInt() else 0xFFF7F8FC.toInt()
        return blend(base, accentColor(context), if (night) 0.20f else 0.10f)
    }

    /** 顶栏那一条（上部约 1/10）的平均亮度是否偏暗。 */
    private fun topIsDark(src: Bitmap): Boolean {
        val h = (src.height * 0.10f).toInt().coerceAtLeast(1)
        val stepX = (src.width / 40).coerceAtLeast(1)
        val stepY = (h / 10).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < src.width) {
                val c = src.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return false
        return (sum / n) < 140.0
    }

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

    /**
     * 异步把背景应用到某个 Activity 的窗口（CenterCrop + 可选材质）。
     *
     * [onApplied] 在真正设置好背景之后（UI 线程）回调 —— 顶栏图标/标题要按新背景重新选对比色。
     *
     * 注意两点历史 bug：
     * - 无背景时以前是 `setBackgroundDrawable(null)`，窗口底色变 null 就露出**黑底**，
     *   配上半透明卡片还会看起来"UI 重叠"；现在改成**主题色纯色底**并强制重绘。
     * - 以前一进函数就把 key 记上，**下载失败也会被记住**，于是再也不重试、必须杀进程重进；
     *   现在只有真正应用成功才记 key，失败会清掉在途标记等下次重试。
     */
    fun apply(activity: Activity, onApplied: (() -> Unit)? = null) {
        val settings = SettingsStore(activity)
        val source = settings.backgroundSource
        if (source <= 0) {
            lastAppliedKey = null
            inFlightKey = null
            toolbarOnDark = null
            activity.window.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(noBackgroundColor(activity))
            )
            runCatching { activity.window.decorView.invalidate() }
            onApplied?.invoke()
            return
        }

        val effect = settings.bgEffect
        val level = if (effect == 2) settings.acrylicLevel else settings.frostLevel
        val night = isNight(activity)
        val key = "$source|$effect|$level|$night"
        if (key == lastAppliedKey || key == inFlightKey) return
        inFlightKey = key

        val cache = cacheFile(activity, source)
        Thread {
            val processed = runCatching {
                val bytes = cachedOrDownload(cache, source) ?: return@runCatching null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
                val size = Point()
                wm.defaultDisplay.getRealSize(size)
                val cropped = centerCrop(bmp, size.x, size.y)
                when (effect) {
                    1 -> frosted(cropped, level, night)
                    2 -> acrylic(cropped, level, night)
                    else -> cropped
                }
            }.getOrNull()

            if (processed == null) {
                // 下载/解码失败：清掉在途标记，下一次进前台还会再试；
                // 同一个 key 再自动补一次（隔 4 秒），省得用户以为"切了背景没反应"
                inFlightKey = null
                if (retriedKeys.add(key)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        runCatching { apply(activity, onApplied) }
                    }, 4000L)
                }
                return@Thread
            }

            activity.runOnUiThread {
                if (!activity.isFinishing) {
                    activity.window.setBackgroundDrawable(
                        BitmapDrawable(activity.resources, processed)
                    )
                    runCatching { activity.window.decorView.invalidate() }
                    lastAppliedKey = key
                    toolbarOnDark = topIsDark(processed)
                    onApplied?.invoke()
                }
                inFlightKey = null
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
