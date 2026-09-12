package com.traveler.miyou.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivityMainBinding
import com.traveler.miyou.databinding.ItemCharacterBinding
import com.traveler.miyou.net.BackgroundEngine
import com.traveler.miyou.net.CaptchaSolver
import com.traveler.miyou.net.ImageLoader
import com.traveler.miyou.net.LelaerChar
import com.traveler.miyou.net.MiyouApi
import com.traveler.miyou.store.CookieStore
import com.traveler.miyou.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 前台恢复时自动签到的最小间隔，避免频繁切换前后台反复请求。 */
private const val AUTO_REFRESH_INTERVAL_MS = 60_000L

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CookieStore
    private lateinit var settings: SettingsStore
    private lateinit var adapter: NoteAdapter

    private var captchaContinuation: kotlin.coroutines.Continuation<String?>? = null

    /** 上次刷新时间，用于前台恢复时的自动刷新节流。 */
    private var lastRefreshAt = 0L

    /** 当前已应用的背景来源，哨兵值表示尚未应用过。 */
    private var appliedBackgroundSource = Int.MIN_VALUE

    private val captchaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val validate = result.data?.getStringExtra("validate")
            captchaContinuation?.resume(validate)
            captchaContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 顶部内容避让状态栏；底部让 BottomNavigationView 自己延伸到屏幕底
            v.setPadding(0, bars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        store = CookieStore(this)
        settings = SettingsStore(this)

        adapter = NoteAdapter(
            onRefresh = { refreshHome() },
            onLogout = {
                store.clear()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            },
            onCommunitySign = { signCommunity() }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        adapter.submit(listOf(HomeItem.Header, HomeItem.Footer))

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_characters -> {
                    showCharactersPage()
                    true
                }
                R.id.tab_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.tab_home }
                    true
                }
                else -> {
                    showHomePage()
                    true
                }
            }
        }

        if (!store.hasAnyCredential()) {
            // 未登录时直接跳登录页，免责声明由 LoginActivity 负责弹出，避免连弹两次
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        Disclaimer.showOnce(this)
        showHomePage()
        refreshHome()
    }

    override fun onResume() {
        super.onResume()
        // 背景实时生效：只有设置里的来源真的变了才重新拉图，避免每次回前台都重复下载
        val source = settings.backgroundSource
        if (source != appliedBackgroundSource) {
            appliedBackgroundSource = source
            if (source <= 0) {
                window.setBackgroundDrawable(null)
            } else {
                BackgroundEngine.apply(this)
            }
        }
        // 自动签到做节流，避免频繁切换前后台时反复请求；手动刷新按钮不受此限制
        val now = System.currentTimeMillis()
        if (store.hasAnyCredential() && now - lastRefreshAt > AUTO_REFRESH_INTERVAL_MS) {
            refreshHome()
        }
    }

    private fun showHomePage() {
        binding.recycler.visibility = View.VISIBLE
        binding.charactersScroll.visibility = View.GONE
        binding.toolbar.title = getString(R.string.app_name)
    }

    private fun showCharactersPage() {
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.VISIBLE
        binding.toolbar.title = getString(R.string.tab_characters)
        loadCharacters()
    }

    // ---------------- 首页：自动签到 ----------------

    private fun refreshHome() {
        // 记录刷新时刻，供前台恢复时的节流判断
        lastRefreshAt = System.currentTimeMillis()
        adapter.setSignStatus(getString(R.string.sign_waiting))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    try {
                        store.normalize()
                    } catch (_: Exception) {
                    }
                }
                val deviceId = store.deviceId()
                val deviceFp = store.deviceFp()
                val fullCookie = store.fullCookie()

                // 每次签到前重新拉取原神角色，避免使用过期/串号的角色信息（retcode 10002）
                var roles = withContext(Dispatchers.IO) {
                    MiyouApi.fetchRoles(fullCookie, deviceId)
                }
                if (roles.isEmpty()) {
                    roles = withContext(Dispatchers.IO) {
                        MiyouApi.fetchRolesByStoken(store.stokenCookieStr(), deviceId)
                    }
                }
                if (roles.isEmpty()) {
                    // 登录态可能过期，刷新 cookie 后重试一次
                    withContext(Dispatchers.IO) {
                        try {
                            store.normalize()
                        } catch (_: Exception) {
                        }
                    }
                    roles = withContext(Dispatchers.IO) {
                        MiyouApi.fetchRoles(store.fullCookie(), deviceId)
                    }
                }
                val role = roles.firstOrNull()

                // 旅行日历要用到的本月签到进度
                var signedDays = 0
                var signedToday = false

                var lunaText: String
                if (role == null) {
                    lunaText = "未获取到原神角色，请重新登录"
                } else {
                    store.saveRole(role.uid, role.region, role.nickname)

                    var info = withContext(Dispatchers.IO) {
                        MiyouApi.fetchLunaInfo(store.cookieTokenCookieStr(), deviceId, role.uid, role.region)
                    }
                    if (info.message.isBlank()) {
                        signedDays = info.totalSignDay
                        signedToday = info.isSign
                    }
                    if (info.message.isNotBlank()) {
                        lunaText = "状态查询失败：" + info.message
                    } else if (info.isSign) {
                        lunaText = "今日已签到（连续 ${info.totalSignDay} 天）"
                    } else if (info.firstBind) {
                        lunaText = "需先在游戏内手动签到一次"
                    } else {
                        var r = withContext(Dispatchers.IO) {
                            MiyouApi.signLuna(store.cookieTokenCookieStr(), deviceId, role.uid, role.region)
                        }
                        if (r.needCaptcha) {
                            // 先试「无感验证」：设置里开启且配置了 https 打码接口时自动识别
                            val solved = withContext(Dispatchers.IO) {
                                CaptchaSolver.solve(settings, r.gt, r.challenge)
                            }
                            if (solved != null) {
                                r = withContext(Dispatchers.IO) {
                                    MiyouApi.signLuna(
                                        store.cookieTokenCookieStr(), deviceId, role.uid, role.region,
                                        solved.challenge, solved.validate, "${solved.validate}|jordan"
                                    )
                                }
                            }
                            // 无感验证不可用或未通过，回退到网页手动验证
                            if (r.needCaptcha) {
                                val raw = showCaptchaWeb(r.gt, r.challenge, null, null)
                                val cap = raw?.let { extractCaptcha(it, r.challenge) }
                                if (cap != null) {
                                    r = withContext(Dispatchers.IO) {
                                        MiyouApi.signLuna(store.cookieTokenCookieStr(), deviceId, role.uid, role.region, cap.challenge, cap.validate, cap.seccode)
                                    }
                                }
                            }
                        }
                        // 10002：角色信息不被认可，重拉角色后重试一次
                        if (!r.ok && !r.already && r.message.contains("10002") && roles.size > 1) {
                            val other = roles[1]
                            store.saveRole(other.uid, other.region, other.nickname)
                            r = withContext(Dispatchers.IO) {
                                MiyouApi.signLuna(store.cookieTokenCookieStr(), deviceId, other.uid, other.region)
                            }
                        }
                        lunaText = when {
                            r.ok && !r.already -> "签到成功，奖励将通过游戏内邮件发放"
                            r.already -> "今日已签到"
                            else -> "签到失败：" + r.message + "（uid=${role.uid} region=${role.region}）"
                        }
                    }
                }

                // 旅行日历：本月每天的签到奖励（按"第几天"顺序）
                val lunaHome = withContext(Dispatchers.IO) {
                    MiyouApi.fetchLunaHome(store.cookieTokenCookieStr(), deviceId)
                }

                // 实时数据（widget v2 / stoken 通道）：树脂+洞天宝钱+派遣+委托
                val stokenCookie = store.stokenCookieStr()
                val resp = withContext(Dispatchers.IO) {
                    MiyouApi.fetchWidgetResin(stokenCookie, deviceId, deviceFp)
                }

                val items = mutableListOf<HomeItem>()
                items.add(HomeItem.Header)
                val note = resp.note
                if (note != null) {
                    items.add(
                        HomeItem.Note(
                            R.drawable.ic_resin,
                            getString(R.string.resin),
                            "${note.currentResin} / ${note.maxResin}",
                            resinFullText(note.resinRecoveryTime),
                            getColor(R.color.resin)
                        )
                    )
                    items.add(
                        HomeItem.Note(
                            R.drawable.ic_home_coin,
                            getString(R.string.home_coin),
                            "${note.currentHomeCoin} / ${note.maxHomeCoin}",
                            resinFullText(note.homeCoinRecoveryTime),
                            getColor(R.color.coin)
                        )
                    )
                    items.add(
                        HomeItem.Note(
                            R.drawable.ic_expedition,
                            getString(R.string.expedition),
                            "${note.currentExpeditionNum} / ${note.maxExpeditionNum}",
                            if (note.currentExpeditionNum <= 0) getString(R.string.expedition_done) else "进行中",
                            getColor(R.color.expedition)
                        )
                    )
                    items.add(
                        HomeItem.Note(
                            R.drawable.ic_task,
                            getString(R.string.daily_task),
                            "${note.finishedTaskNum} / ${note.totalTaskNum}",
                            "",
                            getColor(R.color.task)
                        )
                    )
                } else {
                    items.add(
                        HomeItem.Note(
                            R.drawable.ic_resin,
                            getString(R.string.resin),
                            "--",
                            resp.message.ifBlank { getString(R.string.resin_fail) },
                            getColor(R.color.resin)
                        )
                    )
                }
                // 旅行日历：本月签到每天能领什么
                val awards = lunaHome.awards
                val todayAward = awards.getOrNull(if (signedToday) signedDays - 1 else signedDays)
                val emptyText = if (lunaHome.message.isBlank()) {
                    getString(R.string.calendar_empty)
                } else {
                    getString(R.string.calendar_empty_reason, lunaHome.message)
                }
                val calendarSummary = when {
                    todayAward == null -> emptyText
                    signedToday -> getString(R.string.calendar_summary_signed, signedDays, todayAward.name, todayAward.cnt)
                    else -> getString(R.string.calendar_summary_unsigned, signedDays, todayAward.name, todayAward.cnt)
                }
                items.add(HomeItem.Calendar(awards, signedDays, signedToday, calendarSummary))
                items.add(HomeItem.Footer)
                adapter.submit(items)
                adapter.setSignStatus(lunaText)

                // 社区签到这里只查状态，绝不自动签（社区签到必须手动点按钮）
                val bbsSigned = withContext(Dispatchers.IO) { MiyouApi.fetchBbsSignedToday(fullCookie) }
                adapter.setCommunityStatus(
                    when (bbsSigned) {
                        true -> getString(R.string.community_sign_done)
                        false -> getString(R.string.community_sign_todo)
                        null -> getString(R.string.community_sign_unknown)
                    }
                )
            } catch (e: Exception) {
                adapter.setSignStatus(getString(R.string.network_error))
            }
        }
    }

    /** 根据树脂恢复剩余秒数计算回满的具体时间。 */
    private fun resinFullText(seconds: Int): String {
        if (seconds <= 0) return getString(R.string.resin_full)
        val full = System.currentTimeMillis() + seconds * 1000L
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(full))
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val human = if (h > 0) "${h}小时${m}分" else "${m}分钟"
        return "${getString(R.string.resin_full_at)} $fmt（约${human}后）"
    }

    private fun extractCaptcha(raw: String, fallbackChallenge: String): CaptchaTriple? {
        return try {
            val j = org.json.JSONObject(raw)
            val challenge = j.optString("geetest_challenge")
                .ifBlank { j.optString("challenge", fallbackChallenge) }
            val validate = j.optString("geetest_validate")
                .ifBlank { j.optString("validate", "") }
            if (validate.isBlank()) return null
            val seccode = j.optString("geetest_seccode")
                .ifBlank { j.optString("seccode", "$validate|jordan") }
            CaptchaTriple(challenge, validate, seccode)
        } catch (e: Exception) {
            // 兼容极个别返回纯 validate 字符串的情况
            if (raw.isNotBlank() && !raw.trimStart().startsWith("{")) {
                CaptchaTriple(fallbackChallenge, raw, "$raw|jordan")
            } else null
        }
    }

    data class CaptchaTriple(val challenge: String, val validate: String, val seccode: String)

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

    // ---------------- 社区（论坛）签到：只手动触发 ----------------

    /**
     * 手动执行米游社社区签到。游戏签到是自动的，社区签到只有用户点按钮才会跑，
     * 流程中按钮会被禁用，天然防重复点击。
     */
    private fun signCommunity() {
        adapter.setCommunityStatus(getString(R.string.community_sign_doing), enabled = false)
        lifecycleScope.launch {
            val result = try {
                signCommunityOnce()
            } catch (e: Exception) {
                CommunityResult(false, getString(R.string.network_error))
            }
            adapter.setCommunityStatus(
                if (result.done) getString(R.string.community_sign_done) else getString(R.string.community_sign_todo),
                enabled = true
            )
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.community_sign)
                .setMessage(result.message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private data class CommunityResult(val done: Boolean, val message: String)

    private suspend fun signCommunityOnce(): CommunityResult {
        val deviceId = store.deviceId()
        val deviceFp = store.deviceFp()
        val cookie = store.fullCookie()

        var r = withContext(Dispatchers.IO) { MiyouApi.signIn(cookie, deviceId, deviceFp) }
        if (r.needCaptcha) {
            val challenge = resolveCommunityCaptcha(cookie, deviceId, deviceFp)
            if (challenge != null) {
                r = withContext(Dispatchers.IO) {
                    MiyouApi.signInWithChallenge(cookie, deviceId, deviceFp, challenge)
                }
            }
        }
        val message = when {
            r.ok && r.alreadySigned -> getString(R.string.community_sign_already)
            r.ok -> getString(R.string.community_sign_success)
            else -> getString(R.string.community_sign_failed) + "：" + r.message
        }
        return CommunityResult(r.ok, message)
    }

    /**
     * 社区签到遇 1034 时的极验流程：无感验证优先，其次网页手验；
     * 再把极验结果交给 verifyVerification 换回真正要放进 x-rpc-challenge 的 challenge
     * （换不到就退回使用极验本身的 challenge）。
     */
    private suspend fun resolveCommunityCaptcha(cookie: String, deviceId: String, deviceFp: String): String? {
        val gc = withContext(Dispatchers.IO) {
            MiyouApi.createVerification(cookie, deviceId, deviceFp)
        } ?: return null

        val solved = withContext(Dispatchers.IO) {
            CaptchaSolver.solve(settings, gc.gt, gc.challenge)
        }
        val triple = if (solved != null) {
            CaptchaTriple(solved.challenge, solved.validate, "${solved.validate}|jordan")
        } else {
            val raw = showCaptchaWeb(gc.gt, gc.challenge, null, null) ?: return null
            extractCaptcha(raw, gc.challenge) ?: return null
        }

        val pass = withContext(Dispatchers.IO) {
            MiyouApi.verifyVerification(cookie, deviceId, deviceFp, triple.challenge, triple.validate, triple.seccode)
        }
        return pass?.challenge?.takeIf { it.isNotBlank() } ?: triple.challenge
    }

    // ---------------- 我的角色 ----------------

    private fun loadCharacters() {
        val uid = store.roleUid()
        if (uid.isNullOrBlank()) {
            binding.charRoleName.text = getString(R.string.characters_need_login)
            binding.charRoleUid.text = ""
            binding.charList.removeAllViews()
            return
        }
        binding.charRoleName.text = store.roleNickname() ?: ""
        binding.charRoleUid.text = "UID $uid"
        binding.charList.removeAllViews()
        binding.charList.addView(statusView(getString(R.string.characters_loading)))
        loadLelaer(uid)
    }

    private fun loadLelaer(uid: String) {
        lifecycleScope.launch {
            try {
                val chars: List<LelaerChar> = withContext(Dispatchers.IO) {
                    MiyouApi.fetchLelaerCharacters(uid)
                }
                binding.charList.removeAllViews()
                if (chars.isEmpty()) {
                    binding.charList.addView(statusView(getString(R.string.characters_empty)))
                    return@launch
                }
                chars.forEach { c ->
                    binding.charList.addView(characterCard(c))
                }
            } catch (e: Exception) {
                binding.charList.removeAllViews()
                binding.charList.addView(statusView(getString(R.string.characters_fail)))
            }
        }
    }

    private fun characterCard(c: LelaerChar): View {
        val item = ItemCharacterBinding.inflate(layoutInflater, binding.charList, false)
        item.name.text = c.name
        item.lvWeapon.text = buildString {
            append(getString(R.string.characters_lv)).append(' ').append(c.level)
            if (c.weapon.isNotBlank()) append("  ·  ").append(c.weapon)
        }
        item.stats.text = buildString {
            if (c.crit.isNotBlank()) { append("暴击 ").append(c.crit); append("  ") }
            if (c.critDmg.isNotBlank()) append("爆伤 ").append(c.critDmg)
            if (c.attack.isNotBlank()) { append("  攻击 ").append(c.attack) }
            if (c.artifacts.isNotBlank()) append("  ").append(c.artifacts)
        }
        if (c.roleImg.isNotBlank()) {
            ImageLoader.load(this, c.roleImg, item.avatar)
        }
        item.root.setOnClickListener { showCharPanel(c) }
        return item.root
    }

    /** 角色面板弹窗：属性/武器/圣遗物详细。 */
    private fun showCharPanel(c: LelaerChar) {
        val text = TextView(this).apply {
            text = c.detail.ifBlank { getString(R.string.characters_fail) }
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${c.name} · ${getString(R.string.characters_lv)} ${c.level}")
            .setView(text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun statusView(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setPadding(0, dp(10), 0, dp(6))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
