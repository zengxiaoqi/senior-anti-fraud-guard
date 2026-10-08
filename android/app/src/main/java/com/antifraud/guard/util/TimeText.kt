package com.antifraud.guard.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 时间文本格式化（老人端/子女端共用）。
 *
 * ## 为什么要抽出来
 * 2026-10-08 出过一次真实事故：子女端「维权证据」页同一张卡片上
 * 分组头显示 `08:28`、条目显示 `16:28`，差 8 小时。
 *
 * 根因是**同一个数据走了两条渲染路径，只有一条做了时区处理**：
 *   - 条目：`RecordingPlayerCard.formatTime()` → 用了 `TimeZone` 转 ✅
 *   - 分组头：`EvidenceFragment` 直接 `.replace("T"," ").take(16)` ❌
 *
 * 只要还存在两份实现，改了一处忘了另一处就会再次出错。
 * 现在统一到这里，两边行为不可能再分叉。
 *
 * ## 与服务端的约定
 * 服务端 `services/timeFormat.js` 已把所有时间字段统一转成北京时间
 * "YYYY-MM-DD HH:mm:ss"（无时区标记）。因此本工具的规则是：
 *   - 带 `Z` / `±HH:MM` → 视为 UTC，先转再显示
 *   - 不带时区标记    → **已经是北京时间**，直接格式化，绝不再加 8 小时
 *
 * 重复转换是最容易犯也最难发现的错误：结果会变成明天凌晨，且没有任何报错。
 */
object TimeText {

    private const val BEIJING = "Asia/Shanghai"

    private val DISPLAY_FULL = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val DISPLAY_SHORT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val PARSE = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA)
    }

    /**
     * 解析为可用于显示的时间戳；无法解析时返回 null。
     *
     * 关键点：没有时区标记的字符串**按北京时间**解析 —— 因为服务端已经转过了。
     */
    fun parse(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        val hasUtcMark = s.endsWith("Z") || s.endsWith("z") ||
                Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(s)
        val head = s.removeSuffix("Z").removeSuffix("z")
            .replace(" ", "T")
            .substringBefore('.')
            .substringBefore('+')
        if (head.length < 16) return null

        return try {
            PARSE.get().apply {
                timeZone = TimeZone.getTimeZone(if (hasUtcMark) "UTC" else BEIJING)
            }.parse(head)?.time
        } catch (e: Exception) {
            null
        }
    }

    /** "2026-10-08 16:28" */
    fun formatShort(raw: String?): String {
        val t = parse(raw) ?: return raw?.trim()?.replace('T', ' ')?.take(16).orEmpty()
        return DISPLAY_SHORT.apply { timeZone = TimeZone.getTimeZone(BEIJING) }.format(t)
    }

    /** "2026-10-08 16:28:20" */
    fun formatFull(raw: String?): String {
        val t = parse(raw) ?: return raw?.trim()?.replace('T', ' ').orEmpty()
        return DISPLAY_FULL.apply { timeZone = TimeZone.getTimeZone(BEIJING) }.format(t)
    }

    /**
     * "还剩 3 天" / "已到期" —— 证据保留倒计时。
     *
     * ## 天数必须向上取整
     * AI 分析结论里会写"还剩 14 天后到期"，那是按自然日对齐算的。
     * 如果这里按已过去时长向下取整，剩 13 天半会显示"还剩 13 天"，
     * 和 AI 说的 14 天对不上，子女会以为数据有问题。
     * 所以剩余时间一律进位成整天，宁可多算不可少算。
     */
    fun remainingText(retentionUntil: String?, nowMs: Long = System.currentTimeMillis()): String {
        val t = parse(retentionUntil) ?: return "时间未知"
        val diff = t - nowMs
        if (diff <= 0) return "已到期（或正在删除）"
        val days = (diff + 86_400_000L - 1) / 86_400_000L
        return when {
            days >= 1 -> "还剩 $days 天"
            diff >= 3_600_000L -> "还剩 ${diff / 3_600_000L} 小时"
            else -> "还剩不到 1 小时"
        }
    }
}