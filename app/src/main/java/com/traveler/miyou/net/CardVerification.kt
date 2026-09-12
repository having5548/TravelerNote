// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import org.json.JSONObject

/**
 * 战绩接口（api-takumi-record）返回 **retcode 1034「需要人机验证」** 时的 card wapi 验证流程。
 *
 * 与社区签到的 `misc/api/createVerification` 不是同一套接口，必须用带
 * `x-rpc-challenge_game: 2`（原神）和 `x-rpc-challenge_path`（必须是完整的接口 URL）的
 * card 接口，流程对齐胡桃工具箱 Snap.Hutao 的 CardClient + GeetestService：
 *
 * 1. GET  `card/wapi/createVerification?is_high=true` → gt / challenge；
 * 2. 用极验解出 geetest_challenge / validate / seccode；
 * 3. POST `card/wapi/verifyVerification` → 换回真正要放进 `x-rpc-challenge` 的 challenge；
 * 4. 带上 `x-rpc-challenge` 重放原战绩请求。
 */
object CardVerification {

    /** x-rpc-challenge_game：2 = 原神（见 CardVerificationHeaders.Create）。 */
    private const val CHALLENGE_GAME = "2"

    /** 服务端要求 challenge_path 为**完整接口地址**，与胡桃 toolbox 一致。 */
    private val challengePath: String get() = ApiConst.CHARACTER_LIST_URL

    private fun headers(
        cookie: String,
        deviceId: String,
        deviceFp: String,
        query: String,
        body: String = ""
    ): MutableMap<String, String> {
        val h = MiyouApi.recordHeaders(cookie, deviceId, deviceFp, query, body)
        h["x-rpc-challenge_game"] = CHALLENGE_GAME
        h["x-rpc-challenge_path"] = challengePath
        return h
    }

    /** 初始化人机验证，返回极验参数。 */
    fun create(cookie: String, deviceId: String, deviceFp: String): GeetestChallenge? {
        val query = "is_high=true"
        val resp = Http.get(ApiConst.CARD_CREATE_VERIFICATION_URL, headers(cookie, deviceId, deviceFp, query))
        return parseGeetestChallenge(resp.body)
    }

    /** 提交极验结果，换取可直接放进 x-rpc-challenge 的 challenge。 */
    fun verify(
        cookie: String,
        deviceId: String,
        deviceFp: String,
        gtChallenge: String,
        validate: String,
        seccode: String
    ): VerificationPass? {
        val body = JSONObject().apply {
            put("geetest_challenge", gtChallenge)
            put("geetest_validate", validate)
            put("geetest_seccode", seccode)
        }.toString()
        val resp = Http.post(
            ApiConst.CARD_VERIFY_VERIFICATION_URL,
            body,
            headers(cookie, deviceId, deviceFp, "", body)
        )
        return parseVerificationPass(resp.body)
    }
}
