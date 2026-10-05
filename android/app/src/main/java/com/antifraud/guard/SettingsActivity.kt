package com.antifraud.guard

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.config.GuardConfig

/**
 * 防护规则设置页面
 *  - 调整陌生通话预警时长阈值
 *  - 调整大额支付预警金额阈值
 *  - 绑定守护人信息修改
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etCallThreshold    = findViewById<EditText>(R.id.et_call_threshold)
        val etPaymentThreshold = findViewById<EditText>(R.id.et_payment_threshold)
        val etFamilyName       = findViewById<EditText>(R.id.et_family_name)
        val etElderName        = findViewById<EditText>(R.id.et_elder_name)
        val btnSave            = findViewById<Button>(R.id.btn_save_settings)

        // 加载已有设置
        etCallThreshold.setText(GuardConfig.callThresholdMinutes.toString())
        etPaymentThreshold.setText(GuardConfig.paymentThreshold.toInt().toString())
        etFamilyName.setText(GuardConfig.boundFamilyName)
        etElderName.setText(GuardConfig.elderName)

        btnSave.setOnClickListener {
            val callMin = etCallThreshold.text.toString().toIntOrNull()
            val payAmt  = etPaymentThreshold.text.toString().toDoubleOrNull()

            if (callMin == null || callMin < 1) {
                Toast.makeText(this, "请输入有效的通话时长阈值（分钟）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (payAmt == null || payAmt < 1) {
                Toast.makeText(this, "请输入有效的支付预警金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            GuardConfig.callThresholdMinutes = callMin
            GuardConfig.paymentThreshold     = payAmt
            GuardConfig.boundFamilyName      = etFamilyName.text.toString().trim()
            GuardConfig.elderName            = etElderName.text.toString().trim()

            Toast.makeText(this, "✅ 设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
