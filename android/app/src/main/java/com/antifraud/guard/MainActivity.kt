package com.antifraud.guard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.service.ForegroundGuardService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()
        setContentView(R.layout.activity_main)

        val btnStartService = findViewById<Button>(R.id.btn_start_service)
        val btnNotificationPerm = findViewById<Button>(R.id.btn_notification_perm)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

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
