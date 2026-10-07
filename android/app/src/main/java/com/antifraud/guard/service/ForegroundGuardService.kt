package com.antifraud.guard.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antifraud.guard.MainActivity
import com.antifraud.guard.api.ApiClient

class ForegroundGuardService : Service() {

    private val CHANNEL_ID = "anti_fraud_guard_channel"
    private val NOTIFICATION_ID = 1001

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        GuardWebSocketManager.init(this)
        GuardWebSocketManager.start()

        // 录音上传队列：常驻后台，服务一启动就检查有没有积压的录音段
        //
        // 必须先 ApiClient.init(this)：本服务可能在 App 界面完全没打开时被系统
        // 拉起（开机自启/被回收后重启）。此时 ApiClient.serverUrl 还是默认值
        // "http://10.0.2.2:3000/api/events/report" —— 那是模拟器专用地址，
        // 在真机上会拼出错误的 URL，导致录音上传永远失败。
        // ApiClient 是 object 单例，状态在进程内共享，所以这里初始化一次即可。
        ApiClient.init(applicationContext)
        UploadQueue.init(applicationContext)
        UploadQueue.trigger()
        registerNetworkListener()
    }

    override fun onDestroy() {
        unregisterNetworkListener()
        super.onDestroy()
        GuardWebSocketManager.stop()
    }

    /**
     * 监听网络恢复：老人在屋里录音时经常手机没信号，出门一恢复就得赶紧补传，
     * 不能干等 15 分钟的退避计时。
     */
    private fun registerNetworkListener() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    android.util.Log.i("GuardService", "网络恢复，立即补传录音队列")
                    UploadQueue.trigger()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            android.util.Log.w("GuardService", "注册网络监听失败（不影响录音功能）: ${e.message}")
        }
    }

    private fun unregisterNetworkListener() {
        try {
            networkCallback?.let { cb ->
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) {
            // 已注销，忽略
        }
        networkCallback = null
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
