// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import java.security.MessageDigest
import kotlin.random.Random

/**
 * miHoYo 动态签名（DS）生成。
 * 算法：md5("salt={salt}&t={t}&r={r}[&b={body}&q={query}]")
 */
object DS {

    private val HEX = "0123456789abcdef".toCharArray()
    private val LOWER_DIGIT = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val b = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    private fun randomText(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(LOWER_DIGIT[Random.nextInt(LOWER_DIGIT.size)])
        }
        return sb.toString()
    }

    private fun randomNum(): String = Random.nextInt(100001, 200000).toString()

    /** 第一代签名：只含 salt/t/r。 */
    fun gen1(salt: String): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        val r = randomText(6)
        val check = md5("salt=$salt&t=$t&r=$r")
        return "$t,$r,$check"
    }

    /** 第二代签名：含 body 与 query。 */
    fun gen2(salt: String, body: String, query: String): String {
        val t = (System.currentTimeMillis() / 1000).toString()
        val r = randomNum()
        val check = md5("salt=$salt&t=$t&r=$r&b=$body&q=$query")
        return "$t,$r,$check"
    }
}
