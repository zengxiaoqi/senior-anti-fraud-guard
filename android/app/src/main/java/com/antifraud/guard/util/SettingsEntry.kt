package com.antifraud.guard.util

import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import com.antifraud.guard.R

/**
 * 设置入口页那一行的绑定器（`item_settings_entry.xml`）。
 *
 * ## 为什么摘要是必填而不是可选
 * 入口页的全部价值就在摘要这一行：用户扫一眼"现在是什么状态"来决定要不要点进去。
 * 允许摘要为空，等于允许某一项退化成"一个不知道通向哪的按钮"——
 * 而用户只有在点进去之后才知道自己该不该关心这件事，这恰恰是入口页要解决的问题。
 *
 * ## 摘要允许带状态色
 * 「未设置家基准」这类状态必须用警示黄，不能和「通话 15 分钟」用同一种灰：
 * 入口页是这几页里唯一能一次性看到全局的地方，状态色是它唯一的信号灯。
 */
object SettingsEntry {

    /** 还没拿到服务端值时的占位。宁可显示"读取中"，也不要留空白让人以为这项没内容 */
    const val LOADING = "正在读取…"
    const val UNSET = "尚未设置"

    fun bind(
        row: View,
        icon: String,
        title: String,
        summary: String = LOADING,
        @ColorInt summaryColor: Int = UiPrefs.dimColor(row.context),
        onClick: () -> Unit
    ) {
        row.findViewById<TextView>(R.id.entry_icon).text = icon
        row.findViewById<TextView>(R.id.entry_title).text = title
        row.findViewById<TextView>(R.id.entry_summary).apply {
            text = summary
            setTextColor(summaryColor)
        }
        row.setOnClickListener { onClick() }
    }

    /** 只更新摘要（配置拉取完成时刷新，不重置点击监听） */
    fun summary(
        row: View,
        text: String,
        @ColorInt color: Int = UiPrefs.dimColor(row.context)
    ) {
        row.findViewById<TextView>(R.id.entry_summary).apply {
            this.text = text
            setTextColor(color)
        }
    }
}
