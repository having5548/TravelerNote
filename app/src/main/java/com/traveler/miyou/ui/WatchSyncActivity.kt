// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.Manifest
import android.app.AlarmManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import rikka.shizuku.Shizuku
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

    /** Shizuku 授权结果回调：授权通过后按用户选择的「守护」模式真正生效。 */
    private var shizukuListener: Shizuku.OnRequestPermissionResultListener? = null

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
        setupDiagnostics()
        setupShizukuListener()
        observeStatus()
        // 一进页面就确认通知权限：保活通知显示不出来（Android 13+）多半是这个没给
        ensureNotificationPermission()

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
        // 从系统设置页回来时，权限状态可能变了
        binding.root.post { renderDiag() }
    }

    override fun onDestroy() {
        shizukuListener?.let { runCatching { Shizuku.removeRequestPermissionResultListener(it) } }
        shizukuListener = null
        super.onDestroy()
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
        // SDK 查不到电量时会返回 0：这里按"未知"处理，避免显示"电量 0%"
        binding.batteryText.text = when {
            !st.connected -> ""
            st.battery !in 1..100 -> getString(R.string.watch_battery_unknown)
            st.charging -> getString(R.string.watch_battery_fmt, st.battery) +
                getString(R.string.watch_charging_suffix)
            else -> getString(R.string.watch_battery_fmt, st.battery)
        }
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
        renderDiag()
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
                // 先把模式落库：以前这里在"还没授权"分支直接 return，等于 Shizuku 模式永远不生效
                settings.watchKeepAliveMode = 2
                updateModeDesc(2)
                if (!ShizukuKeeper.granted()) {
                    requestShizuku()
                    return
                }
                ensureNotificationPermission()
            }
        }
        updateModeDesc(settings.watchKeepAliveMode)
        // 服务运行中则重启，立即套用新的通知呈现方式与看门狗配置
        restartService()
        renderDiag()
    }

    private fun restartService() {
        if (!settings.watchSyncEnabled) return
        WatchSyncService.stop(applicationContext)
        runCatching { WatchSyncService.start(applicationContext) }
    }

    // ---------------- 保活自检与系统设置引导 ----------------

    private fun setupDiagnostics() {
        binding.diagTileBtn.setOnClickListener { addTile() }
        binding.diagBatteryBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.diagExactBtn.setOnClickListener { requestExactAlarm() }
        binding.diagAutostartBtn.setOnClickListener { openAppSettings() }
        renderDiag()
    }

    /** 保活自检：把服务状态与各项系统权限摆出来，出问题时一眼能看出断在哪一步。 */
    private fun renderDiag() {
        if (!::binding.isInitialized) return
        val st = WatchSyncState.status.value
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = listOf(
            when {
                WatchSyncService.running -> getString(R.string.watch_diag_service_on)
                st.startError != null -> getString(R.string.watch_diag_service_failed, st.startError)
                else -> getString(R.string.watch_diag_service_off)
            },
            if (notificationsGranted()) getString(R.string.watch_diag_notify_ok)
            else getString(R.string.watch_diag_notify_no),
            if (ignoringBatteryOptimizations()) getString(R.string.watch_diag_battery_ok)
            else getString(R.string.watch_diag_battery_no),
            if (exactAlarmAllowed()) getString(R.string.watch_diag_exact_ok)
            else getString(R.string.watch_diag_exact_no),
            when {
                !ShizukuKeeper.available() -> getString(R.string.watch_diag_shizuku_missing)
                !ShizukuKeeper.granted() -> getString(R.string.watch_diag_shizuku_denied)
                else -> getString(R.string.watch_diag_shizuku_ok)
            },
            when {
                st.noteError != null && st.notePushedAt > 0 -> getString(
                    R.string.watch_diag_push_fail, fmt.format(Date(st.notePushedAt)), st.noteError
                )
                st.notePushedAt > 0 -> getString(R.string.watch_diag_push_ok, fmt.format(Date(st.notePushedAt)))
                else -> getString(R.string.watch_diag_push_none)
            }
        )
        binding.diagText.text = lines.joinToString("\n")
        binding.syncSwitch.isChecked = settings.watchSyncEnabled
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimizations(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    }.getOrDefault(false)

    private fun exactAlarmAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            }.getOrDefault(false)
        } else {
            true
        }

    /** 一键把磁贴加进下拉快捷设置（Android 13+ 才支持，低版本给手动引导）。 */
    private fun addTile() {
        val sbm = if (Build.VERSION.SDK_INT >= 33) getSystemService(StatusBarManager::class.java) else null
        if (sbm == null) {
            Toast.makeText(this, R.string.watch_tile_add_manual, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            sbm.requestAddTileService(
                ComponentName(this, com.traveler.miyou.watch.WatchSyncTileService::class.java),
                getString(R.string.watch_service_title),
                Icon.createWithResource(this, R.drawable.ic_sign),
                mainExecutor
            ) { result ->
                // 只有"已添加/本来就在"才算成功，其余情况给手动引导
                val ok = result?.toInt() == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result?.toInt() == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                Toast.makeText(
                    this,
                    if (ok) R.string.watch_tile_add_requested else R.string.watch_tile_add_manual,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onFailure {
            Toast.makeText(this, R.string.watch_tile_add_manual, Toast.LENGTH_LONG).show()
        }
    }

    /** 申请忽略电池优化：MIUI/HyperOS 上这是保活最关键的一步。 */
    private fun requestIgnoreBatteryOptimizations() {
        if (ignoringBatteryOptimizations()) {
            Toast.makeText(this, R.string.watch_battery_opt_done, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.isSuccess
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** 申请精确闹钟（看门狗到点能准时唤醒）。 */
    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < 31 || exactAlarmAllowed()) {
            Toast.makeText(this, R.string.watch_exact_alarm_done, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    /** 跳到本应用的系统设置页：自启动 / 省电策略都在那里改。 */
    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    /** Shizuku 授权结果：授权通过就按"守护"模式生效，拒绝就退回磁贴模式。 */
    private fun setupShizukuListener() {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            settings.watchKeepAliveMode = if (granted) 2 else 0
            runOnUiThread {
                binding.modeGroup.check(if (granted) binding.modeShizuku.id else binding.modeTile.id)
                updateModeDesc(settings.watchKeepAliveMode)
                Toast.makeText(
                    this,
                    if (granted) R.string.watch_mode_shizuku_desc else R.string.watch_mode_shizuku_missing,
                    Toast.LENGTH_LONG
                ).show()
                if (granted) restartService()
                renderDiag()
            }
        }
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        shizukuListener = listener
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
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            renderDiag()
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.watch_mode_notify)
                    .setMessage(R.string.watch_notif_permission_needed)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }
}
