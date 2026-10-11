package com.antifraud.guard.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.antifraud.guard.MainActivity
import com.antifraud.guard.api.ApiClient
import com.antifraud.guard.util.ElderDangerConfirmActivity

class ForegroundGuardService : Service() {

    private val CHANNEL_ID = "anti_fraud_guard_channel"
    private val NOTIFICATION_ID = 1001

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** 通话行为判定监听（Phase 1）：随本服务生命周期启停，保活路径全部继承 */
    private var callRiskWatcher: CallRiskWatcher? = null

    /** 老人危险检测（加速度计跌倒判定）：与通话判定同构，挂在同一个前台服务上 */
    private var elderDangerWatcher: ElderDangerWatcher? = null

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

        // Phase 1 通话行为判定。挂在既有前台服务生命周期里而不是做成独立 Service：
        // 独立前台服务 = 第 4 条常驻通知（诱导用户一键全关）；普通 Service 后台启动受限。
        // 为什么这样取舍见 CallRiskWatcher 类注释。
        callRiskWatcher = CallRiskWatcher(applicationContext).also { it.start() }

        // 危险检测（跌倒）。与通话判定同样挂在既有前台服务上，理由见 ElderDangerWatcher
        // 类注释：独立前台服务 = 多一条常驻通知，会诱导用户一键全关。
        //
        // 确认页的回调必须由**这里**持有（长驻服务），而不是由确认页自己上报：
        // Activity 随时可能被系统回收，而"上报"必须有一次就成功的机会。
        ElderDangerConfirmActivity.onTimeout = { payload ->
            elderDangerWatcher?.reportDanger(payload, confirmedByElder = false)
        }
        ElderDangerConfirmActivity.onCancelled = { _ ->
            elderDangerWatcher?.onElderCancelled()
        }
        elderDangerWatcher = ElderDangerWatcher(applicationContext).also { it.start() }
    }

    override fun onDestroy() {
        unregisterNetworkListener()
        callRiskWatcher?.stop()
        callRiskWatcher = null
        elderDangerWatcher?.stop()
        elderDangerWatcher = null
        // 回调必须置空：静态引用会把这个 Service 泄漏住，
        // 而且服务已销毁时再触发上报会打到一个已经停掉的上报链路上。
        ElderDangerConfirmActivity.onTimeout = null
        ElderDangerConfirmActivity.onCancelled = null
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
        // 角色守卫：START_STICKY 会让系统在进程被杀后原地重启本服务，
        // 若用户已切到子女端，这里必须自愈退出 —— 否则子女手机上会出现
        // 点开就进老人端首页的"守护中"通知，还在后台录音上报。
        if (com.antifraud.guard.config.GuardConfig.appRole != "elder") {
            stopSelf()
            return android.app.Service.START_NOT_STICKY
        }
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
