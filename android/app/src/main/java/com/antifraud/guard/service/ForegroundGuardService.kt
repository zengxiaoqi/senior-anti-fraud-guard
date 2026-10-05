package com.antifraud.guard.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antifraud.guard.MainActivity

class ForegroundGuardService : Service() {

    private val CHANNEL_ID = "anti_fraud_guard_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        GuardWebSocketManager.init(this)
        GuardWebSocketManager.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        GuardWebSocketManager.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 常驻后台守护，即使杀死 App 界面也能被系统重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("长者防诈亲情守护中")
            .setContentText("已开启通话安全、防诈提示与大额支付全天候无感守护")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "防诈守护前台服务通道",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "确保老人守护服务不会被系统后台误杀"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
