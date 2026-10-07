package com.antifraud.guard.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antifraud.guard.EmergencyAlertActivity
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.db.RiskEventDbHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GuardWebSocketManager {

    private const val TAG = "GuardWebSocket"
    private const val ALERT_CHANNEL_ID = "emergency_interrupt_channel"
    private const val ALERT_NOTIFICATION_ID = 2001

    private var webSocket: WebSocket? = null
    private var appContext: Context? = null
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shouldReconnect = true

    // 应用是否处于前台：Android 10+ 后台启动 Activity 受限，需据此降级为全屏意图通知
    private var resumedActivityCount = 0
    private var lifecycleHooksRegistered = false
    private val isAppInForeground: Boolean get() = resumedActivityCount > 0

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // 通过 ActivityLifecycleCallbacks 追踪前后台状态（无需自定义 Application 类），只注册一次
        if (lifecycleHooksRegistered) return
        val app = appContext as? Application ?: return
        lifecycleHooksRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                resumedActivityCount++
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    fun start() {
        shouldReconnect = true
        connect()
    }

    fun stop() {
        shouldReconnect = false
        disconnect()
    }

    /**
     * 上报本机录音运行态给服务端（开始/分段/停止）。
     * 服务端据此维护老人录音状态，并推给子女端显示"正在录音"指示灯。
     * 连接未就绪时静默丢弃 —— 状态是瞬时信息，补发没有意义。
     */
    fun sendState(payload: JSONObject) {
        val ws = webSocket
        if (ws == null || !isConnected) return
        try {
            ws.send(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "上报录音状态失败: ${e.message}")
        }
    }

    private fun connect() {
        val context = appContext ?: return
        GuardConfig.init(context)

        val rawServerUrl = GuardConfig.serverUrl.trim().removeSuffix("/")
        if (rawServerUrl.isEmpty()) {
            Log.w(TAG, "未配置后端服务器地址，跳过 WebSocket 连接")
            return
        }

        val wsUrl = when {
            rawServerUrl.startsWith("https://") -> rawServerUrl.replaceFirst("https://", "wss://")
            rawServerUrl.startsWith("http://") -> rawServerUrl.replaceFirst("http://", "ws://")
            else -> "ws://$rawServerUrl:3000"
        }

        Log.i(TAG, "正在建立 WebSocket 远程守护通道: $wsUrl")

        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    isConnected = true
                    Log.i(TAG, "✅ WebSocket 远程守护通道已成功连接")

                    // 注册当前终端身份为老人端
                    val regJson = JSONObject().apply {
                        put("type", "REGISTER")
                        put("userId", GuardConfig.elderId)
                        put("role", "elder")
                    }
                    ws.send(regJson.toString())
                    // 网络刚恢复，立刻把积压的录音段补传出去
                    UploadQueue.trigger()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "收到服务端 WebSocket 推送: $text")
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type")

                        if (type == "RECORDING_STOP") {
                            // 子女端远程喊停：立即结束本机录音并把已录段推给服务器
                            val fromUser = json.optString("fromUser", "子女端守护人")
                            Log.i(TAG, "收到子女端远程停止录音指令：$fromUser")
                            val stopIntent = Intent(context, RecordingGuardService::class.java).apply {
                                action = RecordingGuardService.ACTION_STOP
                                putExtra(RecordingGuardService.EXTRA_REASON, "SOS")
                            }
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(stopIntent)
                                } else {
                                    context.startService(stopIntent)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "远程停止录音执行失败: ${e.message}", e)
                            }
                            // 停完立刻把已录的分段传出去
                            UploadQueue.trigger()
                        }

                        if (type == "EMERGENCY_INTERRUPT") {
                            val title = json.optString("alertTitle", "⚠️ 紧急亲情防骗强提醒！")
                            val message = json.optString("alertMessage", "子女已检测到高危行为，请立即挂断电话并停止转账！")
                            val fromUser = json.optString("fromUser", "子女端守护人")
                            // 服务端下发的守护人真实手机号：警报页一键拨号直接预填
                            val fromPhone = sanitizePhone(json.optString("fromPhone", ""))

                            handleEmergencyInterrupt(title, message, fromUser, fromPhone)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析 WebSocket 消息失败", e)
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    Log.w(TAG, "WebSocket 正在关闭: $reason")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    Log.w(TAG, "WebSocket 已关闭: $reason")
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    Log.e(TAG, "WebSocket 连接故障: ${t.message}")
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "发起 WebSocket 连接异常", e)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            Log.i(TAG, "🔄 正在尝试重新连接 WebSocket 远程守护网络...")
            connect()
        }, 5000)
    }

    private fun disconnect() {
        try {
            webSocket?.close(1000, "客户端主动断开")
        } catch (ignored: Exception) {}
        webSocket = null
        isConnected = false
    }

    private fun handleEmergencyInterrupt(title: String, message: String, fromUser: String, fromPhone: String) {
        val context = appContext ?: return

        // 1. 记录到本地数据库
        try {
            val dbHelper = RiskEventDbHelper(context)
            val details = JSONObject().apply {
                put("from", fromUser)
                put("title", title)
                put("message", message)
            }
            dbHelper.insertEvent(GuardConfig.elderId, "REMOTE_INTERRUPT", "HIGH", details)
        } catch (e: Exception) {
            Log.e(TAG, "保存远程打断事件到本地库失败", e)
        }

        // 2. 拉起全屏红色覆屏警报：
        //    - 应用在前台：直接启动 Activity
        //    - 应用在后台且通知已授权：走「全屏意图通知」（full-screen intent），
        //      这是 Android 10+ 后台启动 Activity 受限后的合规通道，锁屏也能弹全屏警报
        //    - 通知被禁（Android 13+ 未授 POST_NOTIFICATIONS）：降级为直接尝试启动 Activity
        val alertIntent = Intent(context, EmergencyAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EmergencyAlertActivity.EXTRA_TITLE, title)
            putExtra(EmergencyAlertActivity.EXTRA_MESSAGE, message)
            putExtra(EmergencyAlertActivity.EXTRA_FROM, fromUser)
            putExtra(EmergencyAlertActivity.EXTRA_FAMILY_PHONE, fromPhone)
        }

        val notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

        if (!isAppInForeground && notificationsEnabled) {
            showFullScreenAlertNotification(context, alertIntent, title, message, fromUser)
        } else {
            if (!isAppInForeground) {
                Log.w(TAG, "通知权限未授权，全屏通知不可用，尝试直接启动警报页（Android 10+ 可能被系统拦截）")
            }
            context.startActivity(alertIntent)
        }
    }

    private fun showFullScreenAlertNotification(
        context: Context,
        alertIntent: Intent,
        title: String,
        message: String,
        fromUser: String
    ) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "紧急亲情打断警报",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "子女端发起远程强打断时全屏弹出警报"
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                ALERT_NOTIFICATION_ID,
                alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n来自：$fromUser"))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .build()

            manager.notify(ALERT_NOTIFICATION_ID, notification)
            Log.i(TAG, "老人端处于后台，已通过全屏意图通知拉起紧急警报")
        } catch (e: Exception) {
            Log.e(TAG, "全屏意图通知拉起失败，降级为直接启动警报页", e)
            context.startActivity(alertIntent)
        }
    }

    /**
     * 清洗手机号：只保留数字（含全角数字转半角），微信占位号/用户名/非法字符一律丢弃返回空串。
     * 老人端紧急警报要直接拨号，号码必须干净，否则会拉起一个空号码的拨号盘。
     */
    private fun sanitizePhone(raw: String): String {
        val normalized = raw.trim().map { ch ->
            when (ch) {
                in '０'..'９' -> ('0' + (ch - '０'))// 全角数字 → 半角
                '＋', '+' -> '+'
                '-' , '－', ' ' -> ' '
                else -> ch
            }
        }.joinToString("")
        if (normalized.isEmpty() || normalized.startsWith("wx_", ignoreCase = true)) return ""
        val digits = normalized.filter { it.isDigit() }
        // 少于 6 位基本不可能是真实号码（也顺带挡掉用户名 a1b2c3 之类）
        return if (digits.length >= 6) normalized.filter { it == '+' || it.isDigit() } else ""
    }
}
