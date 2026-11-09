package com.antifraud.guard.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * 通话筛选角色挂名服务：角色在位时放行所有来电（不拦截、不拒绝、
 * 不跳过系统通话记录与通知），确保系统筛选链路无副作用。
 *
 * ## 历史包袱：时长预警已移交 CallRiskWatcher（2026-11-09）
 * 这里曾做「通话时长预警」——onScreenCall（响铃）即起每分钟 Handler 计时，
 * 到阈值上报 CALL_RISK。2026-11-07 修过两轮（重入保护 + Doze 超窗丢弃）仍有
 * 原则性缺陷：onScreenCall 拿不到接通/挂断事件，计时起点是响铃而非摘机——
 *  - 未接通的电话也计时：响铃后墙钟时间跑到阈值 → 纯属误报的高危告警
 *  - 短通话按「到阈值才报」虚标为阈值整值（2 分钟的电话报成 15 分钟）
 *  - Doze 冻结 Handler，唤醒后 elapsed=「从响铃到现在」（曾实证 773 分钟）
 *
 * 真实通话时长只在 CallRiskWatcher 的 OFFHOOK→IDLE 状态机上存在（duration_sec），
 * 挂机即结算、未接通不计入。时长预警唯一执行者 = CallRiskWatcher.finishCall()。
 * 本服务不再持有任何计时器与上报逻辑。
 *
 * 角色状态可用 [com.antifraud.guard.util.SystemPermissionState.isCallScreeningRole] 读出，
 * 仍纳入守护健康自检（GuardKeepAliveScheduler.healthReport）。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CallScreeningGuardService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // 默认放行电话，不做任何拦截或监测
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()
        respondToCall(callDetails, response)
    }
}
