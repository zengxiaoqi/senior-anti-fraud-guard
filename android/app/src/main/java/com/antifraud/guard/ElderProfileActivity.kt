package com.antifraud.guard

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.pickPhone

/**
 * 老人端「防护规则设置 › 我的资料」。
 *
 * ## 为什么单独成页
 * 原设置页把资料填写和 6 个权限修复按钮、两屏自检文本放在同一个滚动容器里，
 * 老人填完姓名要滚过一整屏才能找到「保存设置」。按钮在屏幕外时，
 * 最常见的结局是他以为填好了 —— 其实根本没提交，而界面上没有任何东西提示这件事。
 *
 * ## 顺带同步防护规则
 * 保存资料时会把本机防护规则一并上行（[pushSettingsThenFinish]）。
 * 这条链路刻意保留：老人换手机时靠它把本机规则值同步回云端恢复，
 * 彻底删掉会让"规则跟着账号走"在换机后断掉。上行是增量合并，
 * 不会覆盖子女端的显式改动。
 */
class ElderProfileActivity : BaseActivity() {

    /** 保存请求已在飞行中：防止连点造成重复提交与重复弹窗 */
    private var saving = false

    private lateinit var etElderName: EditText
    private lateinit var etElderPhone: EditText
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_elder_profile)

        findViewById<TextView>(R.id.tv_title).text = "我的资料"
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        etElderName = findViewById(R.id.et_elder_name)
        etElderPhone = findViewById(R.id.et_elder_phone)
        btnSave = findViewById(R.id.btn_save_profile)

        etElderName.setText(if (GuardConfig.elderName == "默认账号") "" else GuardConfig.elderName)
        etElderPhone.setText(GuardConfig.elderPhone)
        renderFamilyName(findViewById(R.id.tv_family_name))

        btnSave.setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        // 云端可能在别处被改过（子女端改资料不会改这里，但绑定关系会变），
        // 回到本页时刷新一次只读的守护人显示
        renderFamilyName(findViewById(R.id.tv_family_name))
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

    // ──────────────────────────────────────────
    //  保存
    // ──────────────────────────────────────────

    private fun save() {
        if (saving) {
            Toast.makeText(this, "正在保存，请稍候…", Toast.LENGTH_SHORT).show()
            return
        }
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
        val profileChanged =
            newName != curName || (newPhone.isNotEmpty() && newPhone != GuardConfig.elderPhone)

        if (!GuardConfig.elderActivated) {
            // 未激活：只存本地，下次登记时生效
            GuardConfig.elderName = newName
            GuardConfig.elderPhone = finalPhone
            finishWith("✅ 已保存（账号登记后将同步到服务器）")
            return
        }

        saving = true
        btnSave.isEnabled = false

        // 资料没变时只推规则就够了，不必再打扰用户
        if (!profileChanged) {
            pushSettingsThenFinish()
            return
        }
        submitElderProfile(
            newName.ifEmpty { GuardConfig.elderName.ifEmpty { "老人账号" } },
            finalPhone
        )
    }

    /**
     * 把本机防护规则上行到服务器，成功后退出。
     *
     * 失败也允许退出并提示：资料在本机已经生效，硬拦住不让走反而会让人以为没保存成功。
     * 但必须明确说"未同步"，因为下次换手机时这份配置不会跟过去 —— 静默失败等于埋雷。
     */
    private fun pushSettingsThenFinish() {
        ApiClient.pushElderSettings(
            onSuccess = { merged ->
                // 用服务端合并后的最终值回写，多端并发改同一字段时以服务器为准
                merged?.let { GuardConfig.applySettingsFromServer(it) }
                finishWith("✅ 已保存并同步到云端（换手机也不会丢）")
            },
            onError = { err ->
                btnSave.isEnabled = true
                saving = false
                AlertDialog.Builder(this)
                    .setTitle("已生效，但未同步到云端")
                    .setMessage("原因：$err\n\n本机已按新设置运行，但这次改动没有上传。换手机后可能会恢复成旧设置。\n\n是否现在重试？")
                    .setPositiveButton("重试") { _, _ ->
                        saving = false
                        btnSave.isEnabled = true
                        pushSettingsThenFinish()
                    }
                    .setNegativeButton("先退出") { _, _ ->
                        finishWith("已在本机生效（未同步云端）")
                    }
                    .show()
            }
        )
    }

    /**
     * 同步老人资料到服务器，并顺带把防护规则一起上行。
     *
     * elder-register 会按 elderId 是否有效区分"就地改资料"和"新建/找回"，这里不用自己判断；
     * 它的响应里已带 boundFamily 与 guardSettings，正好一并落地。
     */
    private fun submitElderProfile(name: String, phone: String) {
        Toast.makeText(this, "正在保存并同步服务器…", Toast.LENGTH_SHORT).show()
        ApiClient.elderRegister(
            name, phone, elderId = GuardConfig.elderId,
            onSuccess = { res ->
                GuardConfig.elderName = res.optString("name", name)
                GuardConfig.elderPhone = res.optString("phone", phone)
                GuardConfig.elderId = res.optInt("elderId", GuardConfig.elderId)
                val code = res.optString("bindCode", "")
                if (code.isNotEmpty()) GuardConfig.bindCode = code
                GuardConfig.elderActivated = true
                // 顺手同步守护人信息：换手机后第一次进本页就能看到子女
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
                        finishWith("✅ 已保存并同步到云端")
                    },
                    onError = { finishWith("✅ 资料已同步（防护规则未同步到云端）") }
                )
            },
            onError = { err ->
                btnSave.isEnabled = true
                saving = false
                Toast.makeText(this, "资料同步失败：$err（其余设置已在本机生效）", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun finishWith(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    /**
     * 全角数字→半角，并丢弃空格/连字符。
     *
     * 中文输入法默认打全角，服务端 `/^1[3-9]\d{9}$/` 必判失败 —— 这个坑踩过不止一次。
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
