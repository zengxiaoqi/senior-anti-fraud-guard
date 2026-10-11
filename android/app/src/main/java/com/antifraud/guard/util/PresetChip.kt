package com.antifraud.guard.util

import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * 「宽松 / 标准 / 严格」预设档位 chip 的画法（两端共用）。
 *
 * ## 为什么要抽出来
 * 位置阈值页与通话支付页各有一组档位 chip，画法必须完全一致：
 * 选中实心蓝底白字、未选中卡片底灰字。两份副本只要有一处改了圆角，
 * 同一款 App 里就会出现两种 chip，用户会以为它们是两种不同性质的控件。
 *
 * ## 颜色为什么是硬编码
 * 选中态的蓝是**品牌状态色**，深浅主题下都要保持这个蓝；
 * 未选中态是**角色色**（卡片底 + 次要文字），必须随主题变，走 UiPrefs。
 * 判定口诀见 `docs/ui-guidelines.md` 第 2 节。
 */
object PresetChip {

    private const val SELECTED_BG = 0xFF3B82F6.toInt()
    private const val SELECTED_TEXT = 0xFFFFFFFF.toInt()

    fun paint(chip: TextView, active: Boolean) {
        val radius = 12 * chip.resources.displayMetrics.density
        chip.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (active) SELECTED_BG else UiPrefs.cardColor(chip.context))
        }
        chip.setTextColor(if (active) SELECTED_TEXT else UiPrefs.dimColor(chip.context))
    }
}
