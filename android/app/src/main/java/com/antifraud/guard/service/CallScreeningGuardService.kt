package com.antifraud.guard.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.util.Date

@RequiresApi(Build.VERSION_CODES.Q)
class CallScreeningGuardService : CallScreeningService() {

    private var callStartTime: Long = 0
    private var incomingNumber: String? = null

    override fun onCreate() {
        super.onCreate()
        com.antifraud.guard.config.GuardConfig.init(this)
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle
        incomingNumber = handle?.schemeSpecificPart ?: "陌生号码"
        callStartTime = System.currentTimeMillis()

        // 默认放行电话，但在后台监控通话时长
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

        respondToCall(callDetails, response)

        // 开启计时判定逻辑（如果通话时间过长，上报后端）
        monitorCallDuration(incomingNumber!!)
    }

    private fun monitorCallDuration(phoneNumber: String) {
        val checkInterval = 60 * 1000L // 每分钟检查一次
        val handler = android.os.Handler(mainLooper)

        val runnable = object : Runnable {
            override fun run() {
                val elapsedMinutes = (System.currentTimeMillis() - callStartTime) / (1000 * 60)

                // 规则：陌生号码通话时长超过 15 分钟触发高危上报
                if (elapsedMinutes >= 15) {
                    val details = JSONObject().apply {
                        put("caller_number", phoneNumber)
                        put("duration_minutes", elapsedMinutes)
                        put("timestamp", Date().toString())
                    }

                    ApiClient.reportRiskEvent(
                        elderId = GuardConfig.elderId,
                        eventType = "CALL_RISK",
                        severity = "HIGH",
                        details = details
                    )
                } else {
                    handler.postDelayed(this, checkInterval)
                }
            }
        }

        handler.postDelayed(runnable, checkInterval)
    }
}
