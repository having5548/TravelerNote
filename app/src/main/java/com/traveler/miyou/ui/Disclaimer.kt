// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traveler.miyou.R
import com.traveler.miyou.store.SettingsStore

/** 首次启动免责声明弹窗（仅一次）。 */
object Disclaimer {

    fun showOnce(activity: AppCompatActivity) {
        val settings = SettingsStore(activity)
        if (settings.disclaimerShown) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer)
            .setCancelable(false)
            .setPositiveButton(R.string.agree) { _, _ ->
                settings.disclaimerShown = true
            }
            .setNegativeButton(R.string.disagree) { _, _ ->
                activity.finish()
            }
            .show()
    }
}
