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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traveler.miyou.R
import com.traveler.miyou.databinding.FragmentWatchSyncBinding
import com.traveler.miyou.store.AccountStore
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
 * 手表同步页（**页内 Fragment**，不再是独立 Activity）：
 * 从主界面底部栏「手表同步」切过来，跟其它页签一样只切可见性 + 淡入上移动画，
 * 不会像以前那样直接拉起一个新界面。
 *
 * 内容：连接状态（含电量）、手表应用信息、同步开关、保活方式、保活自检与系统设置引导。
 */
class WatchSyncFragment : Fragment() {

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 4301
        private const val REQUEST_SHIZUKU = 4302
    }

    private var _binding: FragmentWatchSyncBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsStore

    /** Shizuku 授权结果回调：授权通过后按用户选择的「守护」模式真正生效。 */
    private var shizukuListener: Shizuku.OnRequestPermissionResultListener? = null

    private val appContext get() = requireContext().applicationContext

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWatchSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = SettingsStore(requireContext())

        setupSyncToggle()
        setupKeepAlive()
        setupInterval()
        setupDiagnostics()
        setupShizukuListener()
        observeStatus()
        // 注意：通知权限不在这里申请 —— 这个 Fragment 会随主界面一起创建（只是 GONE），
        // 放在这里会导致"一启动应用就弹权限框"。改到真正切到本页时再申请（见 onPageShown）。

        binding.refreshInfoBtn.setOnClickListener {
            binding.appVersion.text = getString(R.string.watch_status_checking)
            WatchNoteSync.requestStorageInfo(appContext)
        }
        // 视图刚建好时先查一次连接状态（不申请权限，避免应用一启动就弹框）
        refreshStatusNow()
    }

    /** 页面被切到前台时由主界面调用（替代原来 Activity 的 onResume）。 */
    fun onPageShown() {
        if (_binding == null) return
        // 第一次真正进入本页时确认通知权限：保活通知显示不出来（Android 13+）多半是这个没给
        ensureNotificationPermission()
        refreshStatusNow()
        // 从系统设置页回来时，权限状态可能变了
        binding.root.post { renderDiag() }
    }

    /** 刷新手表连接状态（连上时顺带问一次手表端应用信息）。 */
    private fun refreshStatusNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            WatchNoteSync.refreshStatus(appContext)
            if (WatchSyncState.status.value.connected) {
                WatchNoteSync.requestStorageInfo(appContext)
            }
        }
    }

    override fun onDestroyView() {
        shizukuListener?.let { runCatching { Shizuku.removeRequestPermissionResultListener(it) } }
        shizukuListener = null
        _binding = null
        super.onDestroyView()
    }

    // ---------------- 状态展示 ----------------

    private fun observeStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WatchSyncState.status.collect { render(it) }
            }
        }
    }

    private fun render(st: WatchSyncState.WatchStatus) {
        if (_binding == null) return
        val dotColor =
            if (st.connected) requireContext().getColor(R.color.expedition)
            else requireContext().getColor(R.color.divider)
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

    // ---------------- 同步开关 ----------------

    private fun setupSyncToggle() {
        binding.syncSwitch.isChecked = settings.watchSyncEnabled
        binding.syncSwitch.setOnCheckedChangeListener { _, checked ->
            settings.watchSyncEnabled = checked
            if (checked) {
                runCatching { WatchSyncService.start(appContext) }
            } else {
                WatchSyncService.stop(appContext)
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
                    toast(R.string.watch_mode_shizuku_missing, Toast.LENGTH_LONG)
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
        WatchSyncService.stop(appContext)
        runCatching { WatchSyncService.start(appContext) }
    }

    // ---------------- 保活自检与系统设置引导 ----------------

    private fun setupDiagnostics() {
        binding.diagTileBtn.setOnClickListener { addTile() }
        binding.diagBatteryBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.diagExactBtn.setOnClickListener { requestExactAlarm() }
        binding.diagAutostartBtn.setOnClickListener { openAppSettings() }
        binding.warnBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
        renderDiag()
    }

    /** 保活自检：把服务状态与各项系统权限摆出来，出问题时一眼能看出断在哪一步。 */
    private fun renderDiag() {
        if (_binding == null) return
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
            },
            st.accountsSummary?.let { getString(R.string.watch_diag_accounts_fmt, it) }
                ?: getString(R.string.watch_diag_accounts_fmt, localAccountSummary()),
            when {
                st.preparing -> getString(R.string.watch_diag_watch_preparing)
                st.connected -> getString(R.string.watch_diag_watch_connected)
                else -> getString(R.string.watch_diag_watch_waiting)
            }
        )
        binding.diagText.text = lines.joinToString("\n")
        binding.syncSwitch.isChecked = settings.watchSyncEnabled
        // 没加白名单就在顶部直接警告：清后台掉线基本都是这个原因
        binding.warnCard.visibility =
            if (ignoringBatteryOptimizations()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(
            requireContext(), Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimizations(): Boolean = runCatching {
        requireContext().getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
    }.getOrDefault(false)

    /**
     * 拉取模式下手表能看到哪些账号：直接按当前账号列表算（最多 5 个，与手表端上限一致），
     * 不再依赖"最近一次推送"。
     */
    private fun localAccountSummary(): String {
        val accounts = AccountStore(requireContext())
        val labels = accounts.ids()
            .take(WatchNoteSync.MAX_WATCH_ACCOUNTS)
            .map { accounts.label(it).substringBefore("（").trim() }
            .filter { it.isNotBlank() }
        return labels.joinToString("、").ifBlank { getString(R.string.watch_diag_accounts_none) }
    }

    private fun exactAlarmAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                requireContext().getSystemService(AlarmManager::class.java)
                    ?.canScheduleExactAlarms() == true
            }.getOrDefault(false)
        } else {
            true
        }

    /** 一键把磁贴加进下拉快捷设置（Android 13+ 才支持，低版本给手动引导）。 */
    private fun addTile() {
        val ctx = requireContext()
        val sbm = if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(StatusBarManager::class.java)
        } else {
            null
        }
        if (sbm == null) {
            toast(R.string.watch_tile_add_manual, Toast.LENGTH_LONG)
            return
        }
        runCatching {
            sbm.requestAddTileService(
                ComponentName(ctx, com.traveler.miyou.watch.WatchSyncTileService::class.java),
                getString(R.string.watch_service_title),
                Icon.createWithResource(ctx, R.drawable.ic_sign),
                ctx.mainExecutor
            ) { result ->
                // 只有"已添加/本来就在"才算成功，其余情况给手动引导
                val ok = result?.toInt() == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result?.toInt() == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                toast(
                    if (ok) R.string.watch_tile_add_requested else R.string.watch_tile_add_manual,
                    Toast.LENGTH_LONG
                )
            }
        }.onFailure {
            toast(R.string.watch_tile_add_manual, Toast.LENGTH_LONG)
        }
    }

    /** 申请忽略电池优化：MIUI/HyperOS 上这是保活最关键的一步。 */
    private fun requestIgnoreBatteryOptimizations() {
        if (ignoringBatteryOptimizations()) {
            toast(R.string.watch_battery_opt_done, Toast.LENGTH_SHORT)
            return
        }
        val pkg = requireContext().packageName
        val ok = runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$pkg"))
            )
        }.isSuccess
        if (!ok) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** 申请精确闹钟（看门狗到点能准时唤醒）。 */
    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < 31 || exactAlarmAllowed()) {
            toast(R.string.watch_exact_alarm_done, Toast.LENGTH_SHORT)
            return
        }
        val pkg = requireContext().packageName
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$pkg"))
            )
        }
    }

    /** 跳到本应用的系统设置页：自启动 / 省电策略都在那里改。 */
    private fun openAppSettings() {
        val pkg = requireContext().packageName
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$pkg"))
            )
        }
    }

    /** Shizuku 授权结果：授权通过就按"守护"模式生效，拒绝就退回磁贴模式。 */
    private fun setupShizukuListener() {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            settings.watchKeepAliveMode = if (granted) 2 else 0
            view?.post {
                if (_binding == null) return@post
                binding.modeGroup.check(if (granted) binding.modeShizuku.id else binding.modeTile.id)
                updateModeDesc(settings.watchKeepAliveMode)
                toast(
                    if (granted) R.string.watch_mode_shizuku_desc else R.string.watch_mode_shizuku_missing,
                    Toast.LENGTH_LONG
                )
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
                // 服务运行中则重启，让看门狗立刻按新间隔工作
                if (settings.watchSyncEnabled && WatchSyncService.running) {
                    WatchSyncService.stop(appContext)
                    runCatching { WatchSyncService.start(appContext) }
                }
            }
        }
    }

    // ---------------- 权限 ----------------

    private fun toast(resId: Int, duration: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), resId, duration).show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun requestShizuku() {
        if (!ShizukuKeeper.available()) return
        toast(R.string.watch_mode_shizuku_wait, Toast.LENGTH_SHORT)
        ShizukuKeeper.requestPermission(REQUEST_SHIZUKU)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            renderDiag()
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED && isAdded) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.watch_mode_notify)
                    .setMessage(R.string.watch_notif_permission_needed)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
    }
}
