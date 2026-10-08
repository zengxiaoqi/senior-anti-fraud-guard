package com.antifraud.guard.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import org.json.JSONObject
import java.util.Date

/**
 * 通话时长监测（依赖本 App 被授予「呼叫筛选」/「来电显示」系统角色）。
 *
 * ## 当前局限（Phase 1 会补齐）
 * 这个服务只覆盖**呼入**、只在系统愿意回调我们时才回调，而且：
 *   - 没有 PhoneStateListener 兜底（拿不到呼出通话）
 *   - 没有通话记录落库、没有陌生号码判定、没有频次判定
 *   - 通话结束检测依赖下一次 onScreenCall，属于近似推断
 *
 * 未被授予系统角色时 onScreenCall **根本不会被调用**，整条线静默失效。
 * 角色状态可用 [com.antifraud.guard.util.SystemPermissionState.isCallScreeningRole] 读出，
 * 并已纳入守护健康自检（GuardKeepAliveScheduler.healthReport）。
 */
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

        // 阈值必须从 GuardConfig 读：以前这里是硬编码 15 分钟，
        // 于是设置页里"通话预警时长"改什么都没用 —— 配置项是死的，
        // 界面上还一切正常。现在与支付阈值一样走本机+云端双向同步。
        val thresholdMinutes = GuardConfig.callThresholdMinutes.coerceIn(1, 240)
        Log.i(TAG, "开始监测通话 $phoneNumber，预警阈值 ${thresholdMinutes} 分钟")

        val runnable = object : Runnable {
            override fun run() {
                val elapsedMinutes = (System.currentTimeMillis() - callStartTime) / (1000 * 60)

                if (elapsedMinutes >= thresholdMinutes) {
                    lastDurationReportAt = System.currentTimeMillis()
                    val details = JSONObject().apply {
                        put("caller_number", phoneNumber)
                        put("duration_minutes", elapsedMinutes)
                        put("threshold_minutes", thresholdMinutes)
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

    companion object {
        private const val TAG = "CallScreeningGuard"

        /**
         * 最近一次由本服务上报的通话时长预警时刻（epoch ms）。
         * CallRiskWatcher 在通话结束时用它去重：角色在位的呼入通话，
         * 时长预警由本服务负责，Watcher 不再补报同一条，防止子女端收到双告警。
         * 注意 companion 不能声明为 private：Watcher（同包不同类）要读这个值。
         */
        @Volatile
        var lastDurationReportAt: Long = 0L
    }
}
