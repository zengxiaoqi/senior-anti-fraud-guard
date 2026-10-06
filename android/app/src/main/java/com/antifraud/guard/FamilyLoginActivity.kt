package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.FamilyWebSocketManager
import org.json.JSONObject

/**
 * 子女端（App 版）账号密码登录/注册页
 * 服务端接口：POST /api/auth/login、POST /api/auth/register
 * 登录成功后签发的 Token 与小程序 wx-login 同一套体系
 */
class FamilyLoginActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etNickname: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        FamilyWebSocketManager.init(this)
        setContentView(R.layout.activity_family_login)

        etServerUrl = findViewById(R.id.et_server_url)
        etUsername  = findViewById(R.id.et_username)
        etPassword  = findViewById(R.id.et_password)
        etNickname  = findViewById(R.id.et_nickname)
        tvStatus    = findViewById(R.id.tv_login_status)
        btnLogin    = findViewById(R.id.btn_login)
        btnRegister = findViewById(R.id.btn_register)

        etServerUrl.setText(GuardConfig.serverUrl)
        etUsername.setText(GuardConfig.familyUsername)

        btnLogin.setOnClickListener { doLogin() }
        btnRegister.setOnClickListener { doRegister() }
    }

    private fun saveServerUrl() {
        val url = etServerUrl.text.toString().trim()
        if (url.isNotEmpty()) ApiClient.setServerBaseUrl(url)
    }

    private fun doLogin() {
        saveServerUrl()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            tvStatus.text = "请输入用户名和密码"
            return
        }
        setStatus("登录中...", "#64748B")
        btnLogin.isEnabled = false
        ApiClient.familyLogin(username, password, onSuccess = {
            Toast.makeText(this, "✅ 登录成功", Toast.LENGTH_SHORT).show()
            enterHome()
        }, onError = { err ->
            btnLogin.isEnabled = true
            setStatus(err, "#EF4444")
        })
    }

    private fun doRegister() {
        saveServerUrl()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val nickname = etNickname.text.toString().trim()
        if (username.isEmpty() || password.isEmpty()) {
            tvStatus.text = "请输入用户名和密码"
            return
        }
        setStatus("注册中...", "#64748B")
        btnRegister.isEnabled = false
        ApiClient.familyRegister(username, password, nickname,
            onSuccess = { _ ->
                Toast.makeText(this, "✅ 注册成功，自动登录中", Toast.LENGTH_SHORT).show()
                // 注册成功后自动登录
                ApiClient.familyLogin(username, password, onSuccess = {
                    enterHome()
                }, onError = { err ->
                    btnRegister.isEnabled = true
                    setStatus("注册成功，但自动登录失败：$err", "#EF4444")
                })
            },
            onError = { err ->
                btnRegister.isEnabled = true
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
