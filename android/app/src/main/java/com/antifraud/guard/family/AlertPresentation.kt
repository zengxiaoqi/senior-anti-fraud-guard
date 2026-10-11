package com.antifraud.guard.family

import org.json.JSONObject

/**
 * 告警的「怎么告诉子女」——按事件类型决定弹窗标题、正文与主按钮动作。
 *
 * ## 为什么需要这一层（不能只靠 shouldInterrupt 的真假）
 * [RiskAlertPolicy.shouldInterrupt] 只回答"要不要打断子女"，它是个布尔值。
 * 但弹窗文案与按钮动作必须**跟着事件类型变**，否则会出现明显荒唐的界面：
 *
 *  | 事件 | 实际该做什么 | 统一文案会怎么说 |
 *  |---|---|---|
 *  | COERCION_RISK | 立刻打断正在进行的通话 | ✅ 正确 |
 *  | ELDER_OFFLINE  | 打电话 / 上门找人 | ❌ "是否立即发起远程打断？" |
 *  | FALL_RISK      | 立刻过去看人 | ❌ 同上 |
 *
 * 老人失联时手机可能没电、可能被摔坏、或者根本不在身边 ——
 * 给子女一个"远程打断"按钮，点了只会得到一句"老人端当前不在线"。
 * 在一个**本该联系不上**的场景里反复提供无效操作，
 * 会让子女从"立刻打电话"变成"点一下试试"，直接延误救援。
 *
 * 所以这里给每类告警配一句「子女此刻该做的那个动作」，而不是通用话术。
 */
object AlertPresentation {

    /** 主按钮点下去要做的事 */
    enum class Action {
        /** 发起远程打断（仅对"正在进行中的通话/操作"有意义） */
        INTERRUPT,

        /** 不提供主动作：只能打电话核实，任何远程操作都没意义 */
        CALL_ELDER
    }

    data class Presentation(
        val title: String,
        val message: String,
        val primaryLabel: String,
        val action: Action,
        /** 强调色：状态色，不随主题变（见 attrs.xml 注释） */
        val accentColor: Int
    )

    /**
     * 由事件类型 + 级别算出弹窗怎么显示。
     *
     * 未识别的类型回退到"疑似风险事件 + 建议打断"：宁可保守（给一个多半用不上的按钮），
     * 也不要因为文案缺失而让子女不知道该干什么。
     */
    fun of(eventType: String?, severity: String?, details: JSONObject? = null): Presentation {
        val type = eventType?.trim()?.uppercase().orEmpty()
        val sev = severity?.trim()?.uppercase().orEmpty()

        return when (type) {
            "ELDER_OFFLINE" -> Presentation(
                title = "📴 老人已失联",
                message = buildString {
                    append(minutesText(details?.optInt("minutes_since_report", -1) ?: -1))
                    append("，完全没有上报任何数据。\n\n")
                    append("请立即打电话联系；如果打不通，请尽快上门查看。")
                },
                primaryLabel = "拨打子女端提醒",
                action = Action.CALL_ELDER,
                accentColor = 0xFFEF4444.toInt()
            )

            "FALL_RISK" -> Presentation(
                title = "🚑 检测到疑似跌倒",
                message = buildString {
                    append("手机检测到强烈撞击后长时间没有移动。\n")
                    val confirmed = details?.optBoolean("elder_confirmed", false) == true
                    if (confirmed) {
                        append("老人已确认平安，本次为提醒级事件。")
                    } else {
                        append("老人 60 秒内没有任何响应，可能无法自己操作手机。\n\n")
                        append("请立即打电话联系；如果打不通，请尽快上门查看。")
                    }
                },
                primaryLabel = "立即联系老人",
                action = Action.CALL_ELDER,
                accentColor = 0xFFEF4444.toInt()
            )

            "SOS" -> Presentation(
                title = "🆘 老人主动求助",
                message = "老人刚刚按下了紧急求助，请立刻联系并确认其安全。",
                primaryLabel = "立即联系老人",
                action = Action.CALL_ELDER,
                accentColor = 0xFFEF4444.toInt()
            )

            "ELDER_SILENT" -> Presentation(
                title = "🔕 长时间没有上报",
                message = buildString {
                    append(minutesText(details?.optInt("minutes_since_report", -1) ?: -1))
                    append("没有任何上报数据。\n\n")
                    append("可能只是手机静音、没电或在休息，建议先打个电话问问。")
                },
                primaryLabel = "知道了",
                action = Action.CALL_ELDER,
                accentColor = 0xFFF59E0B.toInt()
            )

            "ELDER_RECONNECTED" -> Presentation(
                title = "✅ 老人已恢复上报",
                message = "守护数据已经恢复正常，此前的中断已结束。",
                primaryLabel = "知道了",
                action = Action.CALL_ELDER,
                accentColor = 0xFF10B981.toInt()
            )

            else -> {
                val levelText = when (sev) {
                    "HIGH" -> "高危"
                    "MEDIUM" -> "中危"
                    else -> "需留意"
                }
                Presentation(
                    title = "⚠️ 收到紧急防诈预警",
                    // 注意 ${} 而不是 $levelText：Kotlin 标识符允许 CJK 字母，
                    // "$levelText状态" 会被整体解析成一个不存在的标识符 levelText状态。
                    // 插值后面紧跟中文时必须加花括号，这是本项目反复踩过的坑。
                    message = "老人正处于${levelText}状态（$type）。是否立即发起远程打断？",
                    primaryLabel = "强行打断",
                    action = Action.INTERRUPT,
                    accentColor = if (sev == "HIGH") 0xFFEF4444.toInt() else 0xFFF59E0B.toInt()
                )
            }
        }
    }

    /** 分钟数 → "已 3 小时 20 分钟没有上报"；数字缺失时给一句不带时长的兜底 */
    private fun minutesText(minutes: Int): String {
        if (minutes < 0) return "老人已长时间"
        if (minutes < 60) return "老人已 $minutes 分钟"
        val h = minutes / 60
        val m = minutes % 60
        return if (m > 0) "老人已 $h 小时 $m 分钟" else "老人已 $h 小时"
    }
}
