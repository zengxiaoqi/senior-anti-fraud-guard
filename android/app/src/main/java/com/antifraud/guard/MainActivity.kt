package com.antifraud.guard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.service.ForegroundGuardService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()
        setContentView(R.layout.activity_main)

        val btnStartService = findViewById<Button>(R.id.btn_start_service)
        val btnNotificationPerm = findViewById<Button>(R.id.btn_notification_perm)
        val btnSaveUrl = findViewById<Button>(R.id.btn_save_url)
        val etServerUrl = findViewById<EditText>(R.id.et_server_url)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        val sp = getSharedPreferences("guard_config", Context.MODE_PRIVATE)
        val savedUrl = sp.getString("server_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
        etServerUrl.setText(savedUrl)
        ApiClient.setServerBaseUrl(savedUrl)

        // 保存并测试公网/内网代理连接
        btnSaveUrl.setOnClickListener {
            val inputUrl = etServerUrl.text.toString().trim()
            if (inputUrl.isEmpty()) {
                Toast.makeText(this, "请输入有效的后端代理地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sp.edit().putString("server_url", inputUrl).apply()
            ApiClient.setServerBaseUrl(inputUrl)

            // 发送一条探活测试事件
            val testDetails = JSONObject().apply {
                put("type", "PING_TEST")
                put("device", android.os.Build.MODEL)
            }
            ApiClient.reportRiskEvent(
                elderId = 1,
                eventType = "DEVICE_ONLINE",
                severity = "LOW",
                details = testDetails,
                onSuccess = {
                    runOnUiThread {
                        Toast.makeText(this, "✅ 成功连通后端服务器！", Toast.LENGTH_LONG).show()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        Toast.makeText(this, "❌ 连接失败: $err", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // 启动前台守护服务
        btnStartService.setOnClickListener {
            val intent = Intent(this, ForegroundGuardService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvStatus.text = "守护状态：全天候无感防诈守护运行中 ✅"
            Toast.makeText(this, "长者防诈亲情守护服务已成功启动！", Toast.LENGTH_SHORT).show()
        }

        // 引导授权通知栏监听权限（用于捕获大额扣款）
        btnNotificationPerm.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
    }
}
