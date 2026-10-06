package com.antifraud.guard

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.service.ForegroundGuardService
import com.antifraud.guard.service.LocationGuardService
import org.json.JSONObject
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_LOCATION = 100
        private const val REQ_CALL_LOG = 101
    }

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvNotifStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvBackendStatus: TextView
    private lateinit var tvBindCode: TextView
    private lateinit var tvBoundFamily: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardConfig.init(this)
        ApiClient.init(this)
        setContentView(R.layout.activity_main)

        // ── View 引用 ──
        tvServiceStatus  = findViewById(R.id.tv_service_status)
        tvNotifStatus    = findViewById(R.id.tv_notif_status)
        tvLocationStatus = findViewById(R.id.tv_location_status)
        tvBackendStatus  = findViewById(R.id.tv_backend_status)
        tvBindCode       = findViewById(R.id.tv_bind_code)
        tvBoundFamily    = findViewById(R.id.tv_bound_family)

        val etServerUrl   = findViewById<EditText>(R.id.et_server_url)
        val btnSaveUrl    = findViewById<Button>(R.id.btn_save_url)
        val btnStart      = findViewById<Button>(R.id.btn_start_service)
        val btnAiScan     = findViewById<Button>(R.id.btn_ai_scan)
        val btnNotifPerm  = findViewById<Button>(R.id.btn_notification_perm)
        val btnLocPerm    = findViewById<Button>(R.id.btn_location_perm)
        val btnLogs       = findViewById<Button>(R.id.btn_view_logs)
        val btnSettings   = findViewById<Button>(R.id.btn_settings)
        val btnSos        = findViewById<Button>(R.id.btn_sos)
        val btnCopyCode   = findViewById<Button>(R.id.btn_copy_code)
        val btnRefreshCode = findViewById<Button>(R.id.btn_refresh_code)

        // ── 初始化绑定码 ──
        tvBindCode.text = GuardConfig.bindCode.ifEmpty { "加载中..." }
        syncBindCodeFromServer()
        etServerUrl.setText(GuardConfig.serverUrl)

        // ── 保存/测试后端连接 ──
        btnSaveUrl.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) { toast("请输入有效的后端代理地址"); return@setOnClickListener }
            ApiClient.setServerBaseUrl(url)
            tvBackendStatus.setTextColor(0xFF94A3B8.toInt())
            tvBackendStatus.text = "后端连接：连接测试中..."
            ApiClient.reportRiskEvent(
                eventType = "DEVICE_ONLINE",
                severity  = "LOW",
                details   = JSONObject().apply {
                    put("type", "PING"); put("device", Build.MODEL)
                },
                onSuccess = {
                    runOnUiThread {
                        tvBackendStatus.text  = "后端连接：已连通 ✅"
                        tvBackendStatus.setTextColor(0xFF10B981.toInt())
                        com.antifraud.guard.service.GuardWebSocketManager.start()
                        toast("✅ 成功连通后端服务器！守护长连接已激活。")
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        tvBackendStatus.text  = "后端连接：连接失败 ❌"
                        tvBackendStatus.setTextColor(0xFFEF4444.toInt())
                        toast("❌ 连接失败: $err")
                    }
                }
            )
        }

        // ── 开启防诈守护服务 ──
        btnStart.setOnClickListener {
            checkAndRequestPermissions()
            startGuardServices()
            toast("长者防诈亲情守护服务已启动！")
            refreshStatus()
        }

        // ── AI 拍照识诈 ──
        btnAiScan.setOnClickListener {
            startActivity(Intent(this, AiScanActivity::class.java))
        }

        // ── 通知监听授权 ──
        btnNotifPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ── 位置权限申请 ──
        btnLocPerm.setOnClickListener {
            requestLocationPermission()
        }

        // ── 查看风险记录 ──
        btnLogs.setOnClickListener {
            startActivity(Intent(this, RiskLogActivity::class.java))
        }

        // ── 防护规则设置 ──
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // ── SOS 紧急求助 ──
        btnSos.setOnClickListener {
            showSosConfirmDialog()
        }

        // ── 绑定码操作 ──
        btnCopyCode.setOnClickListener {
            val code = tvBindCode.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("bindCode", code))
            toast("绑定码已复制：$code")
        }
        btnRefreshCode.setOnClickListener {
            val newCode = generateBindCode()
            toast("绑定码刷新中...")
            ApiClient.syncElderBindCode(
                newCode = newCode,
                onSuccess = { serverCode ->
                    GuardConfig.bindCode = serverCode
                    tvBindCode.text = serverCode
                    toast("✅ 绑定码已刷新并同步到服务器")
                },
                onError = { err ->
                    toast("❌ 刷新失败（未同步到服务器）: $err")
                }
            )
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ──────────────────────────────────────────
    //  状态刷新
    // ──────────────────────────────────────────
    private fun refreshStatus() {
        // 守护服务
        val svcRunning = isServiceRunning(ForegroundGuardService::class.java)
        tvServiceStatus.text = if (svcRunning) "守护服务：运行中 ✅" else "守护服务：未启动 ⚠️"
        tvServiceStatus.setTextColor(if (svcRunning) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())

        // 通知监听
        val notifGranted = isNotificationListenerEnabled()
        tvNotifStatus.text = if (notifGranted) "扣款监听：已授权 ✅" else "扣款监听：未授权 ⚠️（点击下方按钮授权）"
        tvNotifStatus.setTextColor(if (notifGranted) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())

        // 位置权限
        val locGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        tvLocationStatus.text = if (locGranted) "位置守护：已授权 ✅" else "位置守护：未授权 ⚠️"
        tvLocationStatus.setTextColor(if (locGranted) 0xFF10B981.toInt() else 0xFFF59E0B.toInt())

        // 绑定码：只显示已同步的码（服务器为唯一事实源，本地不再生成）
        // 为空时显示加载中，等 syncBindCodeFromServer 回调填充
        if (GuardConfig.bindCode.isNotEmpty()) {
            tvBindCode.text = GuardConfig.bindCode
        } else if (tvBindCode.text.isEmpty() || tvBindCode.text == "加载中...") {
            tvBindCode.text = "同步中..."
        }

        // 绑定状态
        tvBoundFamily.text = if (GuardConfig.boundFamilyName.isNotEmpty()) {
            "已绑定守护人：${GuardConfig.boundFamilyName} ✅"
        } else {
            "绑定状态：尚未绑定子女账号（将绑定码告知子女）"
        }
    }

    // ──────────────────────────────────────────
    //  SOS 弹窗确认
    // ──────────────────────────────────────────
    private fun showSosConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("🆘 确认触发紧急求助？")
            .setMessage("将向绑定的子女发送您的实时位置与紧急求助信号，并开始本地环境录音存证。")
            .setPositiveButton("确认求助") { _, _ ->
                triggerSos()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun triggerSos() {
        ApiClient.reportRiskEvent(
            eventType = "SOS",
            severity  = "HIGH",
            details   = JSONObject().apply {
                put("note", "老人主动触发一键紧急求助")
                put("device", Build.MODEL)
                put("timestamp", System.currentTimeMillis())
            },
            onSuccess = {
                runOnUiThread { toast("🆘 SOS 已发送！守护人已收到通知。") }
            },
            onError = { err ->
                runOnUiThread { toast("❌ SOS 发送失败：$err，请检查网络连接") }
            }
        )
    }

    // ──────────────────────────────────────────
    //  启动守护服务
    // ──────────────────────────────────────────
    private fun startGuardServices() {
        val fgIntent = Intent(this, ForegroundGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(fgIntent)
        } else {
            startService(fgIntent)
        }

        // 如果有位置权限，同时启动位置守护服务
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val locIntent = Intent(this, LocationGuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(locIntent)
            } else {
                startService(locIntent)
            }
        }
    }

    // ──────────────────────────────────────────
    //  权限申请
    // ──────────────────────────────────────────
    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_PHONE_STATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.READ_CALL_LOG)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_CALL_LOG)
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        } else {
            toast("位置权限已授权 ✅")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("位置权限授权成功！敏感地点停留感知已开启。")
                // 重新启动位置守护服务
                val locIntent = Intent(this, LocationGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(locIntent)
                else startService(locIntent)
            } else {
                toast("位置权限被拒绝，无法开启敏感地点停留感知。")
            }
        }
    }

    // ──────────────────────────────────────────
    //  辅助方法
    // ──────────────────────────────────────────
    private fun isServiceRunning(cls: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    /**
     * 从服务器拉取本老人账号（elderId）的绑定码并显示。
     * 服务器是绑定码唯一事实源；网络失败时回退显示本地缓存码。
     */
    private fun syncBindCodeFromServer() {
        ApiClient.syncElderBindCode(
            onSuccess = { serverCode ->
                GuardConfig.bindCode = serverCode
                tvBindCode.text = serverCode
                refreshStatus()
            },
            onError = { err ->
                // 网络不通时保留本地码，避免界面空白
                if (GuardConfig.bindCode.isNotEmpty()) {
                    tvBindCode.text = GuardConfig.bindCode
                    toast("⚠️ 绑定码未同步到服务器（$err），当前显示本地缓存")
                }
            }
        )
    }

    private fun generateBindCode(): String {
        return (100000 + Random.nextInt(900000)).toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
