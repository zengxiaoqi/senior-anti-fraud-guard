package com.antifraud.guard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 通知栏扣款监听（转账 / 微信支付 / 支付宝 / 银行支出通知）。
 *
 * 权限由用户在系统「通知使用权」里授予（见 SystemPermissionState.isNotificationListenerEnabled）。
 *
 * ## 捕获范围与方向
 *  - 支出方向（告警重点）：微信支付凭证、支付宝账单、银行「支出/扣款」类通知
 *    → 金额 ≥ paymentThreshold 时报 HIGH，子女端高优推送 + 老人端安全核实强提醒
 *  - 收入方向：「收到转账 / 向你转账 / 到账」→ 报 LOW（记录留痕，不打扰老人）
 *    —— 收到陌生转账常常是被骗流程的前奏（测试卡 / 洗钱过账），子女端可见即可
 *  - 有「转账/支付」字样但提不出金额的通知（如微信聊天内「[转账]」占位文案）
 *    → 只打 Logcat 摸底，不制造告警噪音
 *
 * ## 与旧版差异（2026-10-20 重写）
 *  - 金额解析容忍插入词（「转账**了**500」「付款**金额**1,000」「成功付款」），
 *    支持千分位、「万」后缀、独立「¥100」样式；且要求「元」后缀或「¥」前缀兜底，
 *    避免「支付时间10月」这类误提
 *  - 去重：同一通知 key 10s / 同包名同内容 60s 内只处理一次
 *    （onNotificationPosted 对同一条通知会多次回调）
 *  - 商户/对方提取支持「向「XX」付款」「向XX转账」「付款给XX」「收款方：XX」等句式
 *  - 上报失败不再重复落本地库（ApiClient.reportRiskEvent 内部已先 insertEvent）
 */
class NotificationPayListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifPayListener"
        private const val SAME_NOTIFICATION_WINDOW_MS = 10_000L
        private const val SAME_TEXT_WINDOW_MS = 60_000L

        /** 动词 + 容忍 0~4 个插入字（了/成功/金额/给XX）+ ¥ + 数字（不强制「元」） */
        private val AMOUNT_VERB_SYMBOL: Pattern = Pattern.compile(
            "(?:支付|付款|扣款|消费|支出|转账|收款|到账)[^0-9¥￥%]{0,4}(?:人民币)?(?:￥|¥)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(万)?元?"
        )

        /** 动词 + 数字 + 必须有「元」后缀（防「支付时间10月」式误提） */
        private val AMOUNT_VERB_YUAN: Pattern = Pattern.compile(
            "(?:支付|付款|扣款|消费|支出|转账|收款|到账)[^0-9¥￥%]{0,4}(?:人民币)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(万)?元(?![0-9])"
        )

        /** 独立「¥100」/「¥100元」样式（微信支付凭证常写「-¥100.00」） */
        private val AMOUNT_SYMBOL: Pattern = Pattern.compile(
            "(?:￥|¥)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(万)?元?"
        )

        private val INCOME_PATTERN = Regex(
            "收到转账|向你转账|给您转账|已到账|到账通知|收款成功|转入成功|入账|收钱"
        )
        private val OUT_PATTERN = Regex("支付|付款|扣款|消费|支出|转账|收款")
    }

    /** key → 最近处理时间戳，兼做通知 key 与 内容 hash 两类去重 */
    private val recentKeys = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        GuardConfig.init(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.notification == null) return
        if (sbn.isOngoing) return // 常驻通知（进度/保活）不是交易通知

        val packageName = sbn.packageName ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString()
            ?: extras.getCharSequence("android.bigText")?.toString() ?: ""

        val fullText = "$title $text".replace('\n', ' ').trim()
        if (fullText.isEmpty()) return

        // ── 去重：同一通知 key 短窗口 / 同包名同内容 60s ──
        val now = System.currentTimeMillis()
        recentKeys.entries.removeAll { now - it.value > SAME_TEXT_WINDOW_MS }
        val keyId = sbn.key ?: "$packageName/${sbn.id}"
        val textKey = "$packageName#${fullText.hashCode()}"
        if (recentKeys[keyId]?.let { now - it < SAME_NOTIFICATION_WINDOW_MS } == true) return
        if (recentKeys[textKey] != null) return
        recentKeys[keyId] = now
        recentKeys[textKey] = now

        val direction = detectDirection(fullText) ?: return
        handlePayment(packageName, fullText, direction)
    }

    private fun detectDirection(text: String): String? = when {
        INCOME_PATTERN.containsMatchIn(text) -> "in"
        OUT_PATTERN.containsMatchIn(text) -> "out"
        else -> null
    }

    private fun handlePayment(packageName: String, text: String, direction: String) {
        val isIncoming = direction == "in"
        val amount = parseAmount(text)

        if (amount == null) {
            // 聊天内「[转账]」占位等提不出金额：只留本地日志摸底，不上报不告警
            Log.i(TAG, "payment-like notification without amount: pkg=$packageName text=${text.take(80)}")
            return
        }

        val payee = extractPayee(packageName, text, isIncoming)
        val severity = if (!isIncoming && amount >= GuardConfig.paymentThreshold) "HIGH" else "LOW"

        val details = JSONObject().apply {
            put("amount", amount)
            put("direction", if (isIncoming) "in" else "out")
            put("source", packageName)
            put("payee_name", payee)
            put("order_no", "ORD_ANDROID_${System.currentTimeMillis()}")
            put("raw_text", text.take(200))
        }

        if (GuardConfig.elderId > 0) {
            // 内部已先落本地库再异步上报
            ApiClient.reportRiskEvent(
                elderId = GuardConfig.elderId,
                eventType = "PAYMENT_RISK",
                severity = severity,
                details = details
            )
        }

        // 老人端强提醒：仅真实支出且达到阈值才打扰（收入/小额只留痕）
        if (severity == "HIGH") {
            com.antifraud.guard.util.EmergencyAlertLauncher.launch(
                context = this,
                title = "💳 大额支出安全核验提醒！",
                message = "检测到刚刚产生一笔支出：¥${formatAmount(amount)}元\n收款方：$payee\n\n" +
                    "子女已同步收到此笔交易通知。若系被虚假宣传或陌生人诱导转账，" +
                    "请立即停止后续操作并致电子女！",
                fromUser = "支付安全守护服务"
            )
        }
    }

    private fun parseAmount(text: String): Double? {
        val m = pickMatch(AMOUNT_VERB_SYMBOL.matcher(text))
            ?: pickMatch(AMOUNT_VERB_YUAN.matcher(text))
            ?: pickMatch(AMOUNT_SYMBOL.matcher(text))
            ?: return null
        val n = m.first.replace(",", "").toDoubleOrNull() ?: return null
        return if (m.second) n * 10000 else n
    }

    private fun pickMatch(matcher: java.util.regex.Matcher): Pair<String, Boolean>? {
        if (!matcher.find()) return null
        val raw = matcher.group(1) ?: return null
        return Pair(raw, matcher.group(2) != null)
    }

    private fun formatAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

    private fun extractPayee(sourcePkg: String, text: String, isIncoming: Boolean): String {
        // 「」括号名优先（微信支付凭证、账单常用）
        Regex("「([^」]{1,30})」").find(text)?.groupValues?.get(1)?.let { return it.trim() }
        // 支出：向XX付款 / 向XX转账 / 给XX转账
        if (!isIncoming) {
            Regex("向([^，。,\\s]{1,20}?)(?:支付|付款|转账)").find(text)?.groupValues?.get(1)?.let { return it.trim() }
        }
        // 付款给XX / 转账给XX
        Regex("(?:付款给|转账给)([^，。,\\s]{1,20}?)(?:[0-9]+|元|\\s|$)").find(text)?.groupValues?.get(1)?.let {
            return it.trim()
        }
        // 收款方 / 收款账户
        Regex("收款方[户名]?[:：]?([^，。,\\s]{1,20})").find(text)?.groupValues?.get(1)?.let { return it.trim() }
        // 收入：XX向你转账
        if (isIncoming) {
            Regex("([^，。,\\s]{1,20}?)(?:向你|给您)转账").find(text)?.groupValues?.get(1)?.let { return it.trim() }
        }
        return when {
            sourcePkg.contains("alipay") -> "支付宝商户"
            sourcePkg.endsWith(".mm") || sourcePkg.endsWith(".mms") -> "微信支付"
            else -> "银行卡/支付渠道商户"
        }
    }
}
