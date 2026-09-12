// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivityOnboardingBinding
import com.traveler.miyou.store.SettingsStore

/**
 * 首次进入的引导页：完整的免责声明 + 隐私政策，
 * 底部要求用户**手动输入**同意语（必须完全一致）才能继续，避免"一路点确定"。
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 输入法弹出时把底部输入区整体顶上去，否则输入框被键盘挡住看不到自己打的字
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }

        val expected = getString(R.string.onboarding_agree_phrase)
        binding.onboardingPhrase.text = expected
        binding.onboardingAgree.isEnabled = false

        binding.onboardingInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val matched = s?.toString()?.trim() == expected
                binding.onboardingAgree.isEnabled = matched
                if (matched) binding.onboardingInputLayout.error = null
            }
        })

        binding.onboardingAgree.setOnClickListener {
            if (binding.onboardingInput.text?.toString()?.trim() != expected) {
                binding.onboardingInputLayout.error = getString(R.string.onboarding_agree_mismatch)
                return@setOnClickListener
            }
            SettingsStore(this).onboardingDone = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.onboardingDisagree.setOnClickListener {
            finishAffinity()
        }
    }
}
