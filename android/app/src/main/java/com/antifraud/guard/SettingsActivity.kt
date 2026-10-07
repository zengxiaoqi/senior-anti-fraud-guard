package com.antifraud.guard

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.pickPhone

/**
 * 防护规则设置页面
 *  - 调整陌生通话预警时长阈值
 *  - 调整大额支付预警金额阈值
 *  - 调整单次录音最长时长（段数）
 *  - 老人资料（姓名/手机号）修改并同步服务器
 *  - 只读展示已绑定守护人（事实源在服务端绑定关系，见 /api/auth/elder-bind-code）
 *
 * 所有防护规则在保存时上行到服务器（guard_settings）。以前只写本机 SharedPreferences，
 * 于是"老人换手机"会把它们静默打回默认值：老手机上设的"通话超 10 分钟就告警"
 * 在新手机上无声失效，界面上还一切正常，用户完全无从察觉。
 */
class SettingsActivity : AppCompatActivity() {

    /** 保存请求已在飞行中：防止用户连点造成重复提交与重复弹窗 */
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_settings)

        val etCallThreshold    = findViewById<EditText>(R.id.et_call_threshold)
        val etPaymentThreshold = findViewById<EditText>(R.id.et_payment_threshold)
        val etRecSegments      = findViewById<EditText>(R.id.et_rec_segments)
        val tvRecMinutesHint   = findViewById<TextView>(R.id.tv_rec_minutes_hint)
        val tvFamilyName       = findViewById<TextView>(R.id.tv_family_name)
        val etElderName        = findViewById<EditText>(R.id.et_elder_name)
        val etElderPhone       = findViewById<EditText>(R.id.et_elder_phone)
        val btnSave            = findViewById<Button>(R.id.btn_save_settings)

        // 加载已有设置
        etCallThreshold.setText(GuardConfig.callThresholdMinutes.toString())
        etPaymentThreshold.setText(GuardConfig.paymentThreshold.toInt().toString())
        etRecSegments.setText(GuardConfig.recordingMaxSegments.toString())
        etElderName.setText(if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName)
        etElderPhone.setText(GuardConfig.elderPhone)
        renderFamilyName(tvFamilyName)

        // 输入时实时换算分钟数：段数填错（如 9 段）当场就能看出超出范围，
        // 比保存后被静默 clamp 掉再让用户困惑要好
        etRecSegments.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val seg = s?.toString()?.trim()?.toIntOrNull() ?: 0
                tvRecMinutesHint.text = when {
                    seg == 0 -> "请填写 1~6 之间的段数"
                    seg in 1..6 -> "当前：最多 $seg 段，约 ${seg * 10} 分钟"
                    else -> "超出范围（1~6 段），保存时会按 ${
                        seg.coerceIn(1, 6)
                    } 段处理"
                }
                tvRecMinutesHint.setTextColor(
                    if (seg in 1..6) 0xFF10B981.toInt() else 0xFFFBBF24.toInt()
                )
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btnSave.setOnClickListener { view ->
            if (saving) {
                Toast.makeText(this, "正在保存，请稍候…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save(
                etCallThreshold, etPaymentThreshold, etRecSegments,
                etElderName, etElderPhone, btnSave
            )
        }
    }

    /** 守护人姓名只读展示：服务端绑定关系是唯一事实源 */
    private fun renderFamilyName(tv: TextView) {
        val name = GuardConfig.boundFamilyName
        val phone = GuardConfig.boundFamilyPhone
        tv.text = when {
            name.isNotEmpty() && phone.isNotEmpty() && !phone.startsWith("wx_") ->
                "$name（$phone）"
            name.isNotEmpty() -> "$name（该子女未登记手机号，无法一键拨通）"
            else -> "未绑定子女账号"
        }
    }

    private fun save(
        etCallThreshold: EditText,
        etPaymentThreshold: EditText,
        etRecSegments: EditText,
        etElderName: EditText,
        etElderPhone: EditText,
        btnSave: Button
    ) {
        val callMin = etCallThreshold.text.toString().trim().toIntOrNull()
        val payAmt  = etPaymentThreshold.text.toString().trim().toDoubleOrNull()
        val segRaw  = etRecSegments.text.toString().trim().toIntOrNull()

        if (callMin == null || callMin < 1) {
            Toast.makeText(this, "请输入有效的通话时长阈值（分钟）", Toast.LENGTH_SHORT).show()
            return
        }
        if (payAmt == null || payAmt < 1) {
            Toast.makeText(this, "请输入有效的支付预警金额", Toast.LENGTH_SHORT).show()
            return
        }
        if (segRaw == null || segRaw < 1) {
            Toast.makeText(this, "请输入有效的录音段数（1~6 段）", Toast.LENGTH_SHORT).show()
            return
        }
        // 超出范围不静默接受也不直接报错：明确告知会被收敛到哪个值，
        // 免得用户以为设了 9 段、实际生效 6 段还以为系统有 bug
        val segments = segRaw.coerceIn(1, 6)
        if (segments != segRaw) {
            Toast.makeText(
                this,
                "录音段数 $segRaw 超出范围（1~6），将按 $segments 段保存",
                Toast.LENGTH_LONG
            ).show()
        }

        // 本地阈值先存：即便服务器同步失败，这些设置也必须在本机生效
        GuardConfig.callThresholdMinutes = callMin
        GuardConfig.paymentThreshold     = payAmt
        GuardConfig.recordingMaxSegments = segments

        val newName = etElderName.text.toString().trim()
        val newPhone = normalizePhoneInput(etElderPhone.text.toString())
        val curName = if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName

        // 手机号有填才校验，空着表示不改号码（沿用原号码）
        if (newPhone.isNotEmpty() && !Regex("^1[3-9]\\d{9}$").matches(newPhone)) {
            Toast.makeText(this, "老人手机号格式不正确（11 位）", Toast.LENGTH_SHORT).show()
            return
        }
        val finalPhone = if (newPhone.isNotEmpty()) newPhone else GuardConfig.elderPhone

        // 姓名和手机号任意一个变了都要同步服务器。
        // 原来只看 phoneChanged，导致"只改姓名"时服务器上的资料永远不更新，
        // 子女端看到的还是旧名字。
        val profileChanged = newName != curName || (newPhone.isNotEmpty() && newPhone != GuardConfig.elderPhone)

        if (!GuardConfig.elderActivated) {
            // 未激活：只存本地，下次登记时生效
            GuardConfig.elderName = newName
            GuardConfig.elderPhone = finalPhone
            Toast.makeText(this, "✅ 设置已保存（账号登记后将同步到服务器）", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        saving = true
        btnSave.isEnabled = false

        // 1) 规则上行。老人资料没变时这一步就够了，不必再打扰用户
        if (!profileChanged) {
            pushSettingsThenFinish(btnSave)
            return
        }

        submitElderProfile(newName.ifEmpty { GuardConfig.elderName.ifEmpty { "老人账号" } }, finalPhone, btnSave)
    }

    /**
     * 把本机防护规则上行到服务器，成功后退出。
     *
     * 失败也允许退出并提示：设置在本机已经生效，硬拦住不让走反而会让人以为没保存成功。
     * 但必须明确说"未同步"，因为下次换手机时这份配置不会跟过去 —— 静默失败等于埋雷。
     */
    private fun pushSettingsThenFinish(btnSave: Button) {
        ApiClient.pushElderSettings(
            onSuccess = { merged ->
                // 用服务端合并后的最终值回写，多端并发改同一字段时以服务器为准
                merged?.let { GuardConfig.applySettingsFromServer(it) }
                finishWith("✅ 设置已保存并同步到云端（换手机也不会丢）")
            },
            onError = { err ->
                btnSave.isEnabled = true
                saving = false
                AlertDialog.Builder(this)
                    .setTitle("设置已生效，但未同步到云端")
                    .setMessage("原因：$err\n\n本机已按新设置运行，但这次改动没有上传。换手机后可能会恢复成旧设置。\n\n是否现在重试？")
                    .setPositiveButton("重试") { _, _ ->
                        saving = false
                        btnSave.isEnabled = true
                        pushSettingsThenFinish(btnSave)
                    }
                    .setNegativeButton("先退出") { _, _ -> finishWith("设置已在本机生效（未同步云端）") }
                    .show()
            }
        )
    }

    private fun finishWith(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    /**
     * 同步老人资料到服务器，并顺带把防护规则一起上行。
     *
     * elder-register 会按 elderId 是否有效区分"就地改资料"和"新建/找回"，这里不用自己判断；
     * 它的响应里已带 boundFamily 与 guardSettings，正好一并落地。
     */
    private fun submitElderProfile(name: String, phone: String, btnSave: Button) {
        Toast.makeText(this, "正在保存并同步服务器…", Toast.LENGTH_SHORT).show()
        ApiClient.elderRegister(name, phone, elderId = GuardConfig.elderId,
            onSuccess = { res ->
                GuardConfig.elderName = res.optString("name", name)
                GuardConfig.elderPhone = res.optString("phone", phone)
                GuardConfig.elderId = res.optInt("elderId", GuardConfig.elderId)
                val code = res.optString("bindCode", "")
                if (code.isNotEmpty()) GuardConfig.bindCode = code
                GuardConfig.elderActivated = true
                // 顺手同步守护人信息：换手机后第一次进设置页就能看到子女
                res.optJSONObject("boundFamily")?.let { fam ->
                    val n = fam.optString("name", "")
                    if (n.isNotEmpty()) GuardConfig.boundFamilyName = n
                    GuardConfig.boundFamilyPhone =
                        fam.pickPhone(username = GuardConfig.familyUsername)
                }
                // 资料提交成功不代表规则也上传了，单独再推一次
                ApiClient.pushElderSettings(
                    onSuccess = { merged ->
                        merged?.let { GuardConfig.applySettingsFromServer(it) }
                        finishWith("✅ 设置已保存并同步到云端")
                    },
                    onError = { finishWith("✅ 资料已同步（防护规则未同步到云端）") }
                )
            },
            onError = { err ->
                btnSave.isEnabled = true
                saving = false
                Toast.makeText(this, "资料同步失败：$err（其余设置已在本机生效）", Toast.LENGTH_LONG).show()
            })
    }

    /**
     * 全角数字→半角，并丢弃空格/连字符。
     * 中文输入法默认打全角，服务端 /^1[3-9]\d{9}$/ 必判失败 —— 这个坑踩过不止一次。
     */
    private fun normalizePhoneInput(s: String): String {
        val sb = StringBuilder()
        for (ch in s.trim()) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch in '0'..'9' -> sb.append(ch)
                ch == '-' || ch == '－' || ch == ' ' -> {}
            }
        }
        return sb.toString()
    }
}