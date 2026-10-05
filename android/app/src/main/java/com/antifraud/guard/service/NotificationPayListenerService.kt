package com.antifraud.guard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.util.regex.Pattern

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

                // 3. 在老人手机前台弹出安全核实强提醒
                val alertIntent = android.content.Intent(this, com.antifraud.guard.EmergencyAlertActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(com.antifraud.guard.EmergencyAlertActivity.EXTRA_TITLE, "💳 大额支出安全核验提醒！")
                    putExtra(com.antifraud.guard.EmergencyAlertActivity.EXTRA_MESSAGE,
                        "检测到刚刚产生一笔支出：¥${amount}元\n收款方：$payee\n\n子女已同步收到此笔交易通知。若系被虚假宣传或陌生人诱导转账，请立即停止后续操作并致电子女！")
                    putExtra(com.antifraud.guard.EmergencyAlertActivity.EXTRA_FROM, "支付安全守护服务")
                }
                startActivity(alertIntent)
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
