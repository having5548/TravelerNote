// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import android.util.Base64

/**
 * 端点与盐值集中存放，并以 base64 形式落盘，
 * 避免静态字符串扫描直接暴露接口信息。
 *
 * 注意：base64 只提高静态扫描成本，并不等于加密，反编译后仍可直接还原。
 */
object ApiConst {

    private fun d(s: String): String =
        String(Base64.decode(s, Base64.NO_WRAP), Charsets.UTF_8)

    // 基础端点
    val BBS_API: String = d("aHR0cHM6Ly9iYnMtYXBpLm1peW91c2hlLmNvbQ==")
    val TAKUMI_API: String = d("aHR0cHM6Ly9hcGktdGFrdW1pLm1paG95by5jb20=")
    val TAKUMI_RECORD_API: String = d("aHR0cHM6Ly9hcGktdGFrdW1pLXJlY29yZC5taWhveW8uY29t")
    val PASSPORT_API: String = d("aHR0cHM6Ly9wYXNzcG9ydC1hcGkubWlob3lvLmNvbQ==")
    val WEBSTATIC: String = d("aHR0cHM6Ly93ZWJzdGF0aWMubWlob3lvLmNvbQ==")
    val APP_REFERER: String = d("aHR0cHM6Ly9hcHAubWlob3lvLmNvbQ==")

    // 盐
    val BBS_SALT: String = d("aWRNTWFHWW1WZ1B6aDN3eG1XdWRVWEtVUEdpZE83R00=")
    val BBS_WEB_SALT: String = d("RzFrdGR3Rkw0SXlHa0h1dVdTbXowd1VlOURiOXNjeUs=")
    val X4_SALT: String = d("eFY4djRRdTU0bFVLckVZRlprSmhCOGN1T2g5QXNhZnM=")
    val X6_SALT: String = d("dDBxRWdmdWI2Y3Z1ZUFQZ1I1bTlhUVdXVmNpRWVyN3Y=")
    val APP_SALT: String = d("ZERJUUhiS09kYVBhTHV2UUtWelV6cWRlQ2F4anRhUFY=")
    val LK2_SALT: String = d("c2lkUUZFZ2xhakV6N0ZBMEFqN0hRUFY4OHpwZjE3U08=")

    // 客户端版本
    const val BBS_VERSION = "2.106.2"
    const val PASSPORT_APP_VERSION = "2.90.1"
    const val RECORD_VERSION = "2.95.1"
    const val GENSHIN_ACT_ID = "e202311201442471"

    /** 社区客户端 app_id：短信验证码 / 二维码登录用。 */
    val APP_ID: String = d("YmxsOGlxOTdjZW04")

    // 端点
    /** 社区（论坛）签到：只在用户手动点击时调用。 */
    val SIGN_IN_URL: String get() = "$BBS_API/apihub/app/api/signIn"

    /** 米游币任务状态（今日是否已签 + 米游币余额），只读。 */
    val BBS_MISSIONS_URL: String get() = "$BBS_API/apihub/wapi/getUserMissionsState"

    /** 原神每日签到的补签：先查 resign_info（漏签天数/消耗），再 POST resign 补签（花米游币）。 */
    val LUNA_RESIGN_INFO_URL: String get() = "$TAKUMI_API/event/luna/resign_info"
    val LUNA_RESIGN_URL: String get() = "$TAKUMI_API/event/luna/resign"
    val GAME_ROLES_URL: String get() = "$TAKUMI_API/binding/api/getUserGameRolesByCookie"
    val MULTI_TOKEN_URL: String get() = "$TAKUMI_API/auth/api/getMultiTokenByLoginTicket"
    val COOKIE_TOKEN_URL: String get() = "$PASSPORT_API/account/auth/api/getCookieAccountInfoBySToken"
    val LTOKEN_URL: String get() = "$PASSPORT_API/account/auth/api/getLTokenBySToken"

    val QR_FETCH_URL: String get() = "$PASSPORT_API/account/ma-cn-passport/app/createQRLogin"
    val QR_QUERY_URL: String get() = "$PASSPORT_API/account/ma-cn-passport/app/queryQRLoginStatus"
    val CAPTCHA_SEND_URL: String get() = "$PASSPORT_API/account/ma-cn-verifier/verifier/createLoginCaptcha"
    val CAPTCHA_LOGIN_URL: String get() = "$PASSPORT_API/account/ma-cn-passport/app/loginByMobileCaptcha"

    /** 社区签到遇到 1034 时的极验流程。 */
    val CREATE_VERIFICATION_URL: String get() = "$BBS_API/misc/api/createVerification?is_high=true"
    val VERIFY_VERIFICATION_URL: String get() = "$BBS_API/misc/api/verifyVerification"

    // 战绩类端点（api-takumi-record）：query 需按字母序拼接后再做 DS 签名
    val SPIRAL_ABYSS_URL: String get() = "$TAKUMI_RECORD_API/game_record/app/genshin/api/spiralAbyss"
    val ROLE_COMBAT_URL: String get() = "$TAKUMI_RECORD_API/game_record/app/genshin/api/role_combat"

    /** 「我的角色」：POST 角色列表（body 参与 DS 签名）。 */
    val CHARACTER_LIST_URL: String get() = "$TAKUMI_RECORD_API/game_record/app/genshin/api/character/list"

    /** 「我的角色」详情：POST 面板属性 + 圣遗物 + 天赋（body 带 character_ids）。 */
    val CHARACTER_DETAIL_URL: String get() = "$TAKUMI_RECORD_API/game_record/app/genshin/api/character/detail"

    /** 战绩接口 1034 人机验证（card wapi，对齐胡桃工具箱 CardClient）。 */
    val CARD_CREATE_VERIFICATION_URL: String get() =
        "$TAKUMI_RECORD_API/game_record/app/card/wapi/createVerification?is_high=true"
    val CARD_VERIFY_VERIFICATION_URL: String get() =
        "$TAKUMI_RECORD_API/game_record/app/card/wapi/verifyVerification"

    /** 活动日历（含 UP 池）：POST role_id/server，body 参与签名。 */
    val ACT_CALENDAR_URL: String get() = "$TAKUMI_RECORD_API/game_record/app/genshin/api/act_calendar"

    /** 游戏公告：与米哈游启动器同源，无需登录、无需 DS。 */
    val ANN_API: String = d("aHR0cHM6Ly9oazRlLWFubi1hcGkubWlob3lvLmNvbQ==")

    /**
     * 公告接口的 platform 参数。实测（2026-09）：`android` / `ios` 返回的是**空列表**，
     * 只有 `pc` 有数据，所以固定用 pc（保留参数是为了万一以后移动端也要单独取）。
     */
    fun annListUrl(region: String, platform: String = "pc"): String =
        "$ANN_API/common/hk4e_cn/announcement/api/getAnnList?" + annQuery(region, platform)

    fun annContentUrl(region: String, platform: String = "pc"): String =
        "$ANN_API/common/hk4e_cn/announcement/api/getAnnContent?" + annQuery(region, platform)

    private fun annQuery(region: String, platform: String): String =
        "game=hk4e&game_biz=hk4e_cn&lang=zh-cn&bundle_id=hk4e_cn&platform=$platform" +
            "&region=$region&level=55&uid=100000000"

    // 米游社官方 B 站账号动态（公开接口，无需登录）
    val BILI_API: String = d("aHR0cHM6Ly9hcGkuYmlsaWJpbGkuY29t")
    val BILI_VC_API: String = d("aHR0cHM6Ly9hcGkudmMuYmlsaWJpbGkuY29t")
    val BILI_T: String = d("aHR0cHM6Ly90LmJpbGliaWxpLmNvbQ==")
    val BILI_WWW: String = d("aHR0cHM6Ly93d3cuYmlsaWJpbGkuY29t")
    val BILI_SPACE: String = d("aHR0cHM6Ly9zcGFjZS5iaWxpYmlsaS5jb20=")

    /** 原神官方 B 站账号 UID。 */
    const val BILI_GENSHIN_UID = "401742377"

    /** B 站客户端接口（appkey + appsecret 签名，无需登录）：官方投稿与专栏。 */
    val BILI_APP_API: String = d("aHR0cHM6Ly9hcHAuYmlsaWJpbGkuY29t")
    val BILI_APP_KEY: String = d("MWQ4YjZlN2Q0NTIzMzQzNg==")
    val BILI_APP_SECRET: String = d("NTYwYzUyY2NkMjg4ZmVkMDQ1ODU5ZWQxOGJmZmQ5NzM=")

    /** 设备指纹注册（公开数据接口，无需登录）。 */
    const val DEVICE_FP_URL = "https://public-data-api.mihoyo.com/device-fp/api/getFp"

    // 请求头模板
    const val UA_MOBILE = "okhttp/4.9.3"
    val UA_WEB: String get() =
        "Mozilla/5.0 (Linux; Android 12; Unspecified Device) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/103.0.5060.129 Mobile Safari/537.36 " +
            "miHoYoBBS/$BBS_VERSION"
    val UA_PASSPORT_APP: String get() =
        "Mozilla/5.0 miHoYoBBS/$PASSPORT_APP_VERSION Capture/2.2.0"
    val UA_DESKTOP: String get() =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) miHoYoBBS/$BBS_VERSION"
}
