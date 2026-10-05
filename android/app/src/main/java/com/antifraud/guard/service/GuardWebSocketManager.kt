package com.antifraud.guard.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var webSocket: WebSocket? = null
    private var appContext: Context? = null
    private var isConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shouldReconnect = true

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun start() {
        shouldReconnect = true
        connect()
    }

    fun stop() {
        shouldReconnect = false
        disconnect()
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
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "收到服务端 WebSocket 推送: $text")
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type")

                        if (type == "EMERGENCY_INTERRUPT") {
                            val title = json.optString("alertTitle", "⚠️ 紧急亲情防骗强提醒！")
                            val message = json.optString("alertMessage", "子女已检测到高危行为，请立即挂断电话并停止转账！")
                            val fromUser = json.optString("fromUser", "子女端守护人")

                            handleEmergencyInterrupt(title, message, fromUser)
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

    private fun handleEmergencyInterrupt(title: String, message: String, fromUser: String) {
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

        // 2. 强行拉起全屏红色覆屏警报 Activity
        val intent = Intent(context, EmergencyAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EmergencyAlertActivity.EXTRA_TITLE, title)
            putExtra(EmergencyAlertActivity.EXTRA_MESSAGE, message)
            putExtra(EmergencyAlertActivity.EXTRA_FROM, fromUser)
        }
        context.startActivity(intent)
    }
}
