package com.antifraud.guard.util

import com.antifraud.guard.service.GuardKeepAliveScheduler

/**
 * 守护健康自检的问题分级（唯一事实源）。
 *
 * ## 为什么必须从 Activity 里抽出来
 * 「守护设置」入口页要显示"🚨 2 项需要处理"这类摘要，而自检页要显示完整三档清单。
 * 两处各写一份判定规则，必然漂成"入口页说 3 项、点进去只有 2 项" ——
 * 这类不一致在安全类 UI 里是致命的：用户会怀疑整个自检是假的，然后一并忽略。
 *
 * ## 三档的性质完全不同，混在一起会误导用户
 *   1. **失效** = 有功能已经不能用了，必须马上修
 *   2. **降级** = 功能还能用但会漏
 *   3. **备用** = 当前没有任何已实现功能依赖它
 *
 * 把「使用情况访问」标成备用是错的：`CallRiskWatcher.sampleForegroundOnce()` 第一件事
 * 就是检查这个权限，没有它就直接 return，于是「通话中打开支付 App / 远程控制软件」
 * 的 COERCION_RISK 高危告警完全不会发生 —— 是漏报方向的告警失效。
 *
 * 告警一旦不准，真正的告警就会被当成噪音，这正是安全类 UI 最常见的失效方式。
 */
object GuardHealthAdvice {

    data class Issues(
        val blocking: List<String>,
        val degraded: List<String>,
        val upcoming: List<String>
    ) {
        /** 真正需要用户现在动手的项数（备用项不算） */
        val needFix: Int get() = blocking.size + degraded.size
        val isEmpty: Boolean get() =
            blocking.isEmpty() && degraded.isEmpty() && upcoming.isEmpty()
    }

    fun classify(h: GuardKeepAliveScheduler.HealthReport): Issues {
        val blocking = mutableListOf<String>()
        val degraded = mutableListOf<String>()
        val upcoming = mutableListOf<String>()

        if (!h.hasBackgroundLocation) {
            blocking += "位置权限只有「仅在使用中允许」：老人退出应用后系统会停止上报位置，位置守护等于没开"
        }
        // 权限全绿也可能一条也传不上去：鸿蒙/EMUI 上订阅注册成功却从不投递回调是常见形态。
        // 只要服务在跑却一次坐标都没取到，就必须报出来 —— 否则界面和自检页都是绿的，
        // 子女端却看到"一切正常"（2026-10-24 实机事故）。
        if (h.locationRunning && h.hasBackgroundLocation && h.lastFixOkAt <= 0L) {
            blocking += "位置服务在运行，但从未成功取到坐标" +
                (if (h.lastFixFailReason.isNotEmpty()) "（${h.lastFixFailReason}）" else "") +
                "。请打开系统「定位服务」总开关并开启「提高精确度 / WLAN 和蓝牙扫描」"
        }
        if (h.lastFixOkAt > 0L && h.lastReportOkAt <= 0L && h.lastReportFailReason.isNotEmpty()) {
            degraded += "已取到坐标但上报失败：${h.lastReportFailReason}（检查服务器地址与网络）"
        }
        if (!h.guardEnabled) blocking += "守护总开关已关闭，所有功能都不会生效"
        if (!h.foregroundRunning) blocking += "前台守护服务没在运行，请打开 App 让它自启一次"

        if (!h.notificationListenerEnabled) {
            degraded += "未开启通知使用权：大额支付监听完全不会触发（这项在国产 ROM 上较难开启）"
        }
        if (!h.keepAliveScheduled) degraded += "保活调度未挂载，重启手机后可能无法自动恢复"
        if (!h.batteryUnrestricted) degraded += "未豁免电池优化，后台随时可能被系统或厂商杀掉"
        if (h.heartbeatGapMinutes > 40) degraded += "保活心跳间隔超过 40 分钟，说明系统或厂商在杀后台"

        if (!h.callScreeningEnabled) {
            upcoming += "来电显示/呼叫筛选角色未授予：现有通话时长监测不会被系统调用。" +
                "该项目前已由 CallRiskWatcher 的通话状态监听覆盖，不影响告警"
        }
        if (!h.usageAccessGranted) {
            degraded += "未开启「使用情况访问」：通话中打开支付 App / 远程控制软件" +
                "（屏幕共享类诈骗）将不会告警。通话时长预警与陌生号码判定仍有效。" +
                "该项在国产 ROM 上较难开启，需手动设置"
        }

        return Issues(blocking, degraded, upcoming)
    }

    /** 把时间戳说成人话：自检页上「20 分钟前」比一个时间戳有用得多 */
    @JvmStatic
    fun relativeTime(ms: Long): String {
        if (ms <= 0L) return "从未"
        val diff = System.currentTimeMillis() - ms
        if (diff < 0) return "刚刚"
        val min = diff / 60000
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            min < 60 * 24 -> "${min / 60} 小时前"
            else -> "${min / (60 * 24)} 天前"
        }
    }

    /** 只有「暂不影响」类问题时不算异常：跟用户说"有 1 项"会让他去折腾一个不影响功能的东西 */
    @androidx.annotation.ColorInt
    fun summaryColor(issues: Issues): Int = when {
        issues.blocking.isNotEmpty() -> 0xFFEF4444.toInt()  // 红：功能已经失效
        issues.degraded.isNotEmpty() -> 0xFFFBBF24.toInt()  // 黄：能被削弱
        else -> 0xFF10B981.toInt()                          // 绿：正常
    }
}
