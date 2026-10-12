package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.GuardKeepAliveScheduler
import com.antifraud.guard.util.AppUpdateManager
import com.antifraud.guard.util.GuardHealthAdvice
import com.antifraud.guard.util.SettingsEntry
import com.antifraud.guard.util.UiPrefs

/**
 * 老人端「防护规则设置」入口页。
 *
 * ## 为什么改成入口页
 * 原先这一页同时塞了：只读守护参数（3 张卡）+ 资料填写 + 守护健康自检
 * （6 个修复按钮 + 两屏状态文本）+ 版本与升级，滚到底要划五屏。
 * 真正的代价不是"长"：
 *   1. 老人填完姓名，要滚过一整屏自检面板才能找到「保存设置」——
 *      按钮在屏幕外时，最常见的结局是他以为填好了（其实根本没提交）；
 *   2.「版本与升级」在 AppSettingsActivity 里另有一份完整实现，
 *      两处都能升级，且两处的升级说明文案已经开始不一致（一处写 23MB、一处写 37MB）。
 *
 * 现在：本页只回答"有哪些项、每项现在什么状态"，改哪一项就进哪一项。
 * 版本升级改为跳转 AppSettingsActivity，不再复制第二份实现。
 *
 * ## 摘要必须是服务端值 + 实时检测结果
 * 若拿本机缓存显示，从子页面返回后入口页还显示旧值，
 * 用户会以为保存没生效而再改一遍。所以 [onResume] 无条件刷新。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var entryProfile: View
    private lateinit var entryGuardParams: View
    private lateinit var entryHealth: View
    private lateinit var entryAdSkip: View
    private lateinit var entryVersion: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.tv_title).text = "防护规则设置"
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        entryProfile = findViewById(R.id.entry_profile)
        entryGuardParams = findViewById(R.id.entry_guard_params)
        entryHealth = findViewById(R.id.entry_health)
        entryAdSkip = findViewById(R.id.entry_ad_skip)
        entryVersion = findViewById(R.id.entry_version)

        SettingsEntry.bind(entryProfile, "👤", "我的资料") {
            startActivity(Intent(this, ElderProfileActivity::class.java))
        }
        SettingsEntry.bind(entryGuardParams, "🛡️", "当前守护参数") {
            startActivity(Intent(this, ElderGuardParamsActivity::class.java))
        }
        SettingsEntry.bind(entryHealth, "🩺", "守护健康自检") {
            startActivity(Intent(this, GuardHealthActivity::class.java))
        }
        SettingsEntry.bind(entryAdSkip, "🚫", "拦截开屏广告") {
            startActivity(Intent(this, AdSkipSettingActivity::class.java))
        }
        // 版本与升级的实现在 AppSettingsActivity，不在本页复制第二份：
        // 两份实现意味着两处都能升级，也意味着两份文案迟早不一致
        SettingsEntry.bind(entryVersion, "⬆️", "版本与升级") {
            startActivity(Intent(this, AppSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 云端配置可能在子女端被改过，进页面时刷新只读展示，
        // 否则老人看到的是上次进页面时的旧值
        ApiClient.fetchElderSettings(
            onSuccess = { s ->
                s?.let { GuardConfig.applySettingsFromServer(it) }
                render()
            },
            onError = { /* 拉取失败时保留本机值，不打扰用户 */ }
        )
        render()
    }

    private fun render() {
        renderProfileSummary()
        renderParamsSummary()
        renderHealthSummary()
        renderAdSkipSummary()
        renderVersionSummary()
    }

    private fun renderProfileSummary() {
        val name = if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName
        val phone = GuardConfig.elderPhone
        SettingsEntry.summary(
            entryProfile,
            when {
                name.isNotEmpty() && phone.isNotEmpty() -> "$name · $phone"
                name.isNotEmpty() -> "$name · 未填手机号"
                phone.isNotEmpty() -> "未填姓名 · $phone"
                else -> "尚未填写资料"
            },
            if (name.isEmpty() && phone.isEmpty()) 0xFFF59E0B.toInt() else UiPrefs.dimColor(this)
        )
    }

    private fun renderParamsSummary() {
        val call = GuardConfig.callThresholdMinutes
        val pay = GuardConfig.paymentThreshold.toInt()
        val total = GuardConfig.recordingMaxSegments * GuardConfig.recordingSegmentMinutes
        SettingsEntry.summary(
            entryGuardParams,
            "通话超过 $call 分钟 · 单笔超 ${pay} 元 · 录音约 $total 分钟"
        )
    }

    /**
     * 健康自检的摘要直接用检测结果的项数，而不是"点击查看"这类空话。
     *
     * 这是入口页唯一能一次性看到全局的地方：老人不会主动去自检页，
     * 但如果这里写着"🚨 2 项需要处理"，他至少有概率点进来。
     * 分级规则与自检页共用 [GuardHealthAdvice]，不会出现两处数目对不上。
     */
    private fun renderHealthSummary() {
        val issues = GuardHealthAdvice.classify(GuardKeepAliveScheduler.healthReport(this))
        SettingsEntry.summary(
            entryHealth,
            when {
                issues.isEmpty -> "✅ 未发现异常"
                issues.blocking.isNotEmpty() -> "🚨 ${issues.blocking.size} 项导致守护失效，${issues.degraded.size} 项降级"
                else -> "⚠️ ${issues.degraded.size} 项功能降级"
            },
            GuardHealthAdvice.summaryColor(issues)
        )
    }

    /**
     * 开屏广告拦截的摘要必须把两个独立状态一起说清楚：
     * 开关（服务端同步值）与无障碍授权（本机系统状态）。
     * 只报开关会把"开着但没授权"显示成功能正常，而它其实什么都没发生。
     */
    private fun renderAdSkipSummary() {
        val enabled = GuardConfig.adSkipEnabled
        val granted = com.antifraud.guard.util.SystemPermissionState
            .isAdSkipAccessibilityEnabled(this)
        val count = com.antifraud.guard.util.AdSkipStats.todayCount(this)
        SettingsEntry.summary(
            entryAdSkip,
            when {
                !enabled -> "未开启"
                !granted -> "⚠️ 已开启，但未授权无障碍服务"
                count > 0 -> "已开启 · 今日已拦 $count 次"
                else -> "已开启"
            },
            if (enabled && !granted) 0xFFF59E0B.toInt() else UiPrefs.dimColor(this)
        )
    }

    private fun renderVersionSummary() {
        SettingsEntry.summary(
            entryVersion,
            "v${AppUpdateManager.currentVersionName(this)}（版本号 ${AppUpdateManager.currentVersionCode(this)}）"
        )
    }
}
