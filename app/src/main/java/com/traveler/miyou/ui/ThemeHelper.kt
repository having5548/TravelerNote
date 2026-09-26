// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.pm.ActivityInfo
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import com.traveler.miyou.R
import com.traveler.miyou.net.BackgroundEngine
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

        // 启用磨砂/亚克力时让 surface 半透明，背景效果才透得出来。
        // 注意这里**只看材质**、不看有没有背景图：主题叠加只能"加"不能"撤"，
        // 如果跟着背景来源变，切来源时就得重建 Activity；现在无背景时底色是主题色纯色，
        // 半透明卡片压在上面也好看，切来源就不需要重新套主题了。
        if (settings.bgEffect > 0) {
            activity.theme.applyStyle(R.style.ThemeOverlay_TravelerNote_Glass, true)
        }

        // 非手机设备（平板等）固定竖屏，避免横屏平铺
        if (activity.resources.getBoolean(R.bool.is_tablet)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * 顶栏自适应（需要 setContentView 之后调用）：
     *
     * 按**顶栏那一条背后的实际明暗**决定标题/图标用深色还是浅色，并配上同向的渐变遮罩。
     * 以前右上角设置图标是矢量里写死的白色、标题跟主题走，于是"浅色壁纸 + 浅色主题"下
     * 图标完全看不见；现在浅背景给深色图标 + 白雾遮罩，深背景给白色图标 + 黑雾遮罩。
     *
     * 无背景图时按主题明暗兜底（此时底色是主题色纯色，不会再有纯白/黑底问题）。
     */
    fun applyToolbarContent(activity: AppCompatActivity) {
        val settings = SettingsStore(activity)
        val hasImage = settings.backgroundSource > 0
        val onDark = if (hasImage) {
            BackgroundEngine.toolbarOnDark ?: BackgroundEngine.isNight(activity)
        } else {
            BackgroundEngine.isNight(activity)
        }
        val content = if (onDark) Color.WHITE else activity.getColor(R.color.text_primary)

        // 状态栏/导航栏图标也跟着换向：深色壁纸 + 浅色主题时，深色图标压在黑雾上同样看不见
        runCatching {
            val controller = androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView
            )
            controller.isAppearanceLightStatusBars = !onDark
            controller.isAppearanceLightNavigationBars = !onDark
        }

        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        if (toolbar != null) {
            toolbar.setTitleTextColor(content)
            toolbar.navigationIcon?.mutate()?.setTint(content)
            for (i in 0 until toolbar.menu.size()) {
                toolbar.menu.getItem(i).icon?.mutate()?.setTint(content)
            }
            if (hasImage) {
                toolbar.setBackgroundResource(
                    if (onDark) R.drawable.toolbar_scrim_dark else R.drawable.toolbar_scrim
                )
                activity.window.statusBarColor =
                    activity.getColor(if (onDark) R.color.scrim_status_bar_dark else R.color.scrim_status_bar)
            } else {
                toolbar.background = null
                activity.window.statusBarColor = Color.TRANSPARENT
            }
        }
    }
}
