// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivitySettingsBinding
import com.traveler.miyou.net.BackgroundEngine
import com.traveler.miyou.store.AccountRefresher
import com.traveler.miyou.store.AccountStore
import com.traveler.miyou.store.CookieStore
import com.traveler.miyou.store.SettingsStore
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore
    private lateinit var accounts: AccountStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)


        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        settings = SettingsStore(this)
        accounts = AccountStore(this)

        setupAccounts()
        buildThemeRow()
        setupBackground()
        setupBackgroundEffect()
        setupWatchSync()
        // 设置页也应用同一份背景与材质，避免只有主界面"全屏"、进来就断掉；
        // 背景就绪后按它的明暗给工具栏（返回箭头/标题）选对比色
        BackgroundEngine.apply(this) { ThemeHelper.applyToolbarContent(this) }
        ThemeHelper.applyToolbarContent(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.seamlessSwitch.isChecked = settings.seamlessCaptcha
        binding.apiInput.setText(settings.captchaApi)
        binding.userkeyInput.setText(settings.captchaUserkey)
        updateCaptchaFieldsVisibility()

        binding.seamlessSwitch.setOnCheckedChangeListener { _, checked ->
            settings.seamlessCaptcha = checked
            updateCaptchaFieldsVisibility()
            // 开启后立刻校验配置，避免"开关打开但实际什么都没配"
            if (checked) validateCaptchaConfig() else clearCaptchaErrors()
        }
        binding.apiInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistCaptchaApi()
        }
        binding.userkeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) settings.captchaUserkey = binding.userkeyInput.text?.toString()?.trim() ?: ""
        }

        binding.githubRow.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/having5548")))
            }
        }
    }

    // ---------------- 账户（多账号） ----------------

    private fun setupAccounts() {
        renderAccounts()

        binding.accountRefreshBtn.setOnClickListener {
            binding.accountRefreshBtn.isEnabled = false
            lifecycleScope.launch {
                val count = AccountRefresher.refreshAll(this@SettingsActivity)
                binding.accountRefreshBtn.isEnabled = true
                renderAccounts()
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.account_refresh)
                    .setMessage(getString(R.string.account_refresh_done, count))
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }

        binding.accountAddBtn.setOnClickListener {
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_ADD_ACCOUNT, true)
            )
        }

        binding.logoutBtn.setOnClickListener { confirmLogout() }
    }

    private fun renderAccounts() {
        val active = accounts.activeId()
        binding.accountActive.text =
            if (active.isNullOrBlank()) getString(R.string.account_none) else accounts.label(active)

        binding.accountList.removeAllViews()
        accounts.ids().forEach { id ->
            val row = layoutInflater.inflate(R.layout.item_account, binding.accountList, false)
            row.findViewById<TextView>(R.id.accountLabel).text = accounts.label(id)
            row.findViewById<TextView>(R.id.accountState).text = when {
                !accounts.hasCredential(id) -> getString(R.string.account_no_credential)
                id == active -> getString(R.string.account_active)
                else -> ""
            }
            row.setOnClickListener {
                if (id != active) {
                    accounts.setActive(id)
                    // 账号变了，所有页面都要重新读凭证：重启应用最稳妥
                    restartApp()
                }
            }
            row.findViewById<TextView>(R.id.accountDelete).setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.account_delete)
                    .setMessage(getString(R.string.account_delete_confirm, accounts.label(id)))
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        val wasActive = id == active
                        accounts.remove(id)
                        if (wasActive && accounts.activeId().isNullOrBlank()) {
                            startActivity(Intent(this, LoginActivity::class.java))
                            finishAffinity()
                        } else {
                            renderAccounts()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            binding.accountList.addView(row)
        }
    }

    private fun confirmLogout() {
        val active = accounts.activeId()
        if (active.isNullOrBlank()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_active)
            .setMessage(getString(R.string.logout_confirm, accounts.label(active)))
            .setPositiveButton(R.string.confirm) { _, _ ->
                CookieStore(applicationContext, active).clear()
                accounts.remove(active)
                val next = accounts.ids().firstOrNull { accounts.hasCredential(it) }
                if (next == null) {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finishAffinity()
                } else {
                    accounts.setActive(next)
                    restartApp()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 背景 ----------------

    private fun setupBackground() {
        val radios = intArrayOf(
            binding.bg0.id, binding.bg1.id, binding.bg2.id
        )
        binding.bgGroup.check(radios.getOrElse(settings.backgroundSource) { radios[0] })
        binding.bgGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = radios.indexOfFirst { it == checkedId }
            if (index >= 0 && settings.backgroundSource != index) {
                settings.backgroundSource = index
                // 作废该来源旧缓存，返回主界面时立即拉新图（实时生效）
                BackgroundEngine.invalidate(this, index)
                // 当前这一页也立刻跟着变，不用退出去才看到效果
                BackgroundEngine.apply(this) { ThemeHelper.applyToolbarContent(this) }
            }
        }
    }

    /**
     * 背景材质：磨砂 / 亚克力二选一，且两种材质各有自己的程度滑杆
     * （互不影响，切回另一种时保留上次调好的程度）。
     */
    private fun setupBackgroundEffect() {
        val radios = intArrayOf(binding.effect0.id, binding.effect1.id, binding.effect2.id)
        val max = BackgroundEngine.MAX_LEVEL.toFloat()

        binding.effectGroup.check(radios.getOrElse(settings.bgEffect) { radios[0] })
        binding.frostSlider.valueTo = max
        binding.acrylicSlider.valueTo = max
        binding.frostSlider.value = settings.frostLevel.toFloat().coerceIn(0f, max)
        binding.acrylicSlider.value = settings.acrylicLevel.toFloat().coerceIn(0f, max)
        updateEffectVisibility()

        binding.effectGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = radios.indexOfFirst { it == checkedId }
            if (index >= 0 && settings.bgEffect != index) {
                val hadGlass = settings.bgEffect > 0
                settings.bgEffect = index
                updateEffectVisibility()
                // 材质开关会切换 surface 是否半透明，而主题叠加只能加不能撤 ——
                // 跨过"无材质 ↔ 有材质"这条线时重建一次，省得用户以为没生效
                if (hadGlass != (index > 0)) restartApp()
            }
        }
        binding.frostSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) settings.frostLevel = value.toInt()
        }
        binding.acrylicSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) settings.acrylicLevel = value.toInt()
        }
    }

    private fun updateEffectVisibility() {
        binding.frostSlider.visibility = if (settings.bgEffect == 1) View.VISIBLE else View.GONE
        binding.acrylicSlider.visibility = if (settings.bgEffect == 2) View.VISIBLE else View.GONE
    }

    // ---------------- 手表同步 ----------------

    /**
     * 设置列表里的手表同步行：**只作为入口**（同步开关已挪到手表同步页顶部，避免两处重复），
     * 点整行回到主界面并切到「手表同步」页签。
     */
    private fun setupWatchSync() {
        binding.watchEntryRow.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_WATCH)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun updateCaptchaFieldsVisibility() {
        val visible = if (settings.seamlessCaptcha) View.VISIBLE else View.GONE
        binding.apiLayout.visibility = visible
        binding.userkeyLayout.visibility = visible
    }

    private fun buildThemeRow() {
        val colors = intArrayOf(
            R.color.accent_blue, R.color.accent_teal, R.color.accent_purple,
            R.color.accent_rose, R.color.accent_orange, R.color.accent_green
        )
        val selected = settings.themeAccent
        colors.forEachIndexed { index, colorRes ->
            val dot = FrameLayout(this)
            val lp = android.widget.LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(14)
            }
            dot.layoutParams = lp
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(colorRes))
            }
            dot.background = bg

            if (index == selected) {
                val check = TextView(this).apply {
                    text = "✓"
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                }
                dot.addView(check, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            dot.setOnClickListener {
                if (settings.themeAccent != index) {
                    settings.themeAccent = index
                    restartApp()
                }
            }
            binding.themeRow.addView(dot)
        }
    }

    /** 切换主题/账号后强制重启应用以完整刷新。 */
    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        finishAffinity()
        startActivity(intent)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        persistCaptchaApi()
        settings.captchaUserkey = binding.userkeyInput.text?.toString()?.trim() ?: ""
    }

    /** 保存打码接口地址：只接受空值或 https 地址（明文流量已被 Manifest 全局禁用）。 */
    private fun persistCaptchaApi() {
        if (!validateCaptchaConfig()) return
        settings.captchaApi = binding.apiInput.text?.toString()?.trim() ?: ""
    }

    /** 校验打码配置；开关打开时要求两项都填写。返回配置是否可用。 */
    private fun validateCaptchaConfig(): Boolean {
        val api = binding.apiInput.text?.toString()?.trim() ?: ""
        val userkey = binding.userkeyInput.text?.toString()?.trim() ?: ""
        val requireAll = settings.seamlessCaptcha
        val apiError = when {
            api.isNotEmpty() && !api.startsWith("https://", ignoreCase = true) ->
                getString(R.string.captcha_api_https_required)
            requireAll && api.isEmpty() -> getString(R.string.captcha_api_required)
            else -> null
        }
        binding.apiLayout.error = apiError
        binding.userkeyLayout.error =
            if (requireAll && userkey.isEmpty()) getString(R.string.captcha_userkey_required) else null
        return apiError == null
    }

    private fun clearCaptchaErrors() {
        binding.apiLayout.error = null
        binding.userkeyLayout.error = null
    }
}
