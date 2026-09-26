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
import com.traveler.miyou.net.ApiConst
import com.traveler.miyou.net.BackgroundEngine
import com.traveler.miyou.net.CaptchaSolver
import com.traveler.miyou.net.ImageLoader
import com.traveler.miyou.net.MiyouApi
import com.traveler.miyou.store.CookieStore
import com.traveler.miyou.store.SettingsStore
import com.traveler.miyou.watch.WatchSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 前台恢复时自动签到的最小间隔，避免频繁切换前后台反复请求。 */
private const val AUTO_REFRESH_INTERVAL_MS = 60_000L

/** 「游戏资讯」自动刷新的节流间隔（手动点刷新不受限制）。 */
private const val NEWS_INTERVAL_MS = 10 * 60_000L

/** 公告每个分组默认展示的条数：再多也不丢，只是折叠成「展开剩余 N 条」。 */
private const val NOTICE_PREVIEW = 5

/**
 * 在页面脚本执行前注入：屏蔽「打开 App / 下载客户端」这类入口。
 * 真正的兜底在 WebViewClient 与 DownloadListener（非 http(s)、apk 一律拦掉）。
 */
private const val BLOCK_JS = """
    (function () {
      if (window._hbk_n) return;
      window._hbk_n = true;
      window.open = function () { return null; };
      document.addEventListener('click', function (e) {
        var el = e.target;
        while (el && el.tagName !== 'A') { el = el.parentElement; }
        if (!el) return;
        var h = el.getAttribute('href') || el.getAttribute('data-href') || '';
        if (/^(bilibili|xiaoheihe|heybox|market|intent|snssdk|weixin|taobao|openapp):/i.test(h) ||
            /\.apk(\?|$)/i.test(h)) {
          e.preventDefault();
          e.stopPropagation();
        }
      }, true);
    })();
"""

class MainActivity : AppCompatActivity() {

    companion object {
        /** 从通知/设置页跳进来时指定落在哪一页（目前只有手表同步）。 */
        const val EXTRA_PAGE = "extra_page"
        const val PAGE_WATCH = "watch"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: CookieStore
    private lateinit var settings: SettingsStore
    private lateinit var adapter: NoteAdapter

    /** 抓官方 B 站动态用的隐藏 WebView（见 BiliDynamicWeb）。 */
    private lateinit var biliWeb: BiliDynamicWeb

    /** 资讯来源：0 官方 / 1 B站。 */
    private var newsSource = 0

    /** B站动态上次抓取成功的时间（切来源时不重复抓）。 */
    private var biliLoadedAt = 0L

    /** 公告详情打开时的返回键回调（详情是同一页里的覆盖层，不是新 Activity）。 */
    private var noticeBackCallback: androidx.activity.OnBackPressedCallback? = null

    /** 公告详情覆盖层：**第一次点开时才创建**（见 ensureNoticeOverlay）。 */
    private var noticeOverlay: android.widget.LinearLayout? = null
    private var noticeToolbar: com.google.android.material.appbar.MaterialToolbar? = null
    private var noticeProgress: android.widget.ProgressBar? = null
    private var noticeWeb: android.webkit.WebView? = null

    private var captchaContinuation: kotlin.coroutines.Continuation<String?>? = null

    /** 上次刷新时间，用于前台恢复时的自动刷新节流。 */
    private var lastRefreshAt = 0L

    /** 上次成功刷新「游戏资讯」的时间。 */
    private var lastNewsAt = 0L

    /** 公告分组展开状态（key = type_id）与最近一次公告结果（展开/收起时重绘用）。 */
    private val noticeExpanded = mutableSetOf<Int>()
    private var lastAnnouncement: com.traveler.miyou.net.AnnouncementResult? = null

    /** 最近一次补签信息与角色（点「补签」时用来确认消耗、重试与刷新）。 */
    private var lastResignInfo: com.traveler.miyou.net.LunaResignInfo? = null
    private var lastRole: com.traveler.miyou.net.GameRole? = null

    private val captchaLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val validate = result.data?.getStringExtra("validate")
            captchaContinuation?.resume(validate)
            captchaContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
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
        // 右上角设置入口（对标 Shizuku：设置不再是底部页签）
        binding.toolbar.inflateMenu(R.menu.main_menu)
        // 菜单图标是矢量里写死的白色：先按当前背景算一次对比色（浅色壁纸下要变深色才看得见）
        ThemeHelper.applyToolbarContent(this)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else {
                false
            }
        }
        // 保活自愈：应用每次被打开时，只要开关是开的而服务没跑，就补起来
        restoreWatchSync()

        adapter = NoteAdapter(
            onRefresh = { refreshHome() },
            onCommunitySign = { signCommunity() },
            onResign = { resignLunaReward() }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        adapter.submit(listOf(HomeItem.Header, HomeItem.Footer))

        // 角色数据失败时（风控 / 人机验证）用户能手动再试一次
        binding.charRefresh.setOnClickListener { loadCharacters() }
        // 游戏资讯手动刷新
        binding.newsRefresh.setOnClickListener { loadNews(force = true) }
        biliWeb = BiliDynamicWeb(this, binding.newsBiliContainer)

        // 资讯来源切换：官方 / B站（iOS 风格分段控件，选中态在代码里画）
        binding.srcOfficial.setOnClickListener { selectNewsSource(0) }
        binding.srcBili.setOnClickListener { selectNewsSource(1) }

        // 多账号：到期就统一刷新一次所有账号的凭证，避免长期不用导致 cookie 失效
        com.traveler.miyou.store.AccountRefresher.refreshIfDue(this, lifecycleScope)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_characters -> {
                    showCharactersPage()
                    true
                }
                R.id.tab_news -> {
                    showNewsPage()
                    true
                }
                R.id.tab_tools -> {
                    showToolsPage()
                    true
                }
                R.id.tab_watch -> {
                    // 手表同步是**页内内容**（Fragment），不再 startActivity 拉一个新界面
                    showWatchPage()
                    true
                }
                else -> {
                    showHomePage()
                    true
                }
            }
        }
        // 已经在手表同步页时再点一次：刷新一次连接状态
        binding.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.tab_watch) {
                (supportFragmentManager.findFragmentById(R.id.watchPage) as? WatchSyncFragment)
                    ?.onPageShown()
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

        // 通知栏点进来可能直接落在手表同步页
        if (intent?.getStringExtra(EXTRA_PAGE) == PAGE_WATCH) {
            binding.bottomNav.selectedItemId = R.id.tab_watch
        } else {
            showHomePage()
            refreshHome()
        }
        showLastCrash()
    }
    /**
     * 手表同步自愈入口：开关开着而服务没运行时把它补起来（此时应用在前台，启动前台服务不会被系统拒绝），
     * 同时重排一次看门狗闹钟，避免"闹钟丢了 + 服务被杀"两个问题叠加后再也起不来。
     */
    private fun restoreWatchSync() {
        if (!settings.watchSyncEnabled) return
        WatchSyncService.scheduleWatchdog(this, settings.watchRefreshMinutes)
        if (WatchSyncService.running) return
        runCatching { WatchSyncService.start(this) }
    }

    override fun onResume() {
        super.onResume()
        // 背景与磨砂/亚克力效果实时生效：是否重绘由 BackgroundEngine 内部按签名判断。
        // 背景真正就绪后再按它的明暗调整顶栏图标/标题的对比色（浅色壁纸下原来图标看不见）
        BackgroundEngine.apply(this) { ThemeHelper.applyToolbarContent(this) }
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
        hideNoticeOverlay()
        binding.recycler.visibility = View.VISIBLE
        binding.charactersScroll.visibility = View.GONE
        binding.newsPage.visibility = View.GONE
        binding.toolsWebContainer.visibility = View.GONE
        binding.watchPage.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        ThemeHelper.applyToolbarContent(this)
        binding.toolbar.title = getString(R.string.app_name)
        applyRootTopInset(false)
    }

    private fun showCharactersPage() {
        hideNoticeOverlay()
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.VISIBLE
        binding.newsPage.visibility = View.GONE
        binding.toolsWebContainer.visibility = View.GONE
        binding.watchPage.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        ThemeHelper.applyToolbarContent(this)
        binding.toolbar.title = getString(R.string.tab_characters)
        loadCharacters()
        applyRootTopInset(false)
    }

    /** 游戏资讯：顶部切换来源（官方 / B站）。 */
    private fun showNewsPage() {
        hideNoticeOverlay()
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.GONE
        binding.newsPage.visibility = View.VISIBLE
        binding.toolsWebContainer.visibility = View.GONE
        binding.watchPage.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        ThemeHelper.applyToolbarContent(this)
        binding.toolbar.title = getString(R.string.tab_news)
        applyRootTopInset(false)
        applyNewsSource()
    }

    private fun selectNewsSource(index: Int) {
        if (newsSource == index) return
        newsSource = index
        applyNewsSource()
    }

    /** 按当前来源显示对应容器，并（首次或过期时）加载数据。 */
    private fun applyNewsSource() {
        renderSourceSelector()
        binding.newsScroll.visibility = if (newsSource == 0) View.VISIBLE else View.GONE
        binding.newsBiliScroll.visibility = if (newsSource == 1) View.VISIBLE else View.GONE
        when (newsSource) {
            0 -> loadNews(force = false)
            else -> loadBiliDynamics(force = false)
        }
    }

    /**
     * 分段控件的选中态：选中的那块画成白色胶囊 + 主文字色，未选中的透明 + 次要文字色。
     * 全程无描边 —— 之前用 Material 的 outlined 按钮会在外面围一圈方框。
     */
    private fun renderSourceSelector() {
        val primary = themedColor(android.R.attr.textColorPrimary, 0xFF1F2430.toInt())
        val secondary = themedColor(android.R.attr.textColorSecondary, 0xFF8A93A6.toInt())
        val official = newsSource == 0
        binding.srcOfficial.setBackgroundResource(if (official) R.drawable.seg_thumb_bg else 0)
        binding.srcOfficial.setTextColor(if (official) primary else secondary)
        binding.srcOfficial.setTypeface(null, if (official) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        binding.srcBili.setBackgroundResource(if (official) 0 else R.drawable.seg_thumb_bg)
        binding.srcBili.setTextColor(if (official) secondary else primary)
        binding.srcBili.setTypeface(null, if (official) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
    }

    /** 读取主题里的颜色（`?android:attr/textColorXxx`），拿不到就用兜底值。 */
    private fun themedColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    /**
     * 旅行工具：把米游社官方工具页内嵌在本页签里，**隐藏工具栏并连状态栏一起覆盖**；
     * 每次点击页签都会重新加载入口页（见 ToolsWeb.show）；
     * 返回键 / 侧滑返回由 ToolsWeb 注册的回调接管（网页后退 → 退到底回首页）。
     */
    private fun showToolsPage() {
        hideNoticeOverlay()
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.GONE
        binding.newsPage.visibility = View.GONE
        binding.watchPage.visibility = View.GONE
        binding.toolsWebContainer.visibility = View.VISIBLE
        binding.toolbar.visibility = View.GONE
        applyRootTopInset(true)
        ToolsWeb(this, binding, store).show()
    }

    /**
     * 手表同步页：**页内内容**，从底部栏或通知进来。
     * 切换时做一次淡入 + 轻微上移，避免像以前那样硬拉一个新界面。
     */
    private fun showWatchPage() {
        hideNoticeOverlay()
        binding.recycler.visibility = View.GONE
        binding.charactersScroll.visibility = View.GONE
        binding.newsPage.visibility = View.GONE
        binding.toolsWebContainer.visibility = View.GONE
        binding.watchPage.visibility = View.VISIBLE
        binding.toolbar.visibility = View.VISIBLE
        ThemeHelper.applyToolbarContent(this)
        binding.toolbar.title = getString(R.string.watch_title)
        applyRootTopInset(false)
        animatePageIn(binding.watchPage)
        // 让页内 Fragment 刷新一次连接状态（替代原来 Activity 的 onResume）。
        // 用 post：如果是通知直接进入本页，Fragment 的视图可能还没 attach 完。
        binding.watchPage.post {
            (supportFragmentManager.findFragmentById(R.id.watchPage) as? WatchSyncFragment)
                ?.onPageShown()
        }
    }

    /** 页内切换的平滑过渡：淡入 + 轻微上移。 */
    private fun animatePageIn(page: View) {
        page.animate().cancel()
        page.alpha = 0f
        page.translationY = dp(10).toFloat()
        page.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_PAGE) == PAGE_WATCH) {
            binding.bottomNav.selectedItemId = R.id.tab_watch
        }
    }

    override fun onDestroy() {
        // 内嵌的 WebView 常驻在布局里，Activity 销毁时要主动释放
        if (::binding.isInitialized) {
            binding.toolsWeb.destroy()
        }
        noticeWeb?.let { web ->
            runCatching { noticeOverlay?.removeView(web) }
            runCatching { web.destroy() }
        }
        noticeWeb = null
        noticeOverlay = null
        noticeToolbar = null
        noticeProgress = null
        if (::biliWeb.isInitialized) {
            biliWeb.destroy()
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
                // 补签信息：本月漏签天数、每次消耗多少米游币（只读，不发补签）
                val resignInfo = if (role == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        MiyouApi.fetchLunaResignInfo(store.cookieTokenCookieStr(), deviceId, role.uid, role.region)
                    }
                }
                lastResignInfo = resignInfo
                lastRole = role
                val calendarResignHint = when {
                    resignInfo == null || resignInfo.message.isNotBlank() -> getString(R.string.resign_hint_unknown)
                    resignInfo.missedDays <= 0 -> getString(R.string.resign_hint_no_missed)
                    resignInfo.coinCost <= 0 -> getString(R.string.resign_hint_free, resignInfo.missedDays)
                    else -> getString(R.string.resign_hint, resignInfo.missedDays, resignInfo.coinCost)
                }
                items.add(
                    HomeItem.Calendar(
                        awards, signedDays, signedToday, calendarSummary,
                        missedDays = resignInfo?.missedDays ?: -1,
                        resignHint = if (role == null) "" else calendarResignHint,
                        resignEnabled = role != null && resignInfo != null && resignInfo.canResign
                    )
                )
                items.add(HomeItem.Footer)
                adapter.submit(items)
                adapter.setSignStatus(lunaText)

                // 社区签到这里只查状态，绝不自动签（社区签到必须手动点按钮）；
                // 同一个回包顺带给出米游币余额（total_points）与今日收支，显示在社区签到下方
                val bbs = withContext(Dispatchers.IO) { MiyouApi.fetchBbsMissions(fullCookie) }
                adapter.setCommunityStatus(
                    when (bbs?.signedToday) {
                        true -> getString(R.string.community_sign_done)
                        false -> getString(R.string.community_sign_todo)
                        null -> getString(R.string.community_sign_unknown)
                    }
                )
                if (bbs != null) {
                    // 米游币优先用社区任务口径；查不到时退回补签信息里的余额
                    val coin = if (bbs.totalPoints > 0) bbs.totalPoints else (resignInfo?.coinCount ?: 0)
                    val coinExtra = if (bbs.todayCanGet <= 0) {
                        getString(R.string.miyou_coin_done)
                    } else {
                        getString(R.string.miyou_coin_today, bbs.todayGot, bbs.todayCanGet)
                    }
                    adapter.setCommunityCoin(coin.toString(), coinExtra)
                } else {
                    val fallback = resignInfo?.coinCount ?: 0
                    adapter.setCommunityCoin(
                        fallback.toString(),
                        if (fallback > 0) getString(R.string.miyou_coin_done) else getString(R.string.miyou_coin_unknown)
                    )
                }
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

    // ---------------- 原神签到补签（消耗米游币） ----------------

    /**
     * 点「补签」：先用 resign_info 的结果算清楚要花多少米游币，让用户确认，
     * 再调 /event/luna/resign；成功后**自动刷新**签到状态、日历与米游币。
     * 出错时用原来的极验回退流程（无感打码 → 网页手验）重试一次。
     */
    private fun resignLunaReward() {
        val role = lastRole
        val info = lastResignInfo
        if (role == null || info == null) {
            toast(getString(R.string.resign_hint_unknown))
            return
        }
        if (!info.canResign) {
            toast(
                when {
                    info.missedDays <= 0 -> getString(R.string.resign_hint_no_missed)
                    else -> getString(R.string.resign_hint_limit)
                }
            )
            return
        }
        if (info.coinCost > 0 && info.coinCount > 0 && info.coinCount < info.coinCost) {
            toast(getString(R.string.resign_coin_not_enough, info.coinCost, info.coinCount))
            return
        }
        val message = if (info.coinCost > 0) {
            getString(R.string.resign_confirm_msg, info.missedDays, info.coinCost, info.coinCount)
        } else {
            getString(R.string.resign_confirm_msg_free, info.missedDays)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.resign_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.resign_action) { _, _ -> doResignLuna(role) }
            .show()
    }

    private fun doResignLuna(role: com.traveler.miyou.net.GameRole) {
        toast(getString(R.string.resign_doing))
        lifecycleScope.launch {
            var result: com.traveler.miyou.net.LunaSignResult? = null
            try {
                var r = withContext(Dispatchers.IO) {
                    MiyouApi.resignLuna(store.cookieTokenCookieStr(), store.deviceId(), role.uid, role.region)
                }
                if (r.needCaptcha) {
                    // 无感打码优先（在 IO 线程）
                    val solved = withContext(Dispatchers.IO) {
                        CaptchaSolver.solve(settings, r.gt, r.challenge)
                    }
                    if (solved != null) {
                        r = withContext(Dispatchers.IO) {
                            MiyouApi.resignLuna(
                                store.cookieTokenCookieStr(), store.deviceId(), role.uid, role.region,
                                solved.challenge, solved.validate, "${solved.validate}|jordan"
                            )
                        }
                    }
                    // 还不行就回退网页手验（必须在主线程拉起验证页）
                    if (r.needCaptcha) {
                        val raw = showCaptchaWeb(r.gt, r.challenge, null, null)
                        val cap = raw?.let { extractCaptcha(it, r.challenge) }
                        if (cap != null) {
                            r = withContext(Dispatchers.IO) {
                                MiyouApi.resignLuna(
                                    store.cookieTokenCookieStr(), store.deviceId(), role.uid, role.region,
                                    cap.challenge, cap.validate, cap.seccode
                                )
                            }
                        }
                    }
                }
                result = r
            } catch (e: Throwable) {
                result = null
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.resign_confirm_title)
                .setMessage(
                    when {
                        result == null -> getString(R.string.network_error)
                        result.ok && result.already -> getString(R.string.resign_already)
                        result.ok -> getString(R.string.resign_success)
                        else -> getString(R.string.resign_failed, result.message)
                    }
                )
                .setPositiveButton(R.string.close, null)
                .show()
            // 不管成功与否都刷新一次：成功要更新漏签天数/日历/米游币，失败也要拿最新状态
            refreshHome()
        }
    }

    /** 轻量提示（补签流程里用得多）。 */
    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

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
     * 哪些是百分比词条。**必须逐项枚举**：像 `(type in 20..30)` 这种区间写法会把
     * 28（元素精通）也当成百分比 —— 元素精通是整数点数，不是百分比。
     * 列表对齐胡桃工具箱 `FightPropertyExtension.PercentProps`。
     */
    private fun percentLike(type: Int): Boolean = when (type) {
        3, 6, 9, 11, 20, 21, 22, 23, 24, 25, 26, 27, 29, 30, 80, 81 -> true
        in 40..47 -> true
        in 50..56 -> true
        else -> false
    }

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
            if (c.rarity > 0) append("  ").append("（").append(c.rarity).append("★）")
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
                    .append("（").append(c.rarity).append("★）")
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
                if (c.weaponRarity > 0) append("  ").append("（").append(c.weaponRarity).append("★）")
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
                    // 一行头：部位 · 套装（5★） +20
                    append("\n· ").append(r.posName.ifBlank { "圣遗物" })
                    if (r.setName.isNotBlank()) {
                        append("  ").append(r.setName)
                        if (r.rarity > 0) append("（").append(r.rarity).append("★）")
                    } else if (r.rarity > 0) {
                        append("（").append(r.rarity).append("★）")
                    }
                    if (r.level > 0) append("  +").append(r.level)
                    // 主词条单独一行，数值对齐好读
                    r.main?.let {
                        append("\n      ").append(it.name).append("  ").append(fmtRelicValue(it))
                    }
                    if (r.subs.isNotEmpty()) {
                        // 一个词条一行，竖着排，扫读更清楚
                        r.subs.forEach { s ->
                            append("\n      ").append(s.name).append("  ").append(fmtRelicValue(s))
                        }
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

    // ---------------- 游戏资讯：活动 / UP 池 / 公告 / 官方 B 站动态 ----------------

    private suspend fun loadActCalendarWithCaptcha(): com.traveler.miyou.net.ActCalendarResult? {
        val uid = store.roleUid() ?: return null
        val region = store.roleRegion() ?: return null
        val cookie = store.recordCookieStr()
        val deviceId = store.deviceId()
        val deviceFp = withContext(Dispatchers.IO) {
            com.traveler.miyou.net.DeviceFp.ensure(store)
        }
        var r = withContext(Dispatchers.IO) {
            com.traveler.miyou.net.fetchActCalendar(cookie, deviceId, deviceFp, uid, region)
        }
        if (r.needVerification) {
            val challenge = resolveRecordCaptcha(cookie, deviceId, deviceFp)
            if (challenge != null) {
                r = withContext(Dispatchers.IO) {
                    com.traveler.miyou.net.fetchActCalendar(
                        cookie, deviceId, deviceFp, uid, region, challenge
                    )
                }
            }
        }
        return r
    }

    private fun loadNews(force: Boolean) {
        if (!force && System.currentTimeMillis() - lastNewsAt < NEWS_INTERVAL_MS) return
        binding.newsUpdated.text = getString(R.string.news_loading)
        binding.newsActs.removeAllViews()
        binding.newsActs.addView(statusView(getString(R.string.news_loading)))
        binding.newsPools.removeAllViews()
        binding.newsNotices.removeAllViews()

        lifecycleScope.launch {
            // 1) 活动日历 + UP 池（需要登录态与角色 uid）
            try {
                val r = loadActCalendarWithCaptcha()
                binding.newsActs.removeAllViews()
                binding.newsPools.removeAllViews()
                when {
                    r == null -> {
                        val text = getString(R.string.news_need_login)
                        binding.newsActs.addView(statusView(text))
                        binding.newsPools.addView(statusView(text))
                    }
                    !r.ok -> {
                        val text = newsErrorText(r.retcode, r.message)
                        binding.newsActs.addView(statusView(text))
                        binding.newsPools.addView(statusView(text))
                    }
                    else -> {
                        renderActs(r.acts)
                        renderPools(r.pools)
                    }
                }
            } catch (e: Exception) {
                binding.newsActs.removeAllViews()
                binding.newsActs.addView(statusView(getString(R.string.news_fail)))
                binding.newsPools.removeAllViews()
                binding.newsPools.addView(statusView(getString(R.string.news_fail)))
            }

            // 2) 游戏公告（与米哈游启动器同源，无需登录）
            try {
                val ann = withContext(Dispatchers.IO) {
                    com.traveler.miyou.net.fetchAnnouncements(store.roleRegion() ?: "cn_gf01")
                }
                binding.newsNotices.removeAllViews()
                renderNotices(ann)
            } catch (e: Exception) {
                binding.newsNotices.removeAllViews()
                binding.newsNotices.addView(statusView(getString(R.string.news_fail)))
            }

            lastNewsAt = System.currentTimeMillis()
            binding.newsUpdated.text = getString(R.string.news_updated, fmtDateTime(lastNewsAt))
        }
    }

    /**
     * B站来源：先用隐藏 WebView 抓官方动态页的数据（页面自己的请求带 B 站下发的风控 cookie），
     * 抓不到再退回官方投稿 + 专栏（客户端接口，无需登录）。
     */
    private fun loadBiliDynamics(force: Boolean) {
        if (!force && biliLoadedAt > 0L && System.currentTimeMillis() - biliLoadedAt < NEWS_INTERVAL_MS) return
        binding.newsDynamics.removeAllViews()
        binding.newsDynamics.addView(statusView(getString(R.string.news_loading)))
        biliWeb.capture { raw ->
            if (isFinishing || isDestroyed) return@capture
            lifecycleScope.launch {
                val captured = if (raw != null) {
                    withContext(Dispatchers.IO) {
                        com.traveler.miyou.net.parseCapturedDynamics(raw)
                    }
                } else {
                    null
                }
                val dyn = if (captured != null && captured.ok && captured.items.isNotEmpty()) {
                    captured
                } else {
                    withContext(Dispatchers.IO) {
                        com.traveler.miyou.net.fetchOfficialBiliContent()
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                biliLoadedAt = System.currentTimeMillis()
                binding.newsDynamics.removeAllViews()
                renderDynamics(dyn)
            }
        }
    }

    private fun newsErrorText(retcode: Int, message: String): String = when {
        retcode == 5003 -> getString(R.string.characters_risk)
        retcode == 1034 -> getString(R.string.characters_need_captcha)
        retcode == -100 || retcode == 10001 -> getString(R.string.characters_need_login)
        else -> buildString {
            append(getString(R.string.news_fail))
            append("（").append(retcode)
            if (message.isNotBlank()) append(' ').append(message)
            append('）')
        }
    }

    /** 活动/卡池的三种状态：1 即将开始 / 2 进行中 / 0 已结束。 */
    private fun phaseOf(startMs: Long, endMs: Long): Int {
        val now = System.currentTimeMillis()
        return when {
            endMs in 1..now -> 0
            startMs > now -> 1
            else -> 2
        }
    }

    private fun phaseText(phase: Int): String = when (phase) {
        0 -> getString(R.string.news_status_finished)
        1 -> getString(R.string.news_status_upcoming)
        else -> getString(R.string.news_status_ongoing)
    }

    private fun fxCountdown(startMs: Long, endMs: Long): String {
        val now = System.currentTimeMillis()
        if (endMs in 1..now) return getString(R.string.news_status_finished)
        val target = if (startMs > now) startMs else endMs
        val prefix = startMs > now
        val diff = target - now
        val days = (diff / 86_400_000L).toInt()
        val hours = (diff / 3_600_000L).toInt()
        return when {
            days >= 1 -> getString(
                if (prefix) R.string.news_start_in_days else R.string.news_left_days, days
            )
            else -> getString(
                if (prefix) R.string.news_start_in_hours else R.string.news_left_hours,
                hours.coerceAtLeast(1)
            )
        }
    }

    private val newsDateFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    private val newsDateFullFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)

    private fun fmtDateTime(ms: Long): String = if (ms <= 0L) "" else newsDateFullFormat.format(java.util.Date(ms))

    /** 同年只显示 月-日 时:分，跨年带上年份。 */
    private fun fmtShortTime(ms: Long): String {
        if (ms <= 0L) return "-"
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return if (cal.get(java.util.Calendar.YEAR) == year) {
            newsDateFormat.format(java.util.Date(ms))
        } else {
            newsDateFullFormat.format(java.util.Date(ms))
        }
    }

    private fun renderActs(acts: List<com.traveler.miyou.net.NewsAct>) {
        val now = System.currentTimeMillis()
        // 只显示**当前正在开启**的活动：官方会提前把活动挂到服务器上，
        // 那些还没到开始时间的（以及已结束的）都不列出来，避免"活动还没开就显示出来"。
        val list = acts
            .filter { a ->
                val notEnded = a.endMs == 0L || a.endMs > now
                val started = a.startMs == 0L || a.startMs <= now
                notEnded && started
            }
            .sortedBy { if (it.endMs == 0L) Long.MAX_VALUE else it.endMs }
        if (list.isEmpty()) {
            binding.newsActs.addView(statusView(getString(R.string.news_empty)))
            return
        }
        list.forEach { a ->
            val item = com.traveler.miyou.databinding.ItemNewsActBinding
                .inflate(layoutInflater, binding.newsActs, false)
            item.name.text = a.name
            item.status.text = phaseText(phaseOf(a.startMs, a.endMs))
            item.time.text = getString(
                R.string.news_time_range, fmtShortTime(a.startMs), fmtShortTime(a.endMs)
            )
            item.countdown.text = fxCountdown(a.startMs, a.endMs)
            if (a.rewards.isEmpty()) {
                item.rewards.visibility = View.GONE
            } else {
                item.rewards.text = a.rewards.joinToString("、")
            }
            binding.newsActs.addView(item.root)
        }
    }

    private fun renderPools(pools: List<com.traveler.miyou.net.NewsCardPool>) {
        val now = System.currentTimeMillis()
        // 与活动一样：只显示**当前正在开启**的池子，官方提前挂上去、还没开始的先不列
        val list = pools
            .filter { p ->
                val notEnded = p.endMs == 0L || p.endMs > now
                val started = p.startMs == 0L || p.startMs <= now
                notEnded && started
            }
            .sortedBy { if (it.endMs == 0L) Long.MAX_VALUE else it.endMs }
        if (list.isEmpty()) {
            binding.newsPools.addView(statusView(getString(R.string.news_empty)))
            return
        }
        list.forEach { p ->
            val item = com.traveler.miyou.databinding.ItemNewsPoolBinding
                .inflate(layoutInflater, binding.newsPools, false)
            item.type.text = com.traveler.miyou.net.cardPoolTypeName(p.poolType)
            item.name.text = p.poolName
            item.status.text = phaseText(phaseOf(p.startMs, p.endMs))
            item.time.text = getString(
                R.string.news_time_range, fmtShortTime(p.startMs), fmtShortTime(p.endMs)
            )
            item.countdown.text = fxCountdown(p.startMs, p.endMs)
            // 五星角色 / 武器头像 + 名字
            p.items.take(6).forEach { it ->
                val box = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                val iv = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    contentDescription = null
                }
                val tv = TextView(this).apply {
                    text = it.name
                    textSize = 10f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 2
                    setTextColor(getColor(R.color.text_secondary))
                }
                box.addView(iv)
                box.addView(tv)
                if (it.icon.isNotBlank()) ImageLoader.load(this, it.icon, iv)
                item.icons.addView(box)
            }
            binding.newsPools.addView(item.root)
        }
    }

    private fun renderNotices(ann: com.traveler.miyou.net.AnnouncementResult) {
        lastAnnouncement = ann
        if (!ann.ok || ann.total == 0) {
            binding.newsNotices.addView(
                statusView(
                    if (ann.ok) getString(R.string.news_empty)
                    else newsErrorText(ann.retcode, ann.message)
                )
            )
            return
        }
        // 每个分组默认只展示前 NOTICE_PREVIEW 条（避免首页过长），多出来的给"展开剩余 N 条"，
        // 这样任何分组都不会被整体丢掉 —— 以前的全局 12 条上限会让后面的分组直接消失。
        for (g in ann.groups) {
            val expanded = noticeExpanded.contains(g.typeId)
            if (g.typeLabel.isNotBlank()) {
                binding.newsNotices.addView(sectionLabel("${g.typeLabel} · ${g.notices.size}"))
            }
            val shownItems = if (expanded) g.notices else g.notices.take(NOTICE_PREVIEW)
            for (n in shownItems) {
                val item = com.traveler.miyou.databinding.ItemNewsNoticeBinding
                    .inflate(layoutInflater, binding.newsNotices, false)
                item.title.text = n.title
                val hasTime = n.type == 1 && n.startMs > 0 && n.endMs > 0
                item.tag.text = when {
                    n.tagLabel.isNotBlank() -> n.tagLabel
                    hasTime -> phaseText(phaseOf(n.startMs, n.endMs))
                    n.typeLabel.isNotBlank() -> n.typeLabel
                    else -> "公告"
                }
                if (item.tag.text.isNullOrBlank()) item.tag.visibility = View.GONE
                item.meta.text = if (hasTime) {
                    getString(R.string.news_time_range, fmtShortTime(n.startMs), fmtShortTime(n.endMs))
                } else {
                    n.subtitle
                }
                item.card.setOnClickListener { openNotice(n) }
                binding.newsNotices.addView(item.root)
            }
            val rest = g.notices.size - shownItems.size
            if (rest > 0) {
                binding.newsNotices.addView(
                    noticeMoreLabel(getString(R.string.news_expand_more, rest)) {
                        noticeExpanded.add(g.typeId)
                        binding.newsNotices.removeAllViews()
                        renderNotices(ann)
                    }
                )
            } else if (expanded && g.notices.size > NOTICE_PREVIEW) {
                binding.newsNotices.addView(
                    noticeMoreLabel(getString(R.string.news_collapse)) {
                        noticeExpanded.remove(g.typeId)
                        binding.newsNotices.removeAllViews()
                        renderNotices(ann)
                    }
                )
            }
        }
    }

    private fun renderDynamics(dyn: com.traveler.miyou.net.BiliDynamicResult) {
        if (!dyn.ok || dyn.items.isEmpty()) {
            binding.newsDynamics.addView(
                statusView(
                    if (dyn.ok) getString(R.string.news_empty)
                    else newsErrorText(dyn.retcode, dyn.message)
                )
            )
            return
        }
        dyn.items.take(12).forEach { d ->
            val item = com.traveler.miyou.databinding.ItemNewsDynamicBinding
                .inflate(layoutInflater, binding.newsDynamics, false)
            item.kind.text = d.kind
            item.time.text = buildString {
                append(fmtShortTime(d.pubMs))
                if (d.meta.isNotBlank()) append("  ·  ").append(d.meta)
            }
            item.text.text = d.text
            if (d.text.isBlank()) item.text.visibility = View.GONE

            // 最多 3 张图，点击用哔哩哔哩打开原内容
            d.images.take(3).forEach { url ->
                val iv = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                        rightMargin = dp(6)
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    contentDescription = null
                }
                ImageLoader.load(this, url, iv, referer = ApiConst.BILI_WWW)
                iv.setOnClickListener { openBili(d) }
                item.images.addView(iv)
            }

            if (d.isVideo) {
                item.videoRow.visibility = View.VISIBLE
                item.videoTitle.text = buildString {
                    append(getString(R.string.news_video_prefix))
                    if (d.meta.isNotBlank()) append("  ·  ").append(d.meta)
                    append("  ·  ").append(getString(R.string.news_click_open_web))
                }
            }

            item.card.setOnClickListener { openBili(d) }
            binding.newsDynamics.addView(item.root)
        }
    }

    /**
     * 打开一条 B 站内容：**在应用内**用 WebView 打开对应网页 ——
     * 不再唤起哔哩哔哩客户端、也不跳系统浏览器。
     */
    private fun openBili(d: com.traveler.miyou.net.BiliDynamicItem) {
        val url = d.webUrl.ifBlank {
            if (d.bvid.isNotBlank()) "${ApiConst.BILI_WWW}/video/${d.bvid}" else ""
        }
        if (url.isBlank()) return
        val title = if (d.kind.isBlank()) "B站" else "B站 · ${d.kind}"
        openSiteInApp(title, url)
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(2), 0, dp(6))
    }

    /** 「展开剩余 N 条 / 收起」：默认每个分组只展示前几条，避免首页过长，但绝不静默丢条目。 */
    private fun noticeMoreLabel(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(getColor(R.color.expedition))
        setPadding(0, dp(6), 0, dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    // ---------------- 游戏公告详情（应用内同一页打开，不新开 Activity） ----------------

    /**
     * 应用内网页覆盖层**第一次点开时才创建**（不写进 activity_main.xml）：
     * 冷启动路径与 1.1.5 完全一致，详情相关的东西一件都不参与启动。
     * 公告正文、B站条目、小黑盒条目都用它。
     */
    private fun ensureWebOverlay(): android.widget.LinearLayout {
        noticeOverlay?.let { return it }

        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)
        val barHeight = android.util.TypedValue
            .complexToDimensionPixelSize(tv.data, resources.displayMetrics)

        val night = NoticeHtml.isNight(this)
        val bg = if (night) android.graphics.Color.parseColor("#121212") else android.graphics.Color.WHITE

        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, barHeight
            )
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { closeNotice() }
        }

        val progress = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            )
            visibility = View.GONE
        }

        val web = android.webkit.WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            // 详情正文本身是静态 HTML，但 B站 / 小黑盒 的页面需要 JS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            // 明确底色：透明底 + 深色字在某些主题组合下会看起来"一片空白"
            setBackgroundColor(bg)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // 应用内打开：http(s) 一律留在本 WebView 里继续加载；
                    // 唤起客户端 / 应用市场 / 下安装包的跳转全部拦掉
                    if (isBlockedNavigation(url)) {
                        notifyWebBlocked()
                        return true
                    }
                    return false
                }

                override fun onPageStarted(
                    view: android.webkit.WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    // 页面脚本执行前注入：屏蔽「打开 App / 下载客户端」入口
                    view?.evaluateJavascript(BLOCK_JS, null)
                }

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    noticeProgress?.visibility = View.GONE
                    view?.evaluateJavascript(BLOCK_JS, null)
                }
            }
            // B站手机网页会偷偷下一份客户端 apk，这里一律拒绝
            setDownloadListener { _, _, _, _, _ -> notifyWebBlocked() }
        }

        val overlay = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(bg)
        }
        overlay.addView(toolbar)
        overlay.addView(progress)
        overlay.addView(web)

        val parent = (binding.recycler.parent as? android.view.ViewGroup) ?: binding.root
        val lp: android.view.ViewGroup.LayoutParams =
            if (parent is android.widget.FrameLayout) {
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            } else {
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        parent.addView(overlay, lp)

        val callback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeNotice()
        }
        onBackPressedDispatcher.addCallback(this, callback)
        noticeBackCallback = callback

        noticeOverlay = overlay
        noticeToolbar = toolbar
        noticeProgress = progress
        noticeWeb = web
        return overlay
    }

    /**
     * 打开公告详情：**立刻**在当前页显示（标题 / 横幅 / "正在加载正文"），
     * 正文随后在后台拉取并替换；渲染失败退回纯文本，任何一步都不允许把应用搞崩。
     */
    private fun openNotice(n: com.traveler.miyou.net.NewsNotice) {
        val overlay = try {
            ensureWebOverlay()
        } catch (e: Throwable) {
            null
        }
        if (overlay == null) {
            android.widget.Toast.makeText(
                this, getString(R.string.news_notice_open_failed), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        noticeToolbar?.title = n.title
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        binding.toolbar.visibility = View.GONE
        noticeProgress?.visibility = View.VISIBLE
        noticeBackCallback?.isEnabled = true
        loadNoticeHtml(NoticeHtml.build(this, n.title, n.subtitle, n.banner, "<p>正在加载正文…</p>"))

        lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) {
                try {
                    com.traveler.miyou.net.cleanNoticeHtml(
                        com.traveler.miyou.net.fetchAnnouncementContents(
                            store.roleRegion() ?: "cn_gf01"
                        )[n.annId].orEmpty()
                    )
                } catch (e: Exception) {
                    ""
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (noticeOverlay?.visibility != View.VISIBLE) return@launch
            noticeProgress?.visibility = View.GONE
            val body = html.ifBlank { "<p>正文加载失败，请检查网络后重试。</p>" }
            loadNoticeHtml(NoticeHtml.build(this@MainActivity, n.title, n.subtitle, n.banner, body))
        }
    }

    /** WebView 渲染失败就退回纯文本。 */
    private fun loadNoticeHtml(html: String) {
        val web = noticeWeb ?: return
        try {
            web.loadDataWithBaseURL(
                "https://webstatic.mihoyo.com/", html, "text/html", "utf-8", null
            )
        } catch (e: Throwable) {
            runCatching {
                web.loadDataWithBaseURL(null, NoticeHtml.plainHtml(html), "text/html", "utf-8", null)
            }
        }
    }

    // ---------------- 应用内网页覆盖层（公告正文 / B站条目共用，见 ensureWebOverlay） ----------------

    private fun closeNotice() = hideNoticeOverlay(restoreToolbar = true)

    /** 切页签 / 点返回时收起公告详情覆盖层（还没创建过就是空操作）。 */
    private fun hideNoticeOverlay(restoreToolbar: Boolean = false) {
        noticeOverlay?.visibility = View.GONE
        noticeProgress?.visibility = View.GONE
        noticeBackCallback?.isEnabled = false
        if (restoreToolbar && binding.newsPage.visibility == View.VISIBLE) {
            binding.toolbar.visibility = View.VISIBLE
        }
    }

    /**
     * 应用内打开网页（不唤起客户端、不跳浏览器）。
     * http(s) 会在覆盖层里继续加载；唤起 App / 应用市场 / apk 的跳转会被拦掉。
     */
    private fun openSiteInApp(title: String, url: String) {
        if (url.isBlank()) return
        val overlay = try {
            ensureWebOverlay()
        } catch (e: Throwable) {
            null
        }
        if (overlay == null) {
            android.widget.Toast.makeText(
                this, getString(R.string.news_notice_open_failed), android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        noticeToolbar?.title = title
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        binding.toolbar.visibility = View.GONE
        noticeProgress?.visibility = View.VISIBLE
        noticeBackCallback?.isEnabled = true
        loadWebUrl(url)
    }

    private fun loadWebUrl(url: String) {
        val web = noticeWeb ?: return
        try {
            web.loadUrl(url)
        } catch (e: Throwable) {
            runCatching {
                web.loadDataWithBaseURL(null, NoticeHtml.plainHtml(url), "text/html", "utf-8", null)
            }
        }
    }

    /** 需要拦掉的跳转：唤起客户端 / 应用市场 / 安装包下载等非 http(s) 目标。 */
    private fun isBlockedNavigation(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".apk")) return true
        val scheme = lower.substringBefore(':', "")
        return scheme !in setOf("http", "https", "about", "data", "javascript", "file", "content", "blob")
    }

    private fun notifyWebBlocked() {
        android.widget.Toast
            .makeText(this, getString(R.string.news_web_blocked), android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    /** 上一次闪退的堆栈（CrashLog 写的），弹出来方便反馈。 */
    private fun showLastCrash() {
        val file = java.io.File(cacheDir, CrashLog.FILE_NAME)
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (e: Exception) {
            ""
        }
        runCatching { file.delete() }
        if (text.isBlank()) return
        val view = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.crash_title))
            .setView(scroll)
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
