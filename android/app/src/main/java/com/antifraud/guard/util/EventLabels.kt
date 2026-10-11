package com.antifraud.guard.util

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 风险事件的统一文案与聚合工具（子女端共用）。
 *
 * 为什么要收敛到一个 object：此前 DashboardFragment / AlertsFragment / RiskLogActivity
 * 各自维护一份 eventName 映射，已经出现漂移（"📍 敏感地点停留过久" vs "长时间停留"），
 * 且控制台把内部枚举名（APP_CRASH）原样拼给用户。这里作为唯一事实源：
 *  - eventName/severityName/severityColor：三页共用的基础映射
 *  - formatOverview：控制台概览的去重聚合（"位置更新 ×2"而不是两条一样的行）
 *  - shortLocalTime：时间戳（ISO UTC 或北京时间裸串）→ "今天 12:59" / "昨天 18:44" / "10-08"
 *
 * 设计约束：
 *  - 不抛异常：事件 JSON 来自网络，字段可能缺失/null，全部走 opt 系列。
 *  - 纯函数、不依赖 Android 类（除 Color 常量用 Int），可加 JVM 单测。
 */
object EventLabels {

    /**
     * 事件类型 → 守护人可读文案。
     * APP_CRASH 是 CrashReporter 上报的诊断事件，历史上直接显示枚举名，
     * 用户完全看不懂 —— 映射成"应用异常退出"，详情弹窗里仍有原始堆栈可查。
     */
    fun eventName(type: String): String = when (type) {
        "CALL_RISK" -> "📞 通话风险"
        "COERCION_RISK" -> "🚨 通话中被诱导操作"
        "CALL_STAT" -> "📞 通话记录"
        "GEOFENCE_DWELL" -> "⏱ 敏感地点停留过久"
        "PAYMENT_RISK" -> "💳 大额扣款"
        "SOS" -> "🆘 紧急求助"
        "REMOTE_INTERRUPT" -> "🚨 远程打断"
        "FALL_RISK" -> "🚑 疑似跌倒"
        "ELDER_OFFLINE" -> "📴 老人失联"
        "ELDER_SILENT" -> "🔕 长时间无上报"
        "ELDER_RECONNECTED" -> "✅ 已恢复上报"
        "LOCATION_RISK" -> "📍 敏感地点停留"
        "GEOFENCE_RECORDING" -> "📍 进入敏感地点（自动录音）"
        "GEOFENCE_EXIT" -> "📍 离开敏感地点"
        "LOCATION_UPDATE" -> "📍 位置更新"
        "DEVICE_ONLINE" -> "🟢 设备上线"
        "APP_CRASH" -> "🛠️ 老人端应用异常退出"
        "PING_TEST" -> "🔍 连接测试"
        else -> type
    }

    fun severityName(sev: String): String = when (sev) {
        "HIGH" -> "高危"
        "MEDIUM" -> "中危"
        else -> "低危"
    }

    /** 严重度 → 状态色（红/黄/绿）。状态色刻意硬编码，不随主题变（见 attrs.xml 注释）。 */
    fun severityColor(sev: String): Int = when (sev) {
        "HIGH" -> 0xFFEF4444.toInt()
        "MEDIUM" -> 0xFFF59E0B.toInt()
        else -> 0xFF10B981.toInt()
    }

    /**
     * 两事件是否为"同一种"：类型与严重度都相同。
     *
     * 这是列表页连续去重合并的统一判据（告警页 buildRows 与控制台 formatOverview 同源）：
     *  - 同类型不同级别不合并（高危的位置更新不能淹没进低危堆里）；
     *  - 只合并"连续"出现的事件，中间插入其他类型即断开——
     *    老人 10 点和 16 点各上报一次位置，是两件事不是一件。
     */
    fun sameKind(a: JSONObject, b: JSONObject): Boolean =
        a.optString("event_type") == b.optString("event_type") &&
            a.optString("severity") == b.optString("severity")

    /**
     * 同上，给数据源不是 JSONObject 的调用方（老人端本地库 RiskEvent 是 data class）。
     * 判据必须和 [sameKind] 完全一致 —— 两端列表"合并成 ×N"的行为才不会分叉。
     */
    fun sameKind(aType: String, aSev: String, bType: String, bSev: String): Boolean =
        aType == bType && aSev == bSev

    /**
     * 控制台概览文案：把事件数组聚合成最多 [maxLines] 行。
     *
     * 聚合规则（2026-10-10 审查结论）：
     *  - PING_TEST / DEVICE_ONLINE 属于心跳噪音，概览里不出现（告警页仍可见）；
     *  - 连续同类型事件合并成一行 "名称 ×N"，不再把"位置更新（低危）"原样刷两遍；
     *  - 每行附相对时间（今天/昨天/日期），用户能判断新旧；
     *  - 过滤后一行都不剩时，明确说"一切正常"，不留空白。
     */
    fun formatOverview(arr: JSONArray?, maxLines: Int = 4): String {
        if (arr == null || arr.length() == 0) return "✅ 暂无风险动态，老人一切正常"

        data class Row(val type: String, val sev: String, val time: String, var count: Int, var index: Int = 0)

        val rows = mutableListOf<Row>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val type = e.optString("event_type", "")
            // 心跳类噪音：既无风险含义又高频，混进概览只会淹没真正的告警
            if (type == "PING_TEST" || type == "DEVICE_ONLINE") continue
            val time = shortLocalTime(e.optString("created_at", ""))
            val last = rows.lastOrNull()
            if (last != null && sameKind(
                    arr.optJSONObject(last.index) ?: JSONObject(), e
                )
            ) {
                last.count++
            } else {
                rows.add(Row(type, e.optString("severity", ""), time, 1).also { it.index = i })
            }
        }
        if (rows.isEmpty()) return "✅ 暂无风险动态，老人一切正常"

        val sb = StringBuilder()
        for ((idx, r) in rows.withIndex()) {
            if (idx >= maxLines) {
                sb.append("\n… 及另外 ").append(rows.size - maxLines).append(" 类动态")
                break
            }
            sb.append("\n• ").append(eventName(r.type))
            if (r.count > 1) sb.append(" ×").append(r.count)
            sb.append("（").append(severityName(r.sev)).append("）")
            if (r.time.isNotEmpty()) sb.append(" · ").append(r.time)
        }
        return sb.toString()
    }

    /**
     * 事件详情 → 一行人话（列表行用）。
     *
     * 以前直接把 details 键值对平铺（"latitude: 28.2738；longitude: 113.0616；…"），
     * 坐标数字对读者毫无意义 —— 人要的是"在哪"，不是经纬度。
     * 服务端会对带坐标的告警异步补 location_name（真名，写时+读时双路）；
     * 这里优先展示它，坐标原始值不再出现在列表行（详情弹窗里仍可查证）。
     *
     * @param forElder true = 老人端自看（措辞用"您"）；false = 守护人视角（用"老人"）。
     *   同一个 elder_confirmed=false 在子女端是"老人未响应确认"、在老人端是"您当时没有回应"——
     *   同一事实两种读法，但只维护一份映射，避免又漂移出两套文案。
     *
     * 约束：不抛异常；键值对顺序跟随 JSONObject 迭代序，无关紧要。
     */
    fun formatDetails(d: JSONObject?, forElder: Boolean = false): String {
        if (d == null || d.length() == 0) return ""
        val parts = mutableListOf<String>()

        val locName = d.optString("location_name", "").trim()
        if (locName.isNotEmpty()) parts.add("📍 $locName")

        val desc = d.optString("event_desc", "").trim()
        if (desc.isNotEmpty()) parts.add(desc)

        for (k in d.keys()) {
            if (k in DETAIL_SKIP) continue
            val raw = d.opt(k) ?: continue
            if (raw is JSONObject || raw is JSONArray) continue
            val v = raw.toString().trim()
            if (v.isEmpty() || v == "null") continue
            // 三类一律不进列表行（详情弹窗里仍可查证）：
            //  - 坐标串：老人端本地库没有 location_name，会直接露出 "GPS 位置 (28.27, 113.06)"
            //  - 包名：NotificationPayListener 的 source="com.eg.android.AlipayGphone"，谁也读不懂
            //  - 超长值：通知原文 raw_text、崩溃堆栈 stack 会把卡片撑成一大坨
            if (looksLikeCoord(v) || looksLikePackage(v) || v.length > 60) continue
            val text = detailValue(k, v, forElder)
            if (text.isEmpty()) continue
            parts.add(text)
        }
        return parts.joinToString("；")
    }

    /** 内部诊断/重复/不可读字段：对两端读者都没有价值，只在详情弹窗的原始记录里保留 */
    private val DETAIL_SKIP = setOf(
        "location_name", "event_desc", "latitude", "longitude", "lat", "lng",
        "trigger", "device", "impact_at", "timestamp", "from", "window_hours",
        "raw_text", "stack", "file", "geofence_id", "dwell_minutes_threshold",
        // source 在扣款事件里是包名（已被包名规则拦掉），在崩溃事件里是 "CrashReporter"
        // —— 两种都不是人话，一并跳过
        "source"
    )

    private fun looksLikeCoord(s: String): Boolean =
        Regex("\\d+\\.\\d+\\s*[,，]\\s*\\d+\\.\\d+").containsMatchIn(s)

    /** 包名形态：`com.eg.android.AlipayGphone`。值里没有空格且至少三段点分小写 */
    private fun looksLikePackage(s: String): Boolean =
        Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){2,}$").matches(s)

    /** 单个 details 键 → 人话短语；返回空串表示这条对读者没有意义，列表行里省略 */
    private fun detailValue(k: String, v: String, forElder: Boolean): String = when (k) {
        "elder_confirmed" -> if (v == "true") {
            if (forElder) "您当时已确认平安" else "老人已确认平安"
        } else {
            if (forElder) "您当时没有回应" else "老人未响应确认"
        }
        "is_away_from_home" -> if (v == "true") {
            if (forElder) "您当时不在家附近" else "已离家外出"
        } else {
            if (forElder) "您当时在家附近" else "在家附近"
        }
        // 布尔三态不能直接打印 true/false：老人读到的会是"陌生号码 true"
        "is_stranger" -> when (v) {
            "true" -> "陌生号码"
            "false" -> "熟悉号码"
            else -> "号码来源不明"
        }
        "coercion_reported" -> if (v == "true") "通话中疑似被诱导操作" else ""
        "amount" -> "金额 ${v} 元"
        "calls_in_2h" -> "两小时内来电 ${v} 次"
        "duration_sec" -> "通话 ${v} 秒"
        "geofence_name" -> "敏感地点 ${v}"
        // 通话中切到远程控制/支付 App 是"被诱导操作"最硬的证据，必须说清楚
        "coercion_app" -> "通话中打开了 ${v}"
        // 阈值不能也叫"已持续"：否则列表行会出现"已持续 25 分钟；已持续 20 分钟"
        "threshold_minutes" -> "超过 ${v} 分钟会提醒"
        "title", "message", "note" -> v
        else -> {
            val label = detailLabel(k)
            when {
                label.isEmpty() -> v
                k.endsWith("_min") || k.endsWith("_minutes") -> "${label} ${v} 分钟"
                else -> "${label} ${v}"
            }
        }
    }

    /**
     * 老人端"我该怎么办"：这类记录对老人自己是 actionable 的，
     * 光告诉他"发生过高危事件"没用 —— 他要的是下一步做什么。
     * 只在详情弹窗里出现，不进列表行（列表行只回答"发生了什么"）。
     */
    fun elderAdvice(type: String): String = when (type) {
        "PAYMENT_RISK" -> "如果不是您本人操作，请立刻拨打银行客服电话冻结，并马上通知子女。"
        "CALL_RISK", "COERCION_RISK" -> "对方一旦提到转账、验证码、屏幕共享，请直接挂断，再打给子女确认。"
        "FALL_RISK" -> "若身体不适或受伤，请直接拨打 120，并让子女知道您的位置。"
        "SOS" -> "求助已发送给子女。请把手机放在身边、保持畅通，等待回电。"
        "REMOTE_INTERRUPT" -> "子女判断这通电话有风险才打断您。请先挂断电话，再回拨子女核实。"
        "LOCATION_RISK", "GEOFENCE_DWELL", "GEOFENCE_RECORDING" ->
            "如果这个地点不是您本人去的，请立刻联系子女说明情况。"
        "ELDER_OFFLINE", "ELDER_SILENT" -> "手机可能没电、没信号或被关机。恢复后会自动上报，不必操作。"
        "APP_CRASH" -> "不需要您处理，问题已自动记录。若频繁发生，重启一次手机即可。"
        else -> ""
    }

    /** details 键 → 中文标签；空串 = 值本身自解释，直接展示；未识别的键原样保留兜底 */
    private fun detailLabel(k: String): String = when (k) {
        "address" -> "位置"
        "amount" -> "金额"
        "payee_name" -> "收款方"
        "payee_account" -> "卡号"
        "order_no" -> "订单号"
        "peak_magnitude" -> "撞击峰值(m/s²)"
        "foreground_app" -> "前台应用"
        "call_number", "caller_number" -> "来电号码"
        "duration_so_far_min" -> "已通话"
        "duration_minutes", "stay_minutes", "threshold_minutes" -> "已持续"
        "duration_sec" -> "通话"
        "peak_risk" -> "峰值风险"
        "is_stranger" -> "陌生号码"
        "calls_in_2h" -> "两小时来电"
        "note" -> ""
        else -> "$k:"
    }

    /**
     * 统一时间解析入口。服务端现有两种下发格式（见 workers/src/lib/timeFormat.ts）：
     *  1) ISO UTC：      "2026-10-10T04:59:00.000Z"（老人端链路）
     *  2) 北京时间裸串： "2026-10-10 12:59:00"（子女端接口统一 toBeijing 转换后下发）
     *
     * 历史 bug（2026-10-10 线上实证）：裸串里的空格与 "yyyy-MM-dd'T'HH:mm:ss"
     * 的字面量 'T' 永远匹配不上（lenient 只放宽数值范围，不放宽字面量），
     * SimpleDateFormat 必然 ParseException → parseIsoDate 恒为 null：
     * 轨迹页 7 天过滤把所有点滤空（"近 7 天暂无轨迹数据"）、
     * 告警列表按时间过滤整页丢弃。两种格式都必须能解析。
     */
    private fun parseTime(raw: String): Date? {
        if (raw.isEmpty()) return null
        return try {
            if (raw.contains('T')) {
                // ISO：无时区标记按 UTC 处理（铁律：Z 判断在截小数点之前）
                val normalized = if (raw.endsWith("Z") || raw.contains("+")) raw
                    else raw.substringBefore('.') + "Z"
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                parser.timeZone = TimeZone.getTimeZone("UTC")
                parser.parse(normalized)
            } else {
                // 空格分隔 = 子女端北京时间裸串，按东八区解析回正确的时间点
                val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                parser.timeZone = TimeZone.getTimeZone("GMT+08:00")
                parser.parse(raw.take(19))
            }
        } catch (e: ParseException) {
            null
        }
    }

    /**
     * 时间字符串 → Date。供需要完整日期信息（如分组头带星期）的调用方使用。
     * 兼容 ISO UTC 与北京时间裸串两种格式，解析失败返回 null。
     */
    fun parseIsoDate(iso: String): Date? = parseTime(iso)

    /** 老人端本地库存的是 epoch 毫秒，与 [shortLocalTime] 同一个出口 */
    fun shortLocalTime(millis: Long): String = shortLocalTime(Date(millis))

    fun shortLocalTime(iso: String): String {
        val date = parseTime(iso) ?: return ""
        return shortLocalTime(date)
    }

    /**
     * 分组头文案：今天 / 昨天 / "M月d日 周X"。
     * 子女端 AlertsFragment.dayHeader 与老人端风险记录页共用，
     * 保证两端列表的时间分组语言一致。
     */
    fun dayHeader(millis: Long): String {
        val t = shortLocalTime(millis)
        return when {
            t.startsWith("今天") -> "今天"
            t.startsWith("昨天") -> "昨天"
            t.isEmpty() -> ""
            // 两天以上：给出"M月d日 周X"级别的可读日期，不再让用户自己算 10-06 是周几
            else -> SimpleDateFormat("M月d日 E", Locale.getDefault()).format(Date(millis))
        }
    }

    /**
     * 本机时区的"今日零点"毫秒值，供计数总览按今日口径统计。
     * 不要用 `now % 86400000` 去算：那是 UTC 日的边界，东八区会整体偏 8 小时。
     */
    fun todayStartMillis(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun shortLocalTime(date: Date): String {
        val cal = Calendar.getInstance()
        cal.time = date
        val hourStr = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val eventDay = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val dayDiff = ((today.timeInMillis - eventDay.timeInMillis) / 86_400_000L).toInt()

        return when (dayDiff) {
            0 -> "今天 $hourStr"
            1 -> "昨天 $hourStr"
            in 2..30 -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        }
    }
}
