// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

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

    /** 背景材质：0=无（原图清晰） 1=磨砂（云母风格，图片保持清晰+雾面） 2=亚克力（轻模糊+雾面）。 */
    var bgEffect: Int
        get() = sp.getInt("bg_effect", 0)
        set(value) = sp.edit().putInt("bg_effect", value).apply()

    /** 磨砂程度（0-10）：雾面浓淡，与亚克力程度各自独立保存。 */
    var frostLevel: Int
        get() = sp.getInt("bg_frost_level", 3)
        set(value) = sp.edit().putInt("bg_frost_level", value).apply()

    /** 亚克力程度（0-10）：模糊与雾面强度，与磨砂程度各自独立保存。 */
    var acrylicLevel: Int
        get() = sp.getInt("bg_acrylic_level", 3)
        set(value) = sp.edit().putInt("bg_acrylic_level", value).apply()

    /** 是否已完成首次引导页（免责声明 + 隐私政策 + 输入同意语）。 */
    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(value) = sp.edit().putBoolean("onboarding_done", value).apply()

    var charSource: Int
        get() = sp.getInt("char_source", 0)
        set(value) = sp.edit().putInt("char_source", value).apply()

    // ---------------- 手表同步 ----------------

    /** 是否启用手表数据同步：总开关，控制保活服务与手表 requestNote 自动回发。 */
    var watchSyncEnabled: Boolean
        get() = sp.getBoolean("watch_sync_enabled", false)
        set(value) = sp.edit().putBoolean("watch_sync_enabled", value).apply()

    /** 保活方式：0=快捷磁贴（静默无感） 1=常驻通知 2=Shizuku 守护重启。 */
    var watchKeepAliveMode: Int
        get() = sp.getInt("watch_keep_alive_mode", 0)
        set(value) = sp.edit().putInt("watch_keep_alive_mode", value).apply()

    /** 手表连接状态自动刷新间隔（分钟）。 */
    var watchRefreshMinutes: Int
        get() = sp.getInt("watch_refresh_minutes", 5)
        set(value) = sp.edit().putInt("watch_refresh_minutes", value).apply()
}
