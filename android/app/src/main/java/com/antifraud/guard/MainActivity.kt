package com.antifraud.guard

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.ForegroundGuardService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var spinnerElder: Spinner
    private val elderOptions = listOf("老人账号 1", "老人账号 2", "老人账号 3")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiClient.init(this)
        setContentView(R.layout.activity_main)

        val etServerUrl = findViewById<EditText>(R.id.et_server_url)
        val btnSaveUrl = findViewById<Button>(R.id.btn_save_url)
        val btnStartService = findViewById<Button>(R.id.btn_start_service)
        val btnNotificationPerm = findViewById<Button>(R.id.btn_notification_perm)
        val btnViewLogs = findViewById<Button>(R.id.btn_view_logs)
        tvServiceStatus = findViewById(R.id.tv_service_status)
        tvNotifStatus = findViewById(R.id.tv_notif_status)
        spinnerElder = findViewById(R.id.spinner_elder)

        etServerUrl.setText(GuardConfig.serverUrl)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, elderOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerElder.adapter = adapter
        spinnerElder.setSelection(GuardConfig.elderId - 1)

        spinnerElder.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                GuardConfig.elderId = position + 1
                GuardConfig.setElderNameForId(position + 1, elderOptions[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnSaveUrl.setOnClickListener {
            val inputUrl = etServerUrl.text.toString().trim()
            if (inputUrl.isEmpty()) {
                Toast.makeText(this, "请输入有效的后端代理地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiClient.setServerBaseUrl(inputUrl)

            val testDetails = JSONObject().apply {
                put("type", "PING_TEST")
                put("device", android.os.Build.MODEL)
            }
            ApiClient.reportRiskEvent(
                eventType = "DEVICE_ONLINE",
                severity = "LOW",
                details = testDetails,
                onSuccess = {
                    runOnUiThread { Toast.makeText(this, "✅ 成功连通后端服务器！", Toast.LENGTH_LONG).show() }
                },
                onError = { err ->
                    runOnUiThread { Toast.makeText(this, "❌ 连接失败: $err", Toast.LENGTH_LONG).show() }
                }
            )
        }

        btnStartService.setOnClickListener {
            val intent = Intent(this, ForegroundGuardService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            refreshStatus()
            Toast.makeText(this, "长者防诈亲情守护服务已启动！", Toast.LENGTH_SHORT).show()
        }

        btnNotificationPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnViewLogs.setOnClickListener {
            startActivity(Intent(this, RiskLogActivity::class.java))
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        tvServiceStatus.text = if (isServiceRunning(ForegroundGuardService::class.java)) {
            "守护状态：全天候无感防诈守护运行中 ✅"
        } else {
            "守护状态：未启动 ⚠️"
        }
        tvServiceStatus.setTextColor(if (isServiceRunning(ForegroundGuardService::class.java)) {
            0xFF10B981.toInt()
        } else {
            0xFFF59E0B.toInt()
        })

        tvNotifStatus.text = if (isNotificationListenerEnabled()) {
            "扣款通知监听：已授权 ✅"
        } else {
            "扣款通知监听：未授权 ⚠️"
        }
        tvNotifStatus.setTextColor(if (isNotificationListenerEnabled()) {
            0xFF10B981.toInt()
        } else {
            0xFFF59E0B.toInt()
        })
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }
}
