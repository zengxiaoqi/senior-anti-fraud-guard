package com.antifraud.guard

import android.os.Bundle
import android.widget.TextView
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig

/**
 * 老人端「防护规则设置 › 当前守护参数」（只读）。
 *
 * ## 为什么只读
 * 防护规则的事实源在服务端 guard_settings，写入方已收敛到子女端「守护设置」
 * （单一写入方）。以前老人端也提供编辑入口，但老人不会配置这些参数，
 * 改了只会产生误操作；单一写入方也彻底消除
 * 「子女改了、老人端页面显示旧值、实际已被覆盖」这类矛盾。
 *
 * ## 只读展示不是敷衍
 * 老人被子女问起时能直接念出数字，这在纠纷场景里是唯一有价值的证据。
 */
class ElderGuardParamsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_elder_guard_params)

        findViewById<TextView>(R.id.tv_title).text = "当前守护参数"
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // 云端配置可能在子女端被改过，进页面时刷新，
        // 否则老人看到的是上次进页面时的旧值
        ApiClient.fetchElderSettings(
            onSuccess = { s ->
                s?.let { GuardConfig.applySettingsFromServer(it) }
                render()
            },
            onError = { /* 拉取失败时保留本机值，不打扰用户 */ }
        )
    }

    private fun render() {
        val seg = GuardConfig.recordingMaxSegments
        val per = GuardConfig.recordingSegmentMinutes
        findViewById<TextView>(R.id.tv_call_threshold).text =
            "通话预警时长：${GuardConfig.callThresholdMinutes} 分钟"
        findViewById<TextView>(R.id.tv_payment_threshold).text =
            "大额支付预警：¥${GuardConfig.paymentThreshold.toInt()}"
        findViewById<TextView>(R.id.tv_rec_segments).text = "单次录音：最多 $seg 段"
        findViewById<TextView>(R.id.tv_rec_segment_minutes).text = "每段时长：$per 分钟"
        findViewById<TextView>(R.id.tv_rec_minutes_hint).apply {
            text = "当前合计约 ${seg * per} 分钟"
            // 单段越长，弱网下上传失败的概率越高 —— 超过 8 分钟给个黄字预警
            setTextColor(if (per >= 8) 0xFFF59E0B.toInt() else 0xFF10B981.toInt())
        }
    }
}
