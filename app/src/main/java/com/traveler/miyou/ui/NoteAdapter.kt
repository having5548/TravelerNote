package com.traveler.miyou.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.traveler.miyou.R
import com.traveler.miyou.databinding.ItemCalendarBinding
import com.traveler.miyou.databinding.ItemCalendarDayBinding
import com.traveler.miyou.databinding.ItemFooterBinding
import com.traveler.miyou.databinding.ItemHeaderBinding
import com.traveler.miyou.databinding.ItemNoteBinding
import com.traveler.miyou.net.ImageLoader
import com.traveler.miyou.net.LunaAward

sealed class HomeItem {
    data object Header : HomeItem()
    data class Note(
        val iconRes: Int,
        val title: String,
        val value: String,
        val extra: String,
        val tint: Int
    ) : HomeItem()

    /** 旅行日历：本月签到每天能领什么。 */
    data class Calendar(
        val awards: List<LunaAward>,
        val signedDays: Int,
        val signedToday: Boolean,
        val summary: String
    ) : HomeItem()

    data object Footer : HomeItem()
}

class NoteAdapter(
    private val onRefresh: () -> Unit,
    private val onLogout: () -> Unit,
    private val onCommunitySign: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<HomeItem>()
    private var headerBinding: ItemHeaderBinding? = null
    private var headerStatus: String = ""

    private var footerBinding: ItemFooterBinding? = null
    private var communityStatus: String = ""
    private var communityEnabled: Boolean = true

    fun submit(newItems: List<HomeItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSignStatus(text: String) {
        headerStatus = text
        headerBinding?.signStatus?.text = text
    }

    /** 社区签到状态：文字 + 按钮可用性（签到中禁用按钮，避免重复触发）。 */
    fun setCommunityStatus(text: String, enabled: Boolean = true) {
        communityStatus = text
        communityEnabled = enabled
        footerBinding?.let {
            it.communityStatus.text = text
            it.communityBtn.isEnabled = enabled
        }
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HomeItem.Header -> TYPE_HEADER
        is HomeItem.Note -> TYPE_NOTE
        is HomeItem.Calendar -> TYPE_CALENDAR
        is HomeItem.Footer -> TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemHeaderBinding.inflate(inflater, parent, false))
            TYPE_NOTE -> NoteVH(ItemNoteBinding.inflate(inflater, parent, false))
            TYPE_CALENDAR -> CalendarVH(ItemCalendarBinding.inflate(inflater, parent, false))
            else -> FooterVH(ItemFooterBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HomeItem.Header -> {
                val vh = holder as HeaderVH
                headerBinding = vh.binding
                vh.binding.signStatus.text = headerStatus
            }
            is HomeItem.Note -> (holder as NoteVH).bind(item)
            is HomeItem.Calendar -> (holder as CalendarVH).bind(item)
            is HomeItem.Footer -> {
                val vh = holder as FooterVH
                footerBinding = vh.binding
                vh.binding.refreshBtn.setOnClickListener { onRefresh() }
                vh.binding.logoutBtn.setOnClickListener { onLogout() }
                vh.binding.communityBtn.setOnClickListener { onCommunitySign() }
                // 还没拿到状态时保留布局里的提示文案
                if (communityStatus.isNotBlank()) {
                    vh.binding.communityStatus.text = communityStatus
                }
                vh.binding.communityBtn.isEnabled = communityEnabled
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderVH(val binding: ItemHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    class NoteVH(val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HomeItem.Note) {
            binding.icon.setImageResource(item.iconRes)
            binding.icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            binding.iconBg.backgroundTintList = ColorStateList.valueOf(item.tint)
            binding.title.text = item.title
            binding.value.text = item.value
            binding.extra.text = item.extra
            binding.extra.visibility = if (item.extra.isBlank()) View.GONE else View.VISIBLE
        }
    }

    /** 签到日历：7 列等宽格子，已领取 / 今日 / 未领取三种状态。 */
    class CalendarVH(val binding: ItemCalendarBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HomeItem.Calendar) {
            val context = binding.root.context
            binding.calendarSummary.text = item.summary
            binding.calendarGrid.removeAllViews()

            if (item.awards.isEmpty()) {
                binding.calendarCaption.visibility = View.GONE
                return
            }
            binding.calendarCaption.visibility = View.VISIBLE

            val accent = themeColor(context, com.google.android.material.R.attr.colorPrimary)
            val textPrimary = themeColor(context, android.R.attr.textColorPrimary)
            val textSecondary = themeColor(context, android.R.attr.textColorSecondary)
            val cellNormal = context.getColor(R.color.calendar_cell)
            val cellDone = context.getColor(R.color.calendar_cell_done)
            // 已签 totalSignDay 天时，今天的奖励是第 totalSignDay 个；否则是下一个
            val todayIndex = if (item.signedToday) item.signedDays - 1 else item.signedDays
            val gap = (2 * context.resources.displayMetrics.density).toInt()
            val inflater = LayoutInflater.from(context)

            item.awards.forEachIndexed { index, award ->
                if (index % COLUMNS == 0) {
                    binding.calendarGrid.addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                        }
                    )
                }
                val row = binding.calendarGrid.getChildAt(binding.calendarGrid.childCount - 1) as LinearLayout
                val cell = ItemCalendarDayBinding.inflate(inflater, row, false)

                val day = index + 1
                val done = day <= item.signedDays
                val isToday = index == todayIndex

                cell.dayNum.text = day.toString()
                cell.dayCnt.text = if (award.cnt > 0) "×${award.cnt}" else ""
                cell.root.contentDescription =
                    context.getString(R.string.calendar_day_label, day) + " " + award.name
                if (award.icon.isNotBlank()) {
                    ImageLoader.load(context, award.icon, cell.dayIcon)
                }

                cell.root.backgroundTintList = ColorStateList.valueOf(
                    when {
                        isToday -> withAlpha(accent, 0.20f)
                        done -> cellDone
                        else -> cellNormal
                    }
                )
                val textColor = when {
                    isToday -> accent
                    done -> textSecondary
                    else -> textPrimary
                }
                cell.dayNum.setTextColor(textColor)
                cell.dayCnt.setTextColor(textColor)

                cell.root.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(gap, gap, gap, gap) }
                row.addView(cell.root)
            }

            // 最后一行用等宽占位补齐，保证 7 列对齐
            (binding.calendarGrid.getChildAt(binding.calendarGrid.childCount - 1) as? LinearLayout)?.let { row ->
                while (row.childCount < COLUMNS) {
                    row.addView(
                        View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                                setMargins(gap, gap, gap, gap)
                            }
                        }
                    )
                }
            }
        }

        private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
            (255 * alpha).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color)
        )
    }

    class FooterVH(val binding: ItemFooterBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_NOTE = 1
        const val TYPE_CALENDAR = 2
        const val TYPE_FOOTER = 3
        const val COLUMNS = 7
    }
}

/** 取主题属性颜色，保证明暗主题下都正确。 */
private fun themeColor(context: Context, attr: Int): Int {
    val value = TypedValue()
    if (!context.theme.resolveAttribute(attr, value, true)) return Color.GRAY
    return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
}
