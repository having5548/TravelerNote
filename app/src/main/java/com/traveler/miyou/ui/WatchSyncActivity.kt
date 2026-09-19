// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivityWatchSyncBinding
import com.traveler.miyou.net.BackgroundEngine
import com.traveler.miyou.store.SettingsStore
import com.traveler.miyou.watch.ShizukuKeeper
import com.traveler.miyou.watch.WatchNoteSync
import com.traveler.miyou.watch.WatchSyncService
import com.traveler.miyou.watch.WatchSyncState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手表同步二级页：连接状态（含电量）、手表应用信息（版本/构建/存储）、
 * 数据同步开关、保活方式（磁贴/常驻通知/Shizuku）、状态自动刷新间隔。
 */
class WatchSyncActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 4301
        private const val REQUEST_SHIZUKU = 4302
    }

    private lateinit var binding: ActivityWatchSyncBinding
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityWatchSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        settings = SettingsStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        // 设置页也应用同一份背景与材质，保持视觉一致
        BackgroundEngine.apply(this)

        setupSend()
        setupSyncToggle()
        setupKeepAlive()
        setupInterval()
        observeStatus()

        binding.refreshInfoBtn.setOnClickListener {
            binding.appVersion.text = getString(R.string.watch_status_checking)
            WatchNoteSync.requestStorageInfo(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            WatchNoteSync.refreshStatus(applicationContext)
            if (WatchSyncState.status.value.connected) {
                WatchNoteSync.requestStorageInfo(applicationContext)
            }
        }
    }

    // ---------------- 状态展示 ----------------

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WatchSyncState.status.collect { render(it) }
            }
        }
    }

    private fun render(st: WatchSyncState.WatchStatus) {
        val dotColor = if (st.connected) getColor(R.color.expedition) else getColor(R.color.divider)
        (binding.statusDot.background as? android.graphics.drawable.GradientDrawable)?.setTint(dotColor)
        binding.statusTitle.text = if (st.connected) {
            getString(R.string.watch_status_connected_fmt, st.deviceName ?: "")
        } else {
            getString(R.string.watch_status_disconnected)
        }
        binding.batteryText.text =
            if (st.connected && st.battery in 0..100) getString(R.string.watch_battery_fmt, st.battery) else ""
        binding.statusDetail.text = buildString {
            append(
                when (st.installed) {
                    true -> getString(R.string.watch_installed_yes)
                    false -> getString(R.string.watch_installed_no)
                    null -> getString(R.string.watch_installed_unknown)
                }
            )
            if (st.lastCheck > 0) {
                append(" · ")
                append(
                    getString(
                        R.string.watch_last_check_fmt,
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(st.lastCheck))
                    )
                )
            }
        }
        binding.appVersion.text = st.versionName ?: getString(R.string.watch_unknown)
        binding.appBuild.text = st.buildTime ?: getString(R.string.watch_unknown)
        binding.appStorage.text =
            if (st.storageUsedKb >= 0) getString(R.string.watch_storage_fmt, st.storageUsedKb)
            else getString(R.string.watch_unknown)
    }

    // ---------------- 立即发送 ----------------

    private fun setupSend() {
        binding.sendBtn.setOnClickListener {
            binding.sendBtn.isEnabled = false
            binding.sendStatus.text = getString(R.string.watch_sending)
            lifecycleScope.launch {
                val result = WatchNoteSync.sendNow(applicationContext)
                binding.sendStatus.text = result.message
                binding.sendBtn.isEnabled = true
            }
        }
    }

    // ---------------- 同步开关 ----------------

    private fun setupSyncToggle() {
        binding.syncSwitch.isChecked = settings.watchSyncEnabled
        binding.syncSwitch.setOnCheckedChangeListener { _, checked ->
            settings.watchSyncEnabled = checked
            if (checked) {
                runCatching { WatchSyncService.start(applicationContext) }
            } else {
                WatchSyncService.stop(applicationContext)
            }
        }
    }

    // ---------------- 保活方式 ----------------

    private fun setupKeepAlive() {
        val modeChips = mapOf(
            binding.modeTile.id to 0, binding.modeNotify.id to 1, binding.modeShizuku.id to 2
        )
        binding.modeGroup.check(
            modeChips.entries.firstOrNull { it.value == settings.watchKeepAliveMode }?.key
                ?: binding.modeTile.id
        )
        updateModeDesc(settings.watchKeepAliveMode)

        binding.modeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val mode = modeChips[checkedId] ?: return@setOnCheckedStateChangeListener
            if (mode == settings.watchKeepAliveMode) return@setOnCheckedStateChangeListener
            applyKeepAliveMode(mode)
        }
    }

    private fun applyKeepAliveMode(mode: Int) {
        when (mode) {
            0 -> settings.watchKeepAliveMode = 0
            1 -> {
                ensureNotificationPermission()
                settings.watchKeepAliveMode = 1
            }
            2 -> {
                if (!ShizukuKeeper.available()) {
                    updateModeDesc(0)
                    binding.modeGroup.check(binding.modeTile.id)
                    Toast.makeText(this, R.string.watch_mode_shizuku_missing, Toast.LENGTH_LONG).show()
                    return
                }
                if (!ShizukuKeeper.granted()) {
                    updateModeDesc(2)
                    binding.modeGroup.check(binding.modeShizuku.id)
                    requestShizuku()
                    return
                }
                ensureNotificationPermission()
                settings.watchKeepAliveMode = 2
            }
        }
        updateModeDesc(settings.watchKeepAliveMode)
        // 服务运行中则重启，立即套用新的通知呈现方式与看门狗配置
        if (settings.watchSyncEnabled && WatchSyncService.running) {
            WatchSyncService.stop(applicationContext)
            runCatching { WatchSyncService.start(applicationContext) }
        }
    }

    private fun updateModeDesc(mode: Int) {
        binding.modeDesc.setText(
            when (mode) {
                1 -> R.string.watch_mode_notify_desc
                2 -> R.string.watch_mode_shizuku_desc
                else -> R.string.watch_mode_tile_desc
            }
        )
    }

    // ---------------- 刷新间隔 ----------------

    private fun setupInterval() {
        val intervals = mapOf(
            binding.interval1.id to 1, binding.interval2.id to 2, binding.interval5.id to 5,
            binding.interval10.id to 10, binding.interval30.id to 30, binding.interval60.id to 60
        )
        binding.intervalGroup.check(
            intervals.entries.firstOrNull { it.value == settings.watchRefreshMinutes }?.key
                ?: binding.interval5.id
        )
        binding.intervalGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val minutes = intervals[checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener]
                ?: return@setOnCheckedStateChangeListener
            if (minutes != settings.watchRefreshMinutes) {
                settings.watchRefreshMinutes = minutes
                // 服务运行中则重启，让看门狗与刷新循环立刻按新间隔工作
                if (settings.watchSyncEnabled && WatchSyncService.running) {
                    WatchSyncService.stop(applicationContext)
                    runCatching { WatchSyncService.start(applicationContext) }
                }
            }
        }
    }

    // ---------------- 权限 ----------------

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    private fun requestShizuku() {
        if (!ShizukuKeeper.available()) return
        Toast.makeText(this, R.string.watch_mode_shizuku_wait, Toast.LENGTH_SHORT).show()
        ShizukuKeeper.requestPermission(REQUEST_SHIZUKU)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.watch_mode_notify)
                .setMessage(R.string.watch_notif_permission_needed)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }
}
