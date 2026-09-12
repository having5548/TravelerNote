// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatActivity
import com.traveler.miyou.R
import com.traveler.miyou.store.SettingsStore

object ThemeHelper {

    val accentOverlays = intArrayOf(
        R.style.ThemeOverlay_TravelerNote_Blue,
        R.style.ThemeOverlay_TravelerNote_Teal,
        R.style.ThemeOverlay_TravelerNote_Purple,
        R.style.ThemeOverlay_TravelerNote_Rose,
        R.style.ThemeOverlay_TravelerNote_Orange,
        R.style.ThemeOverlay_TravelerNote_Green
    )

    fun apply(activity: AppCompatActivity) {
        val settings = SettingsStore(activity)
        val index = settings.themeAccent
        val overlay = accentOverlays.getOrElse(index) { accentOverlays[0] }
        activity.theme.applyStyle(overlay, true)

        // 启用磨砂/亚克力时让 surface 半透明，背景效果才透得出来
        if (settings.bgEffect > 0) {
            activity.theme.applyStyle(R.style.ThemeOverlay_TravelerNote_Glass, true)
        }

        // 非手机设备（平板等）固定竖屏，避免横屏平铺
        if (activity.resources.getBoolean(R.bool.is_tablet)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * 有背景图时给顶部加一层渐变遮罩，否则左上角返回箭头/标题会被照片"吃掉"。
     * 状态栏也压一层同色薄雾，让"背景材质全屏"到状态栏是连续的、不突兀。
     * 需要在 setContentView 之后调用。
     */
    fun applyToolbarScrim(activity: AppCompatActivity) {
        if (SettingsStore(activity).backgroundSource <= 0) return
        activity.findViewById<android.view.View>(R.id.toolbar)
            ?.setBackgroundResource(R.drawable.toolbar_scrim)
        activity.window.statusBarColor = activity.getColor(R.color.scrim_status_bar)
    }
}
