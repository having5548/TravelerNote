// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 极简图片加载器：内存 LRU + 磁盘缓存，用于角色头像等小图。 */
object ImageLoader {

    private val memCache: LruCache<String, Bitmap> by lazy {
        val max = (Runtime.getRuntime().maxMemory() / 8).toInt()
        object : LruCache<String, Bitmap>(max) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    fun load(context: Context, url: String, view: ImageView) {
        val key = url.hashCode().toString()
        memCache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        val dir = File(context.cacheDir, "img")
        val file = File(dir, "$key.img")
        if (file.exists()) {
            val cached = BitmapFactory.decodeFile(file.absolutePath)
            if (cached != null) {
                memCache.put(key, cached)
                view.setImageBitmap(cached)
                return
            }
        }
        view.tag = url
        view.setImageDrawable(null)
        Thread {
            try {
                var conn = URL(url).openConnection() as HttpURLConnection
                repeat(6) {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 12_000
                    conn.setRequestProperty("User-Agent", ApiConst.UA_DESKTOP)
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return@Thread
                        conn.disconnect()
                        conn = URL(if (loc.startsWith("http")) loc else URL(URL(url), loc).toString())
                            .openConnection() as HttpURLConnection
                        return@repeat
                    }
                    if (code != 200) {
                        conn.disconnect()
                        return@Thread
                    }
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@Thread
                    memCache.put(key, bmp)
                    dir.mkdirs()
                    runCatching { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    view.post { if (view.tag == url) view.setImageBitmap(bmp) }
                    return@Thread
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
