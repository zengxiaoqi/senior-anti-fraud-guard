package com.antifraud.guard.family

import org.json.JSONObject

/**
 * 「这条告警值不值得打断子女」的判定 —— 子女端唯一权威入口。
 *
 * ## 为什么需要这道闸
 * 服务端对**每一条**风险事件都广播 RISK_ALERT（routes/events.js 无条件广播），
 * 而收到就弹「⚠️ 收到紧急防诈预警 / 长者正处于高危状态 (LOCATION_UPDATE) /
 * 是否立即发起远程打断？」。
 *
 * 老人端定位上报是心跳级的：LocationGuardService.onLocationChanged 每次回调都报一条
 * LOCATION_UPDATE，severity 写死 LOW。子女端于是被每分钟一次的"高危状态"弹窗糊满屏幕，
 * 真正该弹的 COERCION_RISK / PAYMENT_RISK 反而被淹掉 —— 漏报方向的告警疲劳。
 *
 * ## 判定标准
 * 打断能改变结果，才值得打断。详见 services/riskAlertPolicy.js 的完整说明，
 * Android / 小程序 / 服务端三处规则必须同步，改一处就要改三处。
 */
object RiskAlertPolicy {

    /** 需要子女立刻动手打断的事件类型（白名单，不是黑名单） */
    private val INTERRUPTIBLE_EVENTS = setOf("SOS", "PAYMENT_RISK", "COERCION_RISK", "CALL_RISK")

    /**
     * 事件类型 + 级别是否构成一次「值得打断」的高危告警。
     *
     * 注意 GEOFENCE_RECORDING 虽是 HIGH 但**不在**名单里：进入敏感地点自动录音
     * 是守护系统的正常动作，不是危险信号，弹红屏只会打断老人正在办的事。
     */
    fun shouldInterrupt(eventType: String?, severity: String?): Boolean {
        val type = eventType?.trim()?.uppercase().orEmpty()
        if (type !in INTERRUPTIBLE_EVENTS) return false
        // SOS 是老人主动按下的求助，任何级别都必须立刻送到子女眼前
        if (type == "SOS") return true
        return severity?.trim()?.uppercase() == "HIGH"
    }

    /**
     * 从服务端推来的 RISK_ALERT data 里读判定结果。
     *
     * 优先用服务端算好的 `interruptible`（保证 Android / 小程序 / Web 三端同一把尺子）；
     * 字段缺失时回退到本地重算，这样连老服务端也能立刻受益，不至于"必须同时升级两端"。
     */
    fun shouldInterrupt(data: JSONObject): Boolean {
        if (data.has("interruptible")) return data.optBoolean("interruptible", false)
        return shouldInterrupt(data.optString("event_type"), data.optString("severity"))
    }
}