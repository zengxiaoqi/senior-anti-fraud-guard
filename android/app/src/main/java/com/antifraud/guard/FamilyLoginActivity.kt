package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager

/**
 * 子女端（App 版）登录/注册页
 * 登录模式：只显示 服务器地址 + 用户名/手机号 + 密码
 * 注册模式：点"注册新账号"切换后，额外显示 手机号 + 昵称
 * 服务端接口：POST /api/auth/login、POST /api/auth/register
 */
class FamilyLoginActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etPhone: EditText
    private lateinit var etNickname: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var labelPhone: TextView
    private lateinit var labelNickname: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnToggleMode: Button
    private lateinit var btnTestConnection: Button

    private var registerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        FamilyWebSocketManager.init(this)
        setContentView(R.layout.activity_family_login)

        etServerUrl = findViewById(R.id.et_server_url)
        etUsername  = findViewById(R.id.et_username)
        etPassword  = findViewById(R.id.et_password)
        etPhone     = findViewById(R.id.et_phone)
        etNickname  = findViewById(R.id.et_nickname)
        tvStatus    = findViewById(R.id.tv_login_status)
        tvTitle     = findViewById(R.id.tv_login_title)
        labelPhone    = findViewById(R.id.label_phone)
        labelNickname = findViewById(R.id.label_nickname)
        btnPrimary    = findViewById(R.id.btn_login)
        btnToggleMode = findViewById(R.id.btn_register)
        btnTestConnection = findViewById(R.id.btn_test_connection)

        etServerUrl.setText(GuardConfig.serverUrl)
        etUsername.setText(GuardConfig.familyUsername)

        btnPrimary.setOnClickListener {
            if (registerMode) doRegister() else doLogin()
        }
        btnToggleMode.setOnClickListener { switchMode() }
        btnTestConnection.setOnClickListener { testConnection() }
    }

    /**
     * 测试服务器连接：先按登录时会用的方式保存地址，再探测 GET /api/health。
     * 这样"测"的就是"之后登录真正会用"的地址，避免测过了却没保存、
     * 或保存的地址和测的不是同一个导致误判。
     */
    private fun testConnection() {
        val raw = etServerUrl.text.toString().trim()
        if (raw.isEmpty()) {
            setStatus("请先填写服务器地址（默认 https://guard.chataifree.eu.org）", "#EF4444")
            return
        }
        saveServerUrl()
        val base = ApiClient.getBaseUrl()
        setStatus("正在测试连接：$base/api/health", "#64748B")
        btnTestConnection.isEnabled = false
        ApiClient.checkHealth(onSuccess = {
            btnTestConnection.isEnabled = true
            setStatus("✅ 连接成功：$base 可用", "#16A34A")
        }, onError = { err ->
            btnTestConnection.isEnabled = true
            setStatus("❌ $err", "#EF4444")
        })
    }

    /** 登录 <-> 注册 模式切换 */
    private fun switchMode() {
        registerMode = !registerMode
        tvStatus.text = ""
        if (registerMode) {
            tvTitle.text = "📝 注册新账号"
            labelPhone.visibility = View.VISIBLE
            labelNickname.visibility = View.VISIBLE
            etPhone.visibility = View.VISIBLE
            etNickname.visibility = View.VISIBLE
            btnPrimary.text = "注 册"
            btnToggleMode.text = "已有账号？返回登录"
        } else {
            tvTitle.text = "🔐 子女端登录"
            labelPhone.visibility = View.GONE
            labelNickname.visibility = View.GONE
            etPhone.visibility = View.GONE
            etNickname.visibility = View.GONE
            btnPrimary.text = "登 录"
            btnToggleMode.text = "没有账号？注册新账号"
        }
    }

/**
 * 手机号归一化：全角数字→半角 + 清除不可见字符 + 去掉分隔符。
 * 注册必填手机号，中文输入法全角打出的是"１３２９７７２００１１"，
 * 不归一化会被服务端判为格式错误，用户却看着屏幕上明明是 11 位数字。
 */
private fun normalizePhone(s: String): String {
    val sb = StringBuilder()
    for (ch in s.trim()) {
        when {
            ch in '０'..'９' -> sb.append('0' + (ch - '０'))
            ch in '0'..'9' -> sb.append(ch)
            ch == '-' || ch == '－' || ch == ' ' -> {} // 分隔符直接丢弃
        }
    }
    return sb.toString()
}

private fun cleanInput(s: String): String =
        s.trim().replace(Regex("[\\u200B-\\u200D\\uFEFF\\u2060\\u00AD]"), "")

    /**
     * 密码归一化：全角字符→半角 + 清除不可见字符与首尾空白。
     * 防止中文输入法在全角模式下打数字（如 １１１１１１，看着和 111111 一样但哈希对不上）、
     * 或夹带不可见字符导致"密码明明没错却提示用户名或密码错误"。
     */
    private fun normalizePassword(s: String): String =
        s.map { c ->
            val code = c.code
            when {
                code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar() // 全角 ASCII 区 → 半角
                c == '\u3000' -> ' '                                // 全角空格 → 半角空格
                else -> c
            }
        }.joinToString("")
            .trim()
            .replace(Regex("[\\u200B-\\u200D\\uFEFF\\u2060\\u00AD]"), "")

    private fun saveServerUrl() {
        val url = etServerUrl.text.toString().trim()
        if (url.isNotEmpty()) ApiClient.setServerBaseUrl(url)
    }

    private fun doLogin() {
        saveServerUrl()
        val username = cleanInput(etUsername.text.toString())
        val password = normalizePassword(etPassword.text.toString())
        if (username.isEmpty() || password.isEmpty()) {
            tvStatus.text = "请输入用户名（或手机号）和密码"
            return
        }
        setStatus("登录中...", "#64748B")
        btnPrimary.isEnabled = false
        ApiClient.familyLogin(username, password, onSuccess = {
            Toast.makeText(this, "✅ 登录成功", Toast.LENGTH_SHORT).show()
            enterHome()
        }, onError = { err ->
            btnPrimary.isEnabled = true
            setStatus(err, "#EF4444")
        })
    }

    private fun doRegister() {
        saveServerUrl()
        val username = cleanInput(etUsername.text.toString())
        val password = normalizePassword(etPassword.text.toString())
        val phone    = normalizePhone(etPhone.text.toString())
        val nickname = etNickname.text.toString().trim()
        if (username.isEmpty() || password.isEmpty()) {
            tvStatus.text = "请输入用户名和密码"
            return
        }
        // 客户端预校验：提前拦下格式问题，给出可读提示（避免服务端报错后一头雾水）
        if (!Regex("^[A-Za-z0-9_]{4,20}$").matches(username)) {
            tvStatus.text = "用户名需为 4-20 位字母、数字或下划线（当前 ${username.length} 个字符，请勿粘贴带隐藏字符的内容）"
            return
        }
        // 手机号必填：老人端的紧急警报一键拨号完全依赖这个号码，缺了就打不出去
        if (phone.isEmpty()) {
            tvStatus.text = "手机号为必填项！老人端紧急警报需要它才能一键拨给子女"
            return
        }
        if (!Regex("^1[3-9]\\d{9}$").matches(phone)) {
            tvStatus.text = "手机号格式不正确（当前 ${phone.length} 位，需 11 位大陆手机号）"
            return
        }
        setStatus("注册中...", "#64748B")
        btnPrimary.isEnabled = false
        ApiClient.familyRegister(username, password, nickname, phone,
            onSuccess = { _ ->
                Toast.makeText(this, "✅ 注册成功，自动登录中", Toast.LENGTH_SHORT).show()
                // 注册成功后自动登录
                ApiClient.familyLogin(username, password, onSuccess = {
                    enterHome()
                }, onError = { err ->
                    btnPrimary.isEnabled = true
                    setStatus("注册成功，但自动登录失败：$err", "#EF4444")
                })
            },
            onError = { err ->
                btnPrimary.isEnabled = true
                setStatus(err, "#EF4444")
            })
    }

    private fun setStatus(text: String, colorHex: String) {
        tvStatus.text = text
        tvStatus.setTextColor(android.graphics.Color.parseColor(colorHex))
    }

    private fun enterHome() {
        startActivity(Intent(this, FamilyHomeActivity::class.java).apply {
            putExtra(FamilyHomeActivity.EXTRA_FROM_LOGIN, true)
        })
        finish()
    }
}
