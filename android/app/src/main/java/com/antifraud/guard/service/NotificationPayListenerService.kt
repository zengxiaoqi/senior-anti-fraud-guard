package com.antifraud.guard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * 通知栏扣款监听。
 *
 * 权限由用户在系统「通知使用权」里授予（见 SystemPermissionState.isNotificationListenerEnabled）。
 *
 * ## 已知局限（Phase 1 修）
 *  - 包名匹配用的是 `contains("mm")` 这种子串判断，会误命中任意含 "mm" 的包名
 *  - 金额正则不支持千分位（`¥1,234.00` 只会提取到 `1`）、不支持"万"后缀、只取首个匹配
 *  - 无去重：同一笔交易可能重复上报
 *  - 微信支付/支付宝的到账通知多数被系统静默，捕获率有限
 */
class NotificationPayListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        com.antifraud.guard.config.GuardConfig.init(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: ""
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val fullText = "$title $text"

        // 识别微信支付 / 支付宝 / 银联扣款短信关键字
        if (packageName.contains("mm") || packageName.contains("alipay") || packageName.contains("mms") || fullText.contains("扣款") || fullText.contains("支付")) {
            parseAndReportPayment(packageName, fullText)
        }
    }

    private fun parseAndReportPayment(sourcePkg: String, text: String) {
        // 正则表达解析金额：如 "成功付款3000.00元" 或 "扣款 500 元"
        val pattern = Pattern.compile("(?:支付|付款|扣款|消费|转账)\\s*(?:人民币|￥|¥)?\\s*(\\d+(?:\\.\\d{1,2})?)")
        val matcher = pattern.matcher(text)

        if (matcher.find()) {
            val amountStr = matcher.group(1)
            val amount = amountStr?.toDoubleOrNull() ?: 0.0

            val threshold = GuardConfig.paymentThreshold
            if (amount >= threshold) {
                val payee = extractPayee(text)
                val details = JSONObject().apply {
                    put("amount", amount)
                    put("source", sourcePkg)
                    put("raw_text", text)
                    put("payee_name", payee)
                    put("order_no", "ORD_ANDROID_${System.currentTimeMillis()}")
                }

                // 1. 上报服务端
                ApiClient.reportRiskEvent(
                    elderId = GuardConfig.elderId,
                    eventType = "PAYMENT_RISK",
                    severity = "HIGH",
                    details = details
                )

                // 2. 存入本地数据库
                try {
                    val dbHelper = com.antifraud.guard.db.RiskEventDbHelper(this)
                    dbHelper.insertEvent(GuardConfig.elderId, "PAYMENT_RISK", "HIGH", details)
                } catch (ignored: Exception) {}

                // 3. 拉起老人端安全核实强提醒。
                //
                // 必须走 EmergencyAlertLauncher 的三级降级链，不能裸 startActivity：
                // NotificationListenerService 是绑定服务、没有前台地位，
                // Android 10+ 会把后台的 startActivity **静默丢弃**（不报错、不崩溃）。
                // 而老人恰恰是在锁屏/后台时收到扣款通知 —— 也就是最需要警报的时刻。
                com.antifraud.guard.util.EmergencyAlertLauncher.launch(
                    context = this,
                    title = "💳 大额支出安全核验提醒！",
                    message = "检测到刚刚产生一笔支出：¥${amount}元\n收款方：$payee\n\n" +
                        "子女已同步收到此笔交易通知。若系被虚假宣传或陌生人诱导转账，" +
                        "请立即停止后续操作并致电子女！",
                    fromUser = "支付安全守护服务"
                )
            }
        }
    }

    private fun extractPayee(text: String): String {
        return if (text.contains("向")) {
            text.substringAfter("向").substringBefore("支付").trim()
        } else {
            "微信/支付宝/银行卡商户"
        }
    }
}
