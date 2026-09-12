// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

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
            onCommunitySign = { signCommunity() }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        adapter.submit(listOf(HomeItem.Header, HomeItem.Footer))

        // 角色数据失败时（风控 / 人机验证）用户能手动再试一次
        binding.charRefresh.setOnClickListener { loadCharacters() }

        // 多账号：到期就统一刷新一次所有账号的凭证，避免长期不用导致 cookie 失效
        com.traveler.miyou.store.AccountRefresher.refreshIfDue(this, lifecycleScope)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_characters -> {
                    showCharactersPage()
                    true
                }
                R.id.tab_tools -> {
                    showToolsPage()
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

        // 首次进入先走引导页（免责声明 + 隐私政策，需手动输入同意语）
        if (!settings.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (!store.hasAnyCredential()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        showHomePage()
        refreshHome()
    }

    override fun onResume() {
        super.onResume()
        // 背景与磨砂/亚克力效果实时生效：是否重绘由 BackgroundEngine 内部按签名判断
        BackgroundEngine.apply(this)
        // 自动签到做节流，避免频繁切换前后台时反复请求；手动刷新按钮不受此限制
        val now = System.currentTimeMillis()
        if (store.hasAnyCredential() && now - lastRefreshAt > AUTO_REFRESH_INTERVAL_MS) {
            refreshHome()
        }
    }

    /**
     * 旅行工具的网页要**连状态栏一起覆盖**，所以工具页签下把根布局顶部内边距设为 0；
     * 其他页签恢复为状态栏高度，避免内容被状态栏压住。
     */
    private fun applyRootTopInset(fullscreen: Boolean) {
        val bars = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        val top = if (fullscreen) 0 else (bars?.top ?: 0)
        binding.root.setPadding(0, top, 0, 0)
    }

    private fun showHomePage() {
        binding.recycler.visibility = View.VISIBLE
        binding.charactersScroll.visibility = View.GONE
        binding.toolsWebContainer.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        ThemeHelper.applyToolbarScrim(this)
        binding.toolbar.title = getString(R.string.app_name)
        applyRootTopInset(false)
    }

    private fun showCharactersPage() {
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.VISIBLE
        binding.toolsWebContainer.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        ThemeHelper.applyToolbarScrim(this)
        binding.toolbar.title = getString(R.string.tab_characters)
        loadCharacters()
        applyRootTopInset(false)
    }

    /**
     * 旅行工具：把米游社官方工具页内嵌在本页签里，**隐藏工具栏并连状态栏一起覆盖**；
     * 每次点击页签都会重新加载入口页（见 ToolsWeb.show）；
     * 返回键 / 侧滑返回由 ToolsWeb 注册的回调接管（网页后退 → 退到底回首页）。
     */
    private fun showToolsPage() {
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.GONE
        binding.toolsWebContainer.visibility = View.VISIBLE
        binding.toolbar.visibility = View.GONE
        applyRootTopInset(true)
        ToolsWeb(this, binding, store).show()
    }

    override fun onDestroy() {
        // 内嵌的 WebView 常驻在布局里，Activity 销毁时要主动释放
        if (::binding.isInitialized) {
            binding.toolsWeb.destroy()
        }
        super.onDestroy()
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

        val triple = solveCaptcha(gc.gt, gc.challenge) ?: return null

        val pass = withContext(Dispatchers.IO) {
            MiyouApi.verifyVerification(cookie, deviceId, deviceFp, triple.challenge, triple.validate, triple.seccode)
        }
        return pass?.challenge?.takeIf { it.isNotBlank() } ?: triple.challenge
    }

    /**
     * 极验统一入口：先试无感（用户自行配置的打码服务），拿不到再弹网页手动验证。
     */
    private suspend fun solveCaptcha(gt: String, challenge: String): CaptchaTriple? {
        val solved = withContext(Dispatchers.IO) {
            CaptchaSolver.solve(settings, gt, challenge)
        }
        if (solved != null) {
            return CaptchaTriple(solved.challenge, solved.validate, "${solved.validate}|jordan")
        }
        val raw = showCaptchaWeb(gt, challenge, null, null) ?: return null
        return extractCaptcha(raw, challenge)
    }

    // ---------------- 我的角色（米游社官方战绩接口） ----------------

    private fun loadCharacters() {
        val uid = store.roleUid()
        val region = store.roleRegion()
        if (uid.isNullOrBlank() || region.isNullOrBlank()) {
            binding.charRoleName.text = getString(R.string.characters_need_login)
            binding.charRoleUid.text = ""
            binding.charList.removeAllViews()
            return
        }
        binding.charRoleName.text = store.roleNickname() ?: ""
        binding.charRoleUid.text = "UID $uid"
        binding.charList.removeAllViews()
        binding.charList.addView(statusView(getString(R.string.characters_loading)))
        loadOfficialCharacters(uid, region)
    }

    private fun loadOfficialCharacters(uid: String, region: String) {
        lifecycleScope.launch {
            try {
                val cookie = store.recordCookieStr()
                val deviceId = store.deviceId()
                // 从未注册过的随机指纹极易被风控（5003 / 1034），先用官方接口注册一个再请求
                val deviceFp = withContext(Dispatchers.IO) {
                    com.traveler.miyou.net.DeviceFp.ensure(store)
                }
                var challenge: String? = null
                var result = withContext(Dispatchers.IO) {
                    com.traveler.miyou.net.fetchCharacterList(cookie, deviceId, deviceFp, uid, region)
                }
                // 1034：先过战绩接口的人机验证，再带 x-rpc-challenge 重放一次
                if (result.needVerification) {
                    challenge = resolveRecordCaptcha(cookie, deviceId, deviceFp)
                    if (challenge != null) {
                        result = withContext(Dispatchers.IO) {
                            com.traveler.miyou.net.fetchCharacterList(
                                cookie, deviceId, deviceFp, uid, region, challenge
                            )
                        }
                    }
                }
                if (!result.ok) {
                    binding.charList.removeAllViews()
                    binding.charList.addView(statusView(characterErrorText(result)))
                    return@launch
                }
                if (result.characters.isEmpty()) {
                    binding.charList.removeAllViews()
                    binding.charList.addView(statusView(getString(R.string.characters_empty)))
                    return@launch
                }

                // 列表只有等级 / 命座 / 武器，面板属性与圣遗物要再请求一次 character/detail
                var detailNote: String? = null
                val ids = result.characters.map { it.id }
                var detail = withContext(Dispatchers.IO) {
                    com.traveler.miyou.net.fetchCharacterDetail(
                        cookie, deviceId, deviceFp, uid, region, ids, challenge
                    )
                }
                if (detail.needVerification) {
                    val again = resolveRecordCaptcha(cookie, deviceId, deviceFp)
                    if (again != null) {
                        detail = withContext(Dispatchers.IO) {
                            com.traveler.miyou.net.fetchCharacterDetail(
                                cookie, deviceId, deviceFp, uid, region, ids, again
                            )
                        }
                    }
                }
                val characters = if (detail.ok) {
                    com.traveler.miyou.net.mergeCharacterDetails(result.characters, detail.details)
                } else {
                    detailNote = getString(R.string.characters_detail_failed) +
                        "（" + detail.retcode +
                        (if (detail.message.isNotBlank()) " " + detail.message else "") + "）"
                    result.characters
                }

                binding.charList.removeAllViews()
                detailNote?.let { binding.charList.addView(statusView(it)) }
                characters.sortedWith(
                    compareByDescending<com.traveler.miyou.net.OfficialCharacter> { it.rarity }
                        .thenByDescending { it.level }
                ).forEach { binding.charList.addView(characterCard(it)) }
            } catch (e: Exception) {
                binding.charList.removeAllViews()
                binding.charList.addView(statusView(getString(R.string.characters_fail)))
            }
        }
    }

    /**
     * 战绩接口的人机验证：card createVerification → 极验 → card verifyVerification，
     * 换回可直接放进 x-rpc-challenge 的 challenge（对齐胡桃工具箱 GeetestService）。
     */
    private suspend fun resolveRecordCaptcha(cookie: String, deviceId: String, deviceFp: String): String? {
        val gc = withContext(Dispatchers.IO) {
            com.traveler.miyou.net.CardVerification.create(cookie, deviceId, deviceFp)
        } ?: return null
        val triple = solveCaptcha(gc.gt, gc.challenge) ?: return null
        val pass = withContext(Dispatchers.IO) {
            com.traveler.miyou.net.CardVerification.verify(
                cookie, deviceId, deviceFp, triple.challenge, triple.validate, triple.seccode
            )
        }
        return pass?.challenge?.takeIf { it.isNotBlank() } ?: triple.challenge
    }

    /**
     * 把服务端的真实原因显示出来：release 包做了混淆且日志被裁剪，
     * 界面上不写 retcode 就没法判断到底是风控、人机验证还是登录失效。
     */
    private fun characterErrorText(r: com.traveler.miyou.net.CharacterListResult): String = when {
        r.retcode == 5003 -> getString(R.string.characters_risk)
        r.retcode == 1034 -> getString(R.string.characters_need_captcha)
        r.retcode == -100 || r.retcode == 10001 -> getString(R.string.characters_need_login)
        else -> buildString {
            append(getString(R.string.characters_fail))
            append("（").append(r.retcode)
            if (r.message.isNotBlank()) append(' ').append(r.message)
            append('）')
        }
    }

    /**
     * 属性值的显示：接口给的百分比可能是分数（0.625）也可能是百分数（62.5），
     * 小于等于 1 的一律按分数处理，乘 100 再补 '%'，两种口径都能显示对。
     */
    private fun percentLike(type: Int): Boolean =
        type == 3 || type == 6 || type == 9 ||
            (type in 20..30) || (type in 40..46) || (type in 50..56) ||
            type == 80 || type == 81

    private fun trimNum(v: Float): String {
        val rounded = Math.round(v * 10f) / 10f
        return if (rounded == Math.floor(rounded.toDouble()).toFloat()) {
            rounded.toInt().toString()
        } else {
            rounded.toString()
        }
    }

    private fun fmtPanel(value: String, type: Int): String {
        val v = value.toFloatOrNull() ?: return value.ifBlank { "-" }
        if (!percentLike(type)) return trimNum(v)
        return trimNum(if (v <= 1f) v * 100f else v) + "%"
    }

    /** 圣遗物词条自带 '%'（如 46.6%），只对没带 '%' 的分数做一次换算。 */
    private fun fmtRelicValue(p: com.traveler.miyou.net.RelicProperty): String {
        if (p.value.contains('%')) return p.value
        val v = p.value.toFloatOrNull() ?: return p.value
        if (!percentLike(p.type)) return trimNum(v)
        return trimNum(if (v <= 1f) v * 100f else v) + "%"
    }

    /** 面板摘要：卡片上只挑关键几项，完整面板在详情弹窗里。 */
    private fun panelSummary(c: com.traveler.miyou.net.OfficialCharacter): String {
        if (c.properties.isEmpty()) return ""
        val wanted = listOf(2000, 2001, 2002, 20, 22, 23, 28)
        val picked = ArrayList<com.traveler.miyou.net.CharProperty>()
        wanted.forEach { t ->
            c.properties.firstOrNull { it.type == t }?.let { picked.add(it) }
        }
        if (picked.isEmpty()) picked.addAll(c.properties.take(4))
        return getString(R.string.characters_panel) + "：" +
            picked.joinToString(" · ") { it.name + " " + fmtPanel(it.total, it.type) }
    }

    /** 圣遗物摘要：件数 + 各套装件数。 */
    private fun relicSummary(c: com.traveler.miyou.net.OfficialCharacter): String {
        if (c.relics.isEmpty()) {
            return if (c.reliquaryNames.isEmpty()) getString(R.string.characters_no_artifacts)
            else c.reliquaryNames.distinct().joinToString("、")
        }
        val sets = c.relics.filter { it.setName.isNotBlank() }
            .groupingBy { it.setName }.eachCount()
            .entries.joinToString("、") { "${it.key}×${it.value}" }
        val count = getString(R.string.characters_relic_pieces, c.relics.size)
        return if (sets.isBlank()) count else "$count · $sets"
    }

    private fun characterCard(c: com.traveler.miyou.net.OfficialCharacter): View {
        val item = ItemCharacterBinding.inflate(layoutInflater, binding.charList, false)
        item.name.text = buildString {
            append(c.name)
            val element = com.traveler.miyou.net.elementName(c.element)
            if (element.isNotBlank()) append("  ·  ").append(element)
            if (c.rarity > 0) append("  ").append(c.rarity).append("★")
        }
        item.lvWeapon.text = buildString {
            append("Lv.").append(c.level)
            append("  ·  ").append(getString(R.string.characters_constellation))
                .append(' ').append(c.constellation)
            if (c.weaponName.isNotBlank()) {
                append("  ·  ").append(c.weaponName).append(" Lv.").append(c.weaponLevel)
                if (c.weaponAffix > 1) append("（").append(c.weaponAffix).append("精）")
            }
        }
        item.stats.text = panelSummary(c)
        item.relics.text = relicSummary(c)
        if (c.image.isNotBlank()) {
            ImageLoader.load(this, c.image, item.avatar)
        }
        item.root.setOnClickListener { showCharPanel(c) }
        return item.root
    }

    /** 角色详情弹窗：数据全部来自米游社官方战绩接口。 */
    private fun showCharPanel(c: com.traveler.miyou.net.OfficialCharacter) {
        val element = com.traveler.miyou.net.elementName(c.element)
        val detail = buildString {
            append(getString(R.string.characters_lv)).append("：").append(c.level)
            if (c.rarity > 0) {
                append("\n").append(getString(R.string.characters_rarity)).append("：")
                    .append(c.rarity).append("★")
            }
            if (element.isNotBlank()) {
                append("\n").append(getString(R.string.characters_element)).append("：").append(element)
            }
            append("\n").append(getString(R.string.characters_constellation)).append("：").append(c.constellation)
            if (c.fetter > 0) {
                append("\n").append(getString(R.string.characters_fetter)).append("：").append(c.fetter)
            }
            if (c.weaponName.isNotBlank()) {
                append("\n\n").append(getString(R.string.characters_weapon)).append("：").append(c.weaponName)
                append(" Lv.").append(c.weaponLevel)
                if (c.weaponRarity > 0) append("  ").append(c.weaponRarity).append("★")
                if (c.weaponAffix > 1) append("  ").append(c.weaponAffix).append("精")
            }
            if (c.skills.isNotEmpty()) {
                append("\n\n").append(getString(R.string.characters_talents)).append("：")
                c.skills.forEach { append("\n· ").append(it.name).append(" Lv.").append(it.level) }
            }
            // 面板属性：总值（基础+加成），对齐胡桃工具箱的角色属性页
            if (c.properties.isNotEmpty()) {
                append("\n\n").append(getString(R.string.characters_panel)).append("：")
                c.properties.forEach { p ->
                    append("\n· ").append(p.name).append(' ').append(fmtPanel(p.total, p.type))
                    val add = p.add.toFloatOrNull() ?: 0f
                    if (add > 0f) {
                        append("（").append(fmtPanel(p.base, p.type))
                            .append('+').append(fmtPanel(p.add, p.type)).append('）')
                    }
                }
            }
            append("\n\n").append(getString(R.string.characters_artifacts)).append("：")
            if (c.relics.isEmpty()) {
                if (c.reliquaryNames.isEmpty()) {
                    append(getString(R.string.characters_no_artifacts))
                } else {
                    c.reliquaryNames.distinct().forEach { append("\n· ").append(it) }
                }
            } else {
                c.relics.forEach { r ->
                    append("\n· ").append(r.posName)
                    if (r.setName.isNotBlank()) append(' ').append(r.setName)
                    if (r.level > 0) append("  +").append(r.level)
                    if (r.rarity > 0) append(' ').append(r.rarity).append('★')
                    r.main?.let {
                        append("\n    主词条：").append(it.name).append(' ').append(fmtRelicValue(it))
                    }
                    if (r.subs.isNotEmpty()) {
                        append("\n    副词条：")
                            .append(r.subs.joinToString("、") { s -> "${s.name} ${fmtRelicValue(s)}" })
                    }
                }
            }
        }
        val text = TextView(this).apply {
            this.text = detail
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${c.name} · Lv.${c.level}")
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
