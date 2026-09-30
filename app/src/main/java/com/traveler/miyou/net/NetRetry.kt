// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 网络重试与"人话"错误文案。
 *
 * 手表来拉数据时，手机端可能刚被唤醒 / 正在后台：系统还没把网络交还给进程，
 * 表现就是 `Unable to resolve host "api-takumi.mihoyo.com"`（DNS 解析失败）或连接超时。
 * 这类失败隔几百毫秒再试通常就正常了，直接把异常原文发给手表只会让人一脸问号。
 */
object NetRetry {

    private val DELAYS = longArrayOf(400L, 1200L, 2500L)

    /**
     * 执行 [block]，遇到可重试的网络故障时按 [delays] 间隔重试；
     * 非网络故障（例如解析失败、参数错误）立即抛出，不做无谓等待。
     */
    fun <T> withRetry(delays: LongArray = DELAYS, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (t: Throwable) {
                if (attempt >= delays.size || !isTransient(t)) throw t
                try {
                    Thread.sleep(delays[attempt])
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw t
                }
                attempt++
            }
        }
    }

    /** 是否是"等一会儿就好"的网络故障（DNS / 连不上 / 超时 / 网络不可达）。 */
    fun isTransient(t: Throwable): Boolean {
        if (t is UnknownHostException || t is ConnectException ||
            t is SocketTimeoutException || t is NoRouteToHostException
        ) {
            return true
        }
        val msg = t.message?.lowercase() ?: return false
        return t is IOException && (
            msg.contains("unable to resolve host") ||
                msg.contains("failed to connect") ||
                msg.contains("timed out") ||
                msg.contains("connection reset") ||
                msg.contains("network is unreachable")
            )
    }

    /** 给手表看的短句：别把英文异常原样丢过去。 */
    fun friendlyMessage(t: Throwable, fallback: String = "手机端暂时获取失败"): String {
        val msg = t.message?.lowercase() ?: ""
        return when {
            t is UnknownHostException || msg.contains("unable to resolve host") ->
                "手机端网络不可用（检查手机联网后重试）"
            t is SocketTimeoutException || msg.contains("timed out") ->
                "手机端网络超时，请稍后重试"
            t is ConnectException || t is NoRouteToHostException || msg.contains("failed to connect") ->
                "手机端连接服务器失败，请稍后重试"
            else -> t.message?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}
