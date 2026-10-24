package com.antifraud.guard

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.antifraud.guard.service.GuardKeepAliveScheduler
import com.antifraud.guard.util.GuardHealthAdvice
import com.antifraud.guard.util.SystemPermissionState
import com.antifraud.guard.util.UiPrefs

/**
 * 老人端「防护规则设置 › 守护健康自检」。
 *
 * ## 为什么单独成页
 * 这块面板自带 6 个修复按钮 + 两屏状态文本，塞在原设置页里会把「保存设置」
 * 顶到屏幕外。更关键的是它的**使用方式**和其他设置完全不同：
 * 资料页是"填完保存"，这一页是"跳转系统设置 → 回来再看结果"。
 * 混在一页里，用户会以为点完六个按钮还要去找保存。
 *
 * ## 跳转后必须回检
 * 这是整个面板的关键（见 [onResume]）：只做"跳转引导"而不回检，
 * 等于让用户跳出去随便点两下再回来，App 就认为"引导完成了" ——
 * 但开关可能根本没开，守护继续静默失效。
 */
class GuardHealthActivity : BaseActivity() {

    private lateinit var tvHealthReport: TextView
    private lateinit var tvHealthAdvice: TextView

    /** ③ 来电显示按钮的渐进式兜底计数：第 1 次试系统角色框，第 2 次跳默认应用页，之后弹手工步骤 */
    private var callScreeningFixTries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guard_health)

        findViewById<TextView>(R.id.tv_title).text = "守护健康自检"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        tvHealthReport = findViewById(R.id.tv_health_report)
        tvHealthAdvice = findViewById(R.id.tv_health_advice)

        setupFixButtons()
        refreshHealth()
    }

    /** 每个按钮对应一个"静默失效"的开关，跳转后靠 [onResume] 自动回检 */
    override fun onResume() {
        super.onResume()
        refreshHealth()
    }

    private fun setupFixButtons() {
        // 修复按钮完成态：权限已授予时按钮变绿"✅ 已修复"并禁用，
        // 老人能直接看出"修好了没有"，也不用再去点一个已经没必要点的按钮
        fixButtonLabels.forEach { (id, label) ->
            findViewById<Button>(id).tag = label
        }

        findViewById<Button>(R.id.btn_refresh_health).setOnClickListener {
            refreshHealth(announce = true)
        }

        findViewById<Button>(R.id.btn_fix_battery).setOnClickListener {
            // 优先走厂商自己的省电策略页（进了系统白名单也常被二次管控）
            val vendorIntent = VendorPermissionHelper.batteryOptimizationIntent(this)
            try {
                if (vendorIntent != null && vendorIntent.resolveActivity(packageManager) != null) {
                    startActivity(vendorIntent)
                } else {
                    SystemPermissionState.openBatteryOptimizationSettings(this)
                }
            } catch (e: Exception) {
                SystemPermissionState.openBatteryOptimizationSettings(this)
            }
        }

        findViewById<Button>(R.id.btn_fix_background_location).setOnClickListener {
            SystemPermissionState.openAppLocationSettings(this)
            toast("请点「权限」→「位置信息」，选择「始终允许」")
        }

        findViewById<Button>(R.id.btn_fix_call_screening).setOnClickListener {
            // 渐进式兜底：同一按钮连点逐级降级，而不是永远卡在第一级。
            // 华为/鸿蒙经常"实现了角色却不弹确认框"——第一级点了没反应时，
            // 老人再点一次必须看到新东西，否则就是"按钮是坏的"（HarmonyOS 3.0 实机反馈）。
            callScreeningFixTries++
            when {
                callScreeningFixTries == 1 && SystemPermissionState.requestCallScreeningRole(this) ->
                    toast("已尝试弹出系统确认框，请点「允许」。" +
                        "若什么都没弹出来，请再点一次本按钮改走手动设置")
                callScreeningFixTries <= 2 && SystemPermissionState.openDefaultAppsSettings(this) ->
                    toast("已打开默认应用设置：请找「来电显示/呼叫筛选」改为本应用。" +
                        "若列表里没有本应用，说明本机不支持，可直接跳过本项（不影响现有功能）")
                else -> showManualSteps(SystemPermissionState.Target.CALL_SCREENING)
            }
        }

        findViewById<Button>(R.id.btn_fix_notification_access).setOnClickListener {
            // 厂商优先：MIUI 上跳 AOSP 那个页面会被它自己拦下并直接拒绝
            if (!SystemPermissionState.openNotificationListenerSettings(this)) {
                showManualSteps(SystemPermissionState.Target.NOTIFICATION_ACCESS)
            }
        }

        findViewById<Button>(R.id.btn_fix_vendor).setOnClickListener {
            if (VendorPermissionHelper.openPermissionGuide(this)) {
                // 成功跳到厂商权限页：把落地页里的操作讲清楚（开关名按品牌说，
                // 否则用户拿着 MIUI 的「后台弹出界面」在华为上找不到对应开关）
                val brand = VendorPermissionHelper.brandName()
                toast(
                    if (brand == "华为/荣耀")
                        "已跳转「应用启动管理」：找到「长者防诈守护」，关闭「自动管理」，" +
                            "再开启 自启动 / 关联启动 / 后台活动"
                    else
                        "请在打开的页面中找到本应用，开启 自启动、后台弹出界面、锁屏显示"
                )
            } else {
                // 厂商组件全部解析失败，只落到应用详情页 —— 必须把完整路径写清楚，
                // 让用户能自己走到「应用启动管理」
                showManualSteps(SystemPermissionState.Target.VENDOR_KEEPALIVE)
            }
        }

        findViewById<Button>(R.id.btn_fix_usage_access).setOnClickListener {
            if (!SystemPermissionState.openUsageAccessSettings(this)) {
                showManualSteps(SystemPermissionState.Target.USAGE_ACCESS)
            }
        }
    }

    /**
     * 跳不过去时展示手工步骤。
     *
     * 这一档是刻意设计的：深度定制 ROM 上常常所有 Intent 都解析不到，
     * 而用户看到的是一个"点了没反应"的按钮 —— 那比不给入口更糟。
     * 至少把路径写清楚，让他能自己去设置里找。
     */
    private fun showManualSteps(target: SystemPermissionState.Target) {
        AlertDialog.Builder(this)
            .setTitle("请手动前往系统设置开启")
            .setMessage(SystemPermissionState.manualSteps(target))
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 五个可判定的修复按钮的原始文案（进入完成态后要能还原回来；厂商项无单一判定，保持常驻） */
    private val fixButtonLabels = mapOf(
        R.id.btn_fix_battery to "① 关闭系统电池优化（防后台被杀）",
        R.id.btn_fix_background_location to "② 位置权限改为「始终允许」",
        R.id.btn_fix_call_screening to "③ 设为来电显示/骚扰拦截应用",
        R.id.btn_fix_notification_access to "④ 开启通知使用权（用于监听扣款）",
        R.id.btn_fix_usage_access to "⑥ 使用情况访问（通话中打开支付App联动）"
    )

    /** 按 healthReport 结果刷新修复按钮完成态（厂商项无单一判定，保持常驻） */
    private fun renderFixButtonStates(h: GuardKeepAliveScheduler.HealthReport) {
        val states = mapOf(
            R.id.btn_fix_battery to h.batteryUnrestricted,
            R.id.btn_fix_background_location to h.hasBackgroundLocation,
            R.id.btn_fix_call_screening to h.callScreeningEnabled,
            R.id.btn_fix_notification_access to h.notificationListenerEnabled,
            R.id.btn_fix_usage_access to h.usageAccessGranted
        )
        states.forEach { (id, done) ->
            val btn = findViewById<Button>(id)
            val label = fixButtonLabels[id] ?: btn.tag?.toString() ?: ""
            if (done) {
                btn.text = "✅ 已修复：" +
                    label.removePrefix("① ").removePrefix("② ")
                        .removePrefix("③ ").removePrefix("④ ").removePrefix("⑥ ")
                btn.setTextColor(ContextCompat.getColor(this, R.color.c_accent_green))
                btn.isEnabled = false
                btn.alpha = 0.75f
            } else {
                btn.text = label
                btn.setTextColor(UiPrefs.textColor(this))
                btn.isEnabled = true
                btn.alpha = 1f
            }
        }
    }

    /**
     * 重绘健康自检面板。
     *
     * @param announce 点击「重新检测」按钮时为 true：检测本身是同步的（毫秒级），
     *   如果状态没变化，屏幕上什么都不会变 —— 用户会以为按钮是坏的。
     *   必须用 toast 明确告知"检测真的发生了，结论是什么"。
     *   onResume 的自动回检不打扰（announce=false），否则每次回页面都弹 toast。
     */
    private fun refreshHealth(announce: Boolean = false) {
        val h = GuardKeepAliveScheduler.healthReport(this)
        renderFixButtonStates(h)

        val gapText = when {
            h.heartbeatGapMinutes < 0 -> "尚未触发过保活心跳"
            h.heartbeatGapMinutes > 40 -> "${h.heartbeatGapMinutes} 分钟（⚠️ 远超预期 10~20 分钟）"
            else -> "${h.heartbeatGapMinutes} 分钟"
        }

        tvHealthReport.text = buildString {
            appendLine("守护总开关：${if (h.guardEnabled) "✅ 已开启" else "❌ 已关闭"}")
            appendLine("前台守护服务：${if (h.foregroundRunning) "✅ 运行中" else "❌ 未运行"}")
            appendLine("位置守护服务：${if (h.locationRunning) "✅ 运行中" else "❌ 未运行"}")
            appendLine("保活调度：${if (h.keepAliveScheduled) "✅ 已挂载" else "❌ 未挂载"}")
            appendLine("保活心跳间隔：$gapText（累计 ${h.heartbeatCount} 次）")
            appendLine("电池优化豁免：${if (h.batteryUnrestricted) "✅ 已豁免" else "❌ 未豁免"}")
            appendLine("位置守护：${if (h.hasBackgroundLocation) "✅ 前后台定位均已授权" else "🚨 仅前台授权，退出应用即失效"}")
            // 定位链路的两个关键事实：**取到过坐标** 与 **传到了服务端**。
            // 只报权限位是不够的——权限全绿而一次坐标都没取到，界面和自检页会一起骗人。
            appendLine(
                "最近取到坐标：" + GuardHealthAdvice.relativeTime(h.lastFixOkAt) +
                    if (h.lastFixOkAt > 0L && h.lastFixOkSource.isNotEmpty()) "（${h.lastFixOkSource}）" else ""
            )
            appendLine("最近上报成功：" + GuardHealthAdvice.relativeTime(h.lastReportOkAt))
            if (h.lastFixFailReason.isNotEmpty() && h.lastFixOkAt <= 0L) {
                appendLine("取坐标失败原因：${h.lastFixFailReason}")
            }
            if (h.lastReportFailReason.isNotEmpty()) {
                appendLine("上报失败原因：${h.lastReportFailReason}")
            }
            // 「来电显示角色」确实还没有已实现功能依赖它 —— Phase 1 的
            // CallRiskWatcher 用 PhoneStateListener 独立完成了通话监测，不经过系统角色。
            // 但「使用情况访问」不同：没有它 COERCION_RISK 完全不发生（见下方 degraded 桶）。
            // 两项口径不同，所以一个标 ℹ️ 一个标 ⚠️。
            // 口径必须与首页横幅一致（见 MainActivity.renderGuardAlertBanner），
            // 否则用户数出 3 个问题、横幅却只有 1 项，会以为有一处统计错了。
            appendLine("来电显示/呼叫筛选角色：${if (h.callScreeningEnabled) "✅ 已授予" else "ℹ️ 未授予（不影响现有功能）"}")
            appendLine("通知使用权：${if (h.notificationListenerEnabled) "✅ 已开启" else "❌ 未开启"}")
            appendLine("使用情况访问：${if (h.usageAccessGranted) "✅ 已开启" else "⚠️ 未开启（通话联动告警已停用）"}")
            appendLine("精确闹钟权限：${if (h.canScheduleExactAlarms) "✅ 可用" else "⚠️ 不可用（保活已改用非精确闹钟，不影响）"}")
            append("手机品牌：${h.vendorBrand.ifEmpty { "原厂/其他" }}")
        }

        // 分级判定在 GuardHealthAdvice，入口页摘要与这里共用同一份规则：
        // 两处各写一份必然漂成"入口页说 3 项、点进去只有 2 项"，
        // 那会让用户怀疑整个自检是假的，然后一并忽略。
        val issues = GuardHealthAdvice.classify(h)

        tvHealthAdvice.text = buildString {
            if (issues.blocking.isNotEmpty()) {
                appendLine("🚨 ${issues.blocking.size} 项导致守护失效：")
                issues.blocking.forEach { appendLine("　• $it") }
                if (issues.degraded.isNotEmpty()) appendLine()
            }
            if (issues.degraded.isNotEmpty()) {
                appendLine("⚠️ ${issues.degraded.size} 项功能降级：")
                issues.degraded.forEach { appendLine("　• $it") }
                if (issues.upcoming.isNotEmpty()) appendLine()
            }
            if (issues.upcoming.isNotEmpty()) {
                appendLine("ℹ️ ${issues.upcoming.size} 项暂不影响现有功能：")
                issues.upcoming.forEach { appendLine("　• $it") }
            }
            if (issues.isEmpty) {
                append("✅ 未发现异常，无需处理")
            }
        }
        tvHealthAdvice.setTextColor(GuardHealthAdvice.summaryColor(issues))

        if (announce) {
            val needFix = issues.needFix
            toast(
                if (needFix == 0) "✅ 已重新检测：没有需要处理的问题"
                else "✅ 已重新检测：$needFix 项需要处理，详见下方说明"
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
