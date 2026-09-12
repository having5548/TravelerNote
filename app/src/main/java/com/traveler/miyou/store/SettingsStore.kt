package com.traveler.miyou.store

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置。
 */
class SettingsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var seamlessCaptcha: Boolean
        get() = sp.getBoolean("seamless_captcha", false)
        set(value) = sp.edit().putBoolean("seamless_captcha", value).apply()

    /**
     * 打码接口地址。留空表示未配置，此时无感验证自动回退到网页手动验证。
     * 不预置任何第三方地址：本应用全局禁用明文流量（usesCleartextTraffic=false），
     * 只有 https 地址才可能真正生效。
     */
    var captchaApi: String
        get() = sp.getString("captcha_api", "") ?: ""
        set(value) = sp.edit().putString("captcha_api", value).apply()

    var captchaUserkey: String
        get() = sp.getString("captcha_userkey", "") ?: ""
        set(value) = sp.edit().putString("captcha_userkey", value).apply()

    var themeAccent: Int
        get() = sp.getInt("theme_accent", 0)
        set(value) = sp.edit().putInt("theme_accent", value).apply()

    var backgroundSource: Int
        get() = sp.getInt("background_source", 0)
        set(value) = sp.edit().putInt("background_source", value).apply()

    var disclaimerShown: Boolean
        get() = sp.getBoolean("disclaimer_shown", false)
        set(value) = sp.edit().putBoolean("disclaimer_shown", value).apply()

    var charSource: Int
        get() = sp.getInt("char_source", 0)
        set(value) = sp.edit().putInt("char_source", value).apply()
}
