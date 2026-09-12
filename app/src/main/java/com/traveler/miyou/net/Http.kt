// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 轻量 HTTP 客户端，仅依赖系统 HttpURLConnection。
 */
object Http {

    private const val CONNECT_TIMEOUT = 12_000
    private const val READ_TIMEOUT = 18_000

    class Result(val code: Int, val body: String, val headers: Map<String, String>)

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = false
        headers.forEach { (k, v) ->
            if (k.isNotEmpty() && v.isNotEmpty()) {
                conn.setRequestProperty(k, v)
            }
        }
        return conn
    }

    private fun readStream(conn: HttpURLConnection): String {
        val raw: InputStream = try {
            conn.inputStream
        } catch (e: IOException) {
            conn.errorStream ?: throw e
        }
        val encoding = conn.contentEncoding ?: ""
        val input = if (encoding.equals("gzip", ignoreCase = true)) {
            GZIPInputStream(raw)
        } else {
            raw
        }
        val out = ByteArrayOutputStream()
        input.use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun headersOf(conn: HttpURLConnection): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        conn.headerFields?.forEach { (k, values) ->
            if (k != null && values.isNotEmpty()) map[k.lowercase()] = values[0]
        }
        return map
    }

    fun get(url: String, headers: Map<String, String>): Result {
        val conn = open(url, "GET", headers)
        return try {
            val code = conn.responseCode
            val body = try {
                readStream(conn)
            } catch (e: IOException) {
                ""
            }
            Result(code, body, headersOf(conn))
        } finally {
            conn.disconnect()
        }
    }

    fun post(url: String, body: String, headers: Map<String, String>): Result {
        val conn = open(url, "POST", headers)
        return try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val resp = try {
                readStream(conn)
            } catch (e: IOException) {
                ""
            }
            Result(code, resp, headersOf(conn))
        } finally {
            conn.disconnect()
        }
    }
}
