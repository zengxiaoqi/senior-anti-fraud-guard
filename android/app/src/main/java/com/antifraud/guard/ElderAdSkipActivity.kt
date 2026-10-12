package com.antifraud.guard

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.AdSkipStats
import com.antifraud.guard.util.SystemPermissionState
import com.antifraud.guard.util.UiPrefs
import org.json.JSONObject

/**
 * 老人端「防护规则设置 › 拦截开屏广告」。
 *
 * ## 这个页面同时管两件互相独立的事
 *  1. **功能开关**（guard_settings.adSkipEnabled）：决定无障碍服务要不要处理事件；
 *  2. **系统授权**（无障碍服务）：决定系统愿不愿意把事件送给我们。
 * 两者缺一不可，所以页面必须同时展示两个状态 —— 只显示开关会让
 * "开关开着但没授权"表现得和"功能正常"一模一样，而后者其实什么都没发生。
 *
 * ## 开关写本机还是等服务端？
 * 本机先落（服务每读一个事件就查一次 GuardConfig，本机落了立刻生效），
 * 再上行云端。上行失败只提示、不回滚本机值：老人端语义里"本机已按新设置运行"，
 * 云端缺一份只是换机恢复时回退默认关，下次拉取会补上。
 */
class ElderAdSkipActivity : BaseActivity() {

    private lateinit var swEnabled: Switch
    private lateinit var cardPermission: android.view.View
    private lateinit var tvPermission: TextView
    private lateinit var btnGrant: Button
    private lateinit var tvStats: TextView
    private lateinit var tvLast: TextView

    /** 防止 setChecked 触发监听造成"进页面即保存" */
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_elder_ad_skip)

        findViewById<TextView>(R.id.tv_title).text = "拦截开屏广告"
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        swEnabled = findViewById(R.id.sw_enabled)
        cardPermission = findViewById(R.id.card_permission)
        tvPermission = findViewById(R.id.tv_permission)
        btnGrant = findViewById(R.id.btn_grant)
        tvStats = findViewById(R.id.tv_stats)
        tvLast = findViewById(R.id.tv_last)

        binding = true
        swEnabled.isChecked = GuardConfig.adSkipEnabled
        binding = false
        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            onToggle(checked)
        }

        btnGrant.setOnClickListener { grantAccessibility() }
        findViewById<Button>(R.id.btn_test).setOnClickListener {
            startActivity(android.content.Intent(this, AdSkipTestActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统无障碍设置返回后必须回检：引导不等于生效（见 SystemPermissionState 注释）
        binding = true
        swEnabled.isChecked = GuardConfig.adSkipEnabled
        binding = false
        render()
    }

    private fun onToggle(checked: Boolean) {
        GuardConfig.adSkipEnabled = checked
        render()
        ApiClient.pushElderSettings(
            settings = JSONObject().apply { put("adSkipEnabled", checked) },
            onSuccess = { merged ->
                // 服务端 merged 是最终权威（GuardSettingBounds 硬规则）
                merged?.let { GuardConfig.applySettingsFromServer(it) }
                render()
            },
            onError = { err ->
                Toast.makeText(this, "云端同步失败：$err。本机已生效，换机恢复时可能是默认关闭。", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun grantAccessibility() {
        // 跳转失败（ROM 阉割了所有 Intent）时必须给手工路径，否则用户卡在原地
        if (!SystemPermissionState.openAccessibilitySettings(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("请手动开启无障碍服务")
                .setMessage(SystemPermissionState.manualSteps(SystemPermissionState.Target.ACCESSIBILITY))
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun render() {
        val enabled = GuardConfig.adSkipEnabled
        val granted = SystemPermissionState.isAdSkipAccessibilityEnabled(this)

        // 权限卡：开关开着却没授权时才需要露脸，其余时候收起，不制造噪音
        if (enabled && !granted) {
            cardPermission.visibility = android.view.View.VISIBLE
            tvPermission.text = "无障碍服务还没开，拦截不会生效。请按下面的按钮去系统设置里开启。"
            tvPermission.setTextColor(0xFFF59E0B.toInt())
        } else {
            cardPermission.visibility = android.view.View.GONE
        }

        val count = AdSkipStats.todayCount(this)
        tvStats.text = if (count == 0) {
            if (enabled && granted) "今天还没有拦到广告（打开任意 App 试试）" else "尚未开始拦截"
        } else {
            "今天已经帮你挡掉 $count 次开屏广告"
        }
        tvStats.setTextColor(
            if (count > 0) 0xFF10B981.toInt() else UiPrefs.dimColor(this)
        )

        val last = AdSkipStats.lastSkip(this)
        tvLast.text = if (last != null) {
            val (pkg, text, ms) = last
            val time = android.text.format.DateFormat.format("HH:mm", ms)
            "最近一次：$time 拦截「$text」（${appLabel(pkg)}）"
        } else {
            ""
        }
        tvLast.visibility = if (last != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** 包名转应用名；拿不到就退回包名（系统卸载/不可见时） */
    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
