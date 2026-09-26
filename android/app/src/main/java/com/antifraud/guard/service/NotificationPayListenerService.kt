package com.antifraud.guard.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.antifraud.guard.api.ApiClient
import org.json.JSONObject
import java.util.regex.Pattern

class NotificationPayListenerService : NotificationListenerService() {

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

            // 额度超过 500 元判定为需要同步告警的大额支付
            if (amount >= 500.0) {
                val details = JSONObject().apply {
                    put("amount", amount)
                    put("source", sourcePkg)
                    put("raw_text", text)
                    put("payee_name", extractPayee(text))
                    put("order_no", "ORD_ANDROID_${System.currentTimeMillis()}")
                }

                ApiClient.reportRiskEvent(
                    elderId = 1,
                    eventType = "PAYMENT_RISK",
                    severity = "HIGH",
                    details = details
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
