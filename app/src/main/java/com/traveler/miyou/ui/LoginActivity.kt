package com.traveler.miyou.ui

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivityLoginBinding
import com.traveler.miyou.net.MiyouApi
import com.traveler.miyou.net.parseAigis
import com.traveler.miyou.store.CookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var store: CookieStore
    private var qrJob: Job? = null

    private var actionType: String = ""
    private var aigisValue: String = ""

    private var captchaContinuation: kotlin.coroutines.Continuation<String?>? = null
    private val captchaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val validate = result.data?.getStringExtra("validate")
            captchaContinuation?.resume(validate)
            captchaContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)


        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }

        store = CookieStore(this)

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showQrPanel()
                    else -> showPhonePanel()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: TabLayout.Tab?) = Unit
        })

        binding.sendCodeBtn.setOnClickListener { sendCode() }
        binding.loginBtn.setOnClickListener { doLogin() }
        binding.manualCookieBtn.setOnClickListener { showManualDialog() }

        binding.tabs.getTabAt(0)?.select()
        Disclaimer.showOnce(this)
    }

    private fun showQrPanel() {
        binding.panelQr.visibility = View.VISIBLE
        binding.panelPhone.visibility = View.GONE
        startQrLogin()
    }

    private fun showPhonePanel() {
        qrJob?.cancel()
        binding.panelQr.visibility = View.GONE
        binding.panelPhone.visibility = View.VISIBLE
    }

    // ---------- 二维码登录 ----------

    private fun startQrLogin() {
        qrJob?.cancel()
        qrJob = lifecycleScope.launch {
            setQrStatus(getString(R.string.qr_waiting))
            binding.qrLoading.visibility = View.VISIBLE
            binding.qrImage.setImageDrawable(null)

            val deviceId = store.deviceId()
            val deviceFp = store.deviceFp()
            val deviceName = store.deviceName()
            val deviceModel = store.deviceModel()

            val ticket = withContext(Dispatchers.IO) {
                MiyouApi.createQrLogin(deviceId, deviceFp, deviceName, deviceModel)
            }
            if (ticket == null || ticket.url.isBlank() || ticket.ticket.isBlank()) {
                setQrStatus(getString(R.string.network_error))
                binding.qrLoading.visibility = View.GONE
                return@launch
            }

            val sizePx = 512
            val bmp = withContext(Dispatchers.Default) { Qr.bitmap(ticket.url, sizePx) }
            binding.qrImage.setImageBitmap(bmp)
            binding.qrLoading.visibility = View.GONE
            setQrStatus(getString(R.string.qr_scan_hint))

            val deadline = System.currentTimeMillis() + 120_000
            var lastStatus = ""
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1500)
                val st = withContext(Dispatchers.IO) {
                    MiyouApi.queryQrLogin(ticket.ticket, deviceId, deviceFp, deviceName, deviceModel)
                } ?: continue
                if (st.status != lastStatus) {
                    lastStatus = st.status
                    when (st.status) {
                        "Scanned" -> setQrStatus(getString(R.string.qr_scanned))
                        "Confirmed" -> {
                            setQrStatus(getString(R.string.qr_confirmed))
                            if (st.stoken.isNotBlank() && st.mid.isNotBlank()) {
                                onLoginTokens(st.stoken, st.mid, st.stuid)
                                return@launch
                            }
                        }
                    }
                }
            }
            setQrStatus(getString(R.string.qr_expired))
            delay(1200)
            if (isActive && binding.panelQr.visibility == View.VISIBLE) {
                startQrLogin()
            }
        }
    }

    private fun setQrStatus(text: String) {
        binding.qrStatus.text = text
    }

    private fun setPhoneStatus(text: String) {
        binding.phoneStatus.text = text
    }

    // ---------- 手机号登录 ----------

    private fun phone(): String = binding.phoneInput.text?.toString()?.trim() ?: ""

    private fun sendCode() {
        val p = phone()
        if (!Regex("^1\\d{10}$").matches(p)) {
            setPhoneStatus(getString(R.string.phone_invalid))
            return
        }
        binding.sendCodeBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                var resp = withContext(Dispatchers.IO) {
                    MiyouApi.sendSmsCaptcha(
                        p, aigisValue, store.deviceId(), store.deviceFp(),
                        store.deviceName(), store.deviceModel()
                    )
                }
                val firstAigis = resp.aigis
                if (resp.sent == null && !firstAigis.isNullOrBlank()) {
                    val newAigis = resolveAigis(firstAigis) ?: return@launch
                    aigisValue = newAigis
                    resp = withContext(Dispatchers.IO) {
                        MiyouApi.sendSmsCaptcha(
                            p, aigisValue, store.deviceId(), store.deviceFp(),
                            store.deviceName(), store.deviceModel()
                        )
                    }
                }
                val sent = resp.sent
                if (sent != null) {
                    actionType = sent.actionType
                    setPhoneStatus(getString(R.string.send_code_sent))
                } else {
                    setPhoneStatus(getString(R.string.send_code_failed))
                }
            } catch (e: Exception) {
                setPhoneStatus(getString(R.string.network_error))
            } finally {
                binding.sendCodeBtn.isEnabled = true
            }
        }
    }

    private fun doLogin() {
        val p = phone()
        val code = binding.codeInput.text?.toString()?.trim() ?: ""
        if (!Regex("^1\\d{10}$").matches(p) || code.isBlank() || actionType.isBlank()) {
            setPhoneStatus(getString(R.string.phone_invalid))
            return
        }
        binding.loginBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                var resp = withContext(Dispatchers.IO) {
                    MiyouApi.loginBySmsCaptcha(
                        p, code, actionType, aigisValue, store.deviceId(), store.deviceFp(),
                        store.deviceName(), store.deviceModel()
                    )
                }
                val firstAigis = resp.aigis
                if (resp.tokens == null && !firstAigis.isNullOrBlank()) {
                    val newAigis = resolveAigis(firstAigis) ?: return@launch
                    aigisValue = newAigis
                    resp = withContext(Dispatchers.IO) {
                        MiyouApi.loginBySmsCaptcha(
                            p, code, actionType, aigisValue, store.deviceId(), store.deviceFp(),
                            store.deviceName(), store.deviceModel()
                        )
                    }
                }
                val tokens = resp.tokens
                if (tokens != null && tokens.stoken.isNotBlank()) {
                    onLoginTokens(tokens.stoken, tokens.mid, tokens.stuid)
                } else {
                    setPhoneStatus(getString(R.string.login_failed))
                }
            } catch (e: Exception) {
                setPhoneStatus(getString(R.string.network_error))
            } finally {
                binding.loginBtn.isEnabled = true
            }
        }
    }

    private suspend fun resolveAigis(aigisRaw: String): String? {
        val info = parseAigis(aigisRaw) ?: return null
        val validate = showCaptchaWeb(info.gt, info.challenge, info.riskType, info.sessionId) ?: return null
        val b64 = Base64.encodeToString(validate.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "${info.sessionId};$b64"
    }

    private suspend fun showCaptchaWeb(gt: String, challenge: String, riskType: String?, sessionId: String?): String? =
        suspendCoroutine { cont ->
            captchaContinuation = cont
            val intent = Intent(this, CaptchaActivity::class.java).apply {
                putExtra("gt", gt)
                putExtra("challenge", challenge)
                putExtra("risk_type", riskType ?: "")
                putExtra("session_id", sessionId ?: "")
            }
            captchaLauncher.launch(intent)
        }

    // ---------- 登录成功 ----------

    private fun onLoginTokens(stoken: String, mid: String, stuid: String) {
        store.saveLoginTokens(stoken, mid, stuid)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    store.normalize()
                } catch (_: Exception) {
                }
            }
            goMain()
        }
    }

    private fun showManualDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.login_manual_hint)
            setSingleLine(false)
            minLines = 4
            setHorizontallyScrolling(false)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.login_manual)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotBlank()) {
                    store.saveRawCookie(raw)
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                store.normalize()
                            } catch (_: Exception) {
                            }
                        }
                        goMain()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
