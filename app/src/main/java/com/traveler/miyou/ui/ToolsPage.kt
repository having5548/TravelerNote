// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ActivityMainBinding
import com.traveler.miyou.net.AbyssFloor
import com.traveler.miyou.net.ImageLoader
import com.traveler.miyou.net.MiyouApi
import com.traveler.miyou.net.RecordAvatar
import com.traveler.miyou.net.RoleCombat
import com.traveler.miyou.net.SpiralAbyss
import com.traveler.miyou.store.CookieStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 旅行工具页。
 *
 * 上半部分是原生「原神战绩」：接口路径、DS 签名（X4 盐 Gen2、query 字母序）与
 * 响应字段都对齐 Snap.Hutao 的 GameRecordClient；
 * 下半部分是米游社官方网页工具入口（用系统浏览器打开，可复用浏览器里的登录态）。
 */
class ToolsPage(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val store: CookieStore
) {

    private data class WebTool(
        val iconRes: Int,
        val tint: Int,
        val title: String,
        val desc: String,
        val url: String
    )

    fun show() {
        buildWebTools()
        binding.recordRefreshBtn.setOnClickListener { loadRecords() }
        loadRecords()
    }

    // ---------------- 原神战绩（原生） ----------------

    private fun loadRecords() {
        val uid = store.roleUid()
        val region = store.roleRegion()
        if (uid.isNullOrBlank() || region.isNullOrBlank()) {
            binding.recordStatus.text = activity.getString(R.string.tools_record_need_login)
            binding.abyssSummary.text = ""
            binding.theaterSummary.text = ""
            binding.abyssFloors.removeAllViews()
            binding.theaterAvatars.removeAllViews()
            return
        }
        binding.recordStatus.text = activity.getString(R.string.tools_record_loading)
        binding.recordRefreshBtn.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                val deviceId = store.deviceId()
                val deviceFp = store.deviceFp()
                val cookie = store.recordCookieStr()
                val abyss = withContext(Dispatchers.IO) {
                    MiyouApi.fetchSpiralAbyss(cookie, deviceId, deviceFp, uid, region)
                }
                val theater = withContext(Dispatchers.IO) {
                    MiyouApi.fetchRoleCombat(cookie, deviceId, deviceFp, uid, region)
                }
                renderAbyss(abyss)
                renderTheater(theater)
                binding.recordStatus.text = ""
            } catch (e: Exception) {
                binding.recordStatus.text = activity.getString(R.string.network_error)
            } finally {
                binding.recordRefreshBtn.isEnabled = true
            }
        }
    }

    private fun renderAbyss(abyss: SpiralAbyss) {
        binding.abyssFloors.removeAllViews()
        if (abyss.message.isNotBlank()) {
            binding.abyssSummary.text = abyss.message
            return
        }
        binding.abyssSummary.text = activity.getString(
            R.string.abyss_summary,
            abyss.maxFloor.ifBlank { "-" },
            abyss.totalStar,
            abyss.totalBattleTimes,
            abyss.totalWinTimes
        )
        // 只展示有星的最多三层，避免卡片过长
        abyss.floors.filter { it.star > 0 }.takeLast(3).forEach { floor ->
            binding.abyssFloors.addView(floorRow(floor))
        }
    }

    private fun floorRow(floor: AbyssFloor): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.abyss_floor_star, floor.index, floor.star, floor.maxStar)
                setTextColor(activity.getColor(R.color.text_secondary))
                textSize = 13f
                width = dp(92)
            }
        )
        val avatars = LinkedHashMap<Int, RecordAvatar>()
        floor.levels.forEach { level ->
            level.battles.forEach { battle ->
                battle.avatars.forEach { avatars[it.id] = it }
            }
        }
        avatars.values.take(8).forEach { row.addView(avatarView(it)) }
        return row
    }

    private fun renderTheater(theater: RoleCombat) {
        binding.theaterAvatars.removeAllViews()
        if (theater.message.isNotBlank()) {
            binding.theaterSummary.text = theater.message
            return
        }
        if (!theater.hasData) {
            binding.theaterSummary.text = activity.getString(R.string.theater_empty)
            return
        }
        binding.theaterSummary.text = activity.getString(
            R.string.theater_summary,
            theater.difficultyId,
            theater.maxRoundId,
            theater.medalNum,
            theater.coinNum
        )
        theater.avatars.take(12).forEach { binding.theaterAvatars.addView(avatarView(it)) }
    }

    private fun avatarView(avatar: RecordAvatar): ImageView {
        val size = dp(30)
        return ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
            contentDescription = null
            if (avatar.icon.isNotBlank()) {
                ImageLoader.load(activity, avatar.icon, this)
            }
        }
    }

    // ---------------- 米游社官方网页工具入口 ----------------

    private fun buildWebTools() {
        val groups = listOf(
            activity.getString(R.string.tools_group_community) to listOf(
                WebTool(
                    R.drawable.ic_home, activity.getColor(R.color.primary),
                    activity.getString(R.string.tool_miyoushe_ys), activity.getString(R.string.tool_miyoushe_ys_desc),
                    "https://www.miyoushe.com/ys/"
                ),
                WebTool(
                    R.drawable.ic_sign, activity.getColor(R.color.task),
                    activity.getString(R.string.tool_signin_web), activity.getString(R.string.tool_signin_web_desc),
                    "https://act.mihoyo.com/bbs/event/signin/hk4e/index.html?act_id=e202311201442471"
                ),
                WebTool(
                    R.drawable.ic_characters, activity.getColor(R.color.expedition),
                    activity.getString(R.string.tool_passport), activity.getString(R.string.tool_passport_desc),
                    "https://user.mihoyo.com/"
                )
            ),
            activity.getString(R.string.tools_group_map) to listOf(
                WebTool(
                    R.drawable.ic_tools, activity.getColor(R.color.transformer),
                    activity.getString(R.string.tool_map), activity.getString(R.string.tool_map_desc),
                    "https://act.mihoyo.com/ys/app/interactive-map/index.html"
                ),
                WebTool(
                    R.drawable.ic_task, activity.getColor(R.color.coin),
                    activity.getString(R.string.tool_wiki), activity.getString(R.string.tool_wiki_desc),
                    "https://www.miyoushe.com/ys/obc/"
                )
            ),
            activity.getString(R.string.tools_group_data) to listOf(
                WebTool(
                    R.drawable.ic_weekly, activity.getColor(R.color.weekly),
                    activity.getString(R.string.tool_record), activity.getString(R.string.tool_record_desc),
                    "https://webstatic.mihoyo.com/app/community-game-records/index.html?bbs_presentation_style=fullscreen#/ys"
                ),
                WebTool(
                    R.drawable.ic_home_coin, activity.getColor(R.color.resin),
                    activity.getString(R.string.tool_gacha), activity.getString(R.string.tool_gacha_desc),
                    "https://webstatic.mihoyo.com/hk4e/event/e20190909gacha/index.html"
                ),
                WebTool(
                    R.drawable.ic_expedition, activity.getColor(R.color.task),
                    activity.getString(R.string.tool_official), activity.getString(R.string.tool_official_desc),
                    "https://ys.mihoyo.com/"
                )
            )
        )

        binding.toolsList.removeAllViews()
        groups.forEach { (groupTitle, tools) ->
            binding.toolsList.addView(
                TextView(activity).apply {
                    text = groupTitle
                    setTextColor(activity.getColor(R.color.text_secondary))
                    textSize = 13f
                    setPadding(0, dp(18), 0, dp(8))
                }
            )
            tools.forEach { binding.toolsList.addView(webToolRow(it)) }
        }
    }

    private fun webToolRow(tool: WebTool): View {
        val row = activity.layoutInflater.inflate(R.layout.item_tool, binding.toolsList, false)
        row.findViewById<ImageView>(R.id.toolIcon).setImageResource(tool.iconRes)
        row.findViewById<FrameLayout>(R.id.toolIconBg).backgroundTintList = ColorStateList.valueOf(tool.tint)
        row.findViewById<TextView>(R.id.toolTitle).text = tool.title
        row.findViewById<TextView>(R.id.toolDesc).text = tool.desc
        row.setOnClickListener { openUrl(tool.title, tool.url) }
        return row
    }

    /** 用系统浏览器打开：战绩、祈愿、通行证等页面需要浏览器里的米游社登录态。 */
    private fun openUrl(title: String, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(url)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
