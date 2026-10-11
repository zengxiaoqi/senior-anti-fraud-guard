package com.antifraud.guard.family

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.antifraud.guard.R
import com.antifraud.guard.util.GuardSettingBounds
import com.antifraud.guard.util.PresetChip
import org.json.JSONObject

/**
 * 子女端「守护设置 › 通话与支付」。
 *
 * 只提交 `callThresholdMinutes / paymentThreshold` 两个字段，其余靠服务端增量合并保持不变。
 *
 * ## 为什么这两项放在同一页而位置那三项在另一页
 * 它们回答的是同一个问题："老人正在被诱导转账的迹象，多快通知我"。
 * 诈骗话术里"长时间通话"和"大额支出"是连着发生的两个动作，
 * 拆成两页会让子女调完一个忘了另一个；
 * 而位置阈值回答的是完全不同的问题（"老人去哪了 / 是不是回不了家"），性质不相关。
 */
class CallPaymentRuleActivity : GuardRuleSubActivity() {

    private lateinit var etCallThreshold: EditText
    private lateinit var etPaymentThreshold: EditText

    /** 通话/支付档位：通话分钟 / 支付金额 */
    private val presets = mapOf(
        R.id.chip_call_relaxed to Pair(30, 2000.0),
        R.id.chip_call_standard to Pair(GuardSettingBounds.CALL_MINUTES_DEFAULT, 500.0),
        R.id.chip_call_strict to Pair(5, 100.0)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_payment_rule)

        findViewById<TextView>(R.id.tv_title).text = "通话与支付"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        etCallThreshold = findViewById(R.id.et_call_threshold)
        etPaymentThreshold = findViewById(R.id.et_payment_threshold)

        setupChips()
        etCallThreshold.addTextChangedListener(dirtyWatcher { refreshPresetHighlights() })
        etPaymentThreshold.addTextChangedListener(dirtyWatcher { refreshPresetHighlights() })
        findViewById<Button>(R.id.btn_save).setOnClickListener { onSaveRequested() }

        reload()
    }

    override fun onSettings(s: JSONObject?) {
        filling = true
        etCallThreshold.setText(s.optPositiveInt("callThresholdMinutes"))
        etPaymentThreshold.setText(s.optPositiveDouble("paymentThreshold"))
        filling = false
        refreshPresetHighlights()
    }

    // ──────────────────────────────────────────
    //  档位
    // ──────────────────────────────────────────

    private fun setupChips() {
        presets.forEach { (id, p) ->
            findViewById<TextView>(id).setOnClickListener {
                filling = true
                etCallThreshold.setText(p.first.toString())
                etPaymentThreshold.setText(trimMoney(p.second))
                filling = false
                dirty = true
                refreshPresetHighlights()
            }
        }
    }

    /** 2000.0 要显示成 "2000" 而不是 "2000.0" —— 金额框里出现小数点会让人怀疑填错了 */
    private fun trimMoney(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun refreshPresetHighlights() {
        val call = etCallThreshold.text.toString().trim().toIntOrNull()
        val pay = etPaymentThreshold.text.toString().trim().toDoubleOrNull()
        presets.forEach { (id, p) ->
            PresetChip.paint(findViewById(id), call == p.first && pay == p.second)
        }
    }

    // ──────────────────────────────────────────
    //  保存
    // ──────────────────────────────────────────

    override fun onSaveRequested() {
        val callMin = etCallThreshold.text.toString().trim().toIntOrNull()
        val payAmt = etPaymentThreshold.text.toString().trim().toDoubleOrNull()

        if (callMin == null || payAmt == null) {
            toast("有输入框为空或格式不正确，请检查")
            return
        }
        if (payAmt < 1) {
            toast("支付预警金额须大于 0")
            return
        }

        // 通话时长有上下界会被收敛，支付金额服务端 clamp 到 1~1000000，
        // 两者都可能"存进去的和填的不一样"，必须说出口
        val clamped = listOf(
            "通话时长" to (callMin to GuardSettingBounds.callMinutes(callMin))
        ).filter { it.second.first != it.second.second }

        val note = if (clamped.isEmpty()) "" else {
            "已按范围收敛：" + clamped.joinToString("、") {
                "${it.first} ${it.second.first} → ${it.second.second}"
            }
        }

        save(
            JSONObject().apply {
                put("callThresholdMinutes", GuardSettingBounds.callMinutes(callMin))
                put("paymentThreshold", payAmt)
            },
            note = note
        )
    }
}
