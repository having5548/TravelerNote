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
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivitySettingsBinding
import com.traveler.miyou.net.BackgroundEngine
import com.traveler.miyou.store.SettingsStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore

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

        buildThemeRow()
        setupBackground()

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
            }
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

    /** 切换主题后强制重启应用以完整刷新主题。 */
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
