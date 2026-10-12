package com.antifraud.guard.family

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.antifraud.guard.R
import org.json.JSONObject

/**
 * 子女端「守护设置 › 拦截开屏广告」。
 *
 * 只有一个开关，但有三件事必须说出口：
 *  1. 开关通过 guard_settings 实时下发到老人手机（WS 推送，最长一个拉取周期兜底）；
 *  2. **光有开关不够**：老人手机上还要为本应用开启系统「无障碍」权限，
 *     而这只能在老人手机上操作 —— 子女端看不到那台机器的授权状态，
 *     所以这里必须提醒子女让老人去开（老人端设置页有引导）；
 *  3. 拦截计数存在老人手机本地，不同步到子女端，这里看不到"拦了多少次"。
 *
 * ## 为什么开关对子女端开放
 * 与 trustedCallNumbersJson / highRiskPackages 不同，adSkipEnabled 的失败方向
 * 是"多点几次跳过"而不是"少报一次危险"：最坏情况是老人错过了本会被挡住的广告
 * —— 而广告本来就会出现，这不是新增风险面。
 */
class AdSkipRuleActivity : GuardRuleSubActivity() {

    private lateinit var swEnabled: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_skip_rule)

        findViewById<TextView>(R.id.tv_title).text = "拦截开屏广告"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        swEnabled = findViewById(R.id.sw_enabled)
        swEnabled.setOnCheckedChangeListener { _, _ -> if (!filling) dirty = true }
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSaveRequested() }

        reload()
    }

    override fun onSettings(s: JSONObject?) {
        filling = true
        swEnabled.isChecked = s?.optBoolean("adSkipEnabled", false) ?: false
        filling = false
    }

    override fun onSaveRequested() {
        save(JSONObject().apply { put("adSkipEnabled", swEnabled.isChecked) })
    }
}
