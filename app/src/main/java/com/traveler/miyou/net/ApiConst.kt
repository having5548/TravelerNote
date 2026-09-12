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
