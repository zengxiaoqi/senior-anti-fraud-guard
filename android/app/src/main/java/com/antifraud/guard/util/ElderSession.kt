package com.antifraud.guard.util

import android.content.Context
import android.content.Intent
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.ForegroundGuardService
import com.antifraud.guard.service.GuardWebSocketManager
import com.antifraud.guard.service.LocationGuardService
import com.antifraud.guard.service.RecordingGuardService
import com.antifraud.guard.service.UploadQueue

/**
 * 老人端退出的收尾动作。
 *
 * 抽出来是因为**退出入口现在有两个**：首页（历史入口）和设置页（新入口）。
 * 两份各写一遍的结果必然是某一处漏停服务 —— 而漏停的表现是"退出了但还在录音/还在上报"，
 * 这种问题在老人手机上极难被发现，等发现时已经跑了几天。
 *
 * 顺序是踩过坑的，不能改：
 *   1. 先把待发录音推一把（能发出去就别留在本机）
 *   2. 录音 → 守护 → 长连接，依次停
 *   3. 最后关总开关 —— 顺序反了会被下一次 onResume 重新拉起
 */
object ElderSession {

    fun logout(context: Context) {
        // 1) 待发录音再推一次
        if (UploadQueue.pendingCount() > 0) UploadQueue.trigger()

        // 2) 停掉所有可能在后台继续上报的服务
        context.stopService(Intent(context, RecordingGuardService::class.java))
        context.stopService(Intent(context, ForegroundGuardService::class.java))
        context.stopService(Intent(context, LocationGuardService::class.java))
        GuardWebSocketManager.stop()

        // 3) 总开关必须关掉，否则下次进首页会把服务重新拉起来
        GuardConfig.guardEnabled = false
        GuardConfig.clearElderSession()
        GuardConfig.appRole = ""
    }
}
