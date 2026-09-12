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
        val index = SettingsStore(activity).themeAccent
        val overlay = accentOverlays.getOrElse(index) { accentOverlays[0] }
        activity.theme.applyStyle(overlay, true)

        // 非手机设备（平板等）固定竖屏，避免横屏平铺
        if (activity.resources.getBoolean(R.bool.is_tablet)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
