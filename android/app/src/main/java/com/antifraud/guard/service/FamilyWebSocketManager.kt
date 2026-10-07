package com.antifraud.guard.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.antifraud.guard.config.GuardConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 子女端（App 版）WebSocket 管理器
 * 对齐小程序 app.js 的实时通道逻辑：
 *  - 连接后发送 { type: 'REGISTER', userId, role: 'family' }
 *  - 接收 RISK_ALERT（风险事件实时预警）→ 通知监听者
 *  - 发送 INTERRUPT_CMD（远程强打断）→ 接收 INTERRUPT_ACK
 *  - 断线指数退避重连：3s → 6s → 12s ... 上限 60s
 */
object FamilyWebSocketManager {

    private const val TAG = "FamilyWebSocket"
    private var webSocket: WebSocket? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var reconnectTimer: Runnable? = null
    private var shouldReconnect = false

    /** 前台界面注册的监听者（家庭主界面 / 告警页） */
    private var alertListener: ((JSONObject) -> Unit)? = null

    /** 录音事件监听者（RECORDING_STATE / RECORDING_UPLOADED / RECORDING_ANALYZED） */
    private var recordingListener: ((String, JSONObject) -> Unit)? = null

    private var lastStopCallback: ((Boolean, String) -> Unit)? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun setAlertListener(listener: ((JSONObject) -> Unit)?) {
        alertListener = listener
    }

    fun start() {
        shouldReconnect = true
        reconnectAttempts = 0
        connect()
    }

    fun stop() {
        shouldReconnect = false
        reconnectTimer?.let { mainHandler.removeCallbacks(it) }
        reconnectTimer = null
        try {
            webSocket?.close(1000, "客户端主动断开")
        } catch (ignored: Exception) {}
        webSocket = null
    }

    /** 子女端发起远程强打断 */
    fun sendRemoteInterrupt(message: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val ws = webSocket
        if (ws == null || GuardConfig.boundElderId <= 0) {
            onResult?.invoke(false, "通道未连接或未绑定老人")
            return
        }
        val cmd = JSONObject().apply {
            put("type", "INTERRUPT_CMD")
            put("targetElderId", GuardConfig.boundElderId)
            put("message", message)
        }
        val ok = ws.send(cmd.toString())
        lastInterruptCallback = onResult
        if (!ok) onResult?.invoke(false, "指令发送失败，请检查连接")
    }

    private var lastInterruptCallback: ((Boolean, String) -> Unit)? = null

    /** 证据页注册录音事件监听 */
    fun setRecordingListener(listener: ((String, JSONObject) -> Unit)?) {
        recordingListener = listener
    }

    /**
     * 远程停止老人端正在进行的录音。
     * 场景：子女已经赶到现场、或确认是误触发，不需要继续录下去。
     */
    fun sendStopRecording(onResult: ((Boolean, String) -> Unit)? = null) {
        val ws = webSocket
        if (ws == null || GuardConfig.boundElderId <= 0) {
            onResult?.invoke(false, "通道未连接或未绑定老人")
            return
        }
        val cmd = JSONObject().apply {
            put("type", "RECORDING_STOP_CMD")
            put("targetElderId", GuardConfig.boundElderId)
            put("fromUser", GuardConfig.familyUsername.ifEmpty { "子女端守护人" })
        }
        val ok = ws.send(cmd.toString())
        lastStopCallback = onResult
        if (!ok) {
            onResult?.invoke(false, "指令发送失败，请检查连接")
            lastStopCallback = null
        }
    }

    private fun connect() {
        val context = appContext ?: return
        GuardConfig.init(context)
        val userId = GuardConfig.familyUserId
        if (userId <= 0) {
            Log.w(TAG, "子女端未登录，跳过 WebSocket 连接")
            return
        }

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

        Log.i(TAG, "子女端正在建立 WebSocket 实时通道: $wsUrl")

        try {
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.i(TAG, "✅ 子女端 WebSocket 已连接")
                    reconnectAttempts = 0
                    val reg = JSONObject().apply {
                        put("type", "REGISTER")
                        put("userId", userId)
                        put("role", "family")
                    }
                    ws.send(reg.toString())
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "收到服务端推送: $text")
                    try {
                        val json = JSONObject(text)
                        when (json.optString("type")) {
                            "RISK_ALERT" -> {
                                val listener = alertListener
                                val data = json.optJSONObject("data") ?: JSONObject()
                                if (listener != null) {
                                    mainHandler.post { listener(data) }
                                } else {
                                    showBackgroundAlert(data)
                                }
                            }
                            "RECORDING_STOP_ACK" -> {
                                val success = json.optBoolean("success", false)
                                val message = json.optString("message", "")
                                lastStopCallback?.invoke(success, message)
                                lastStopCallback = null
                            }
                            "RECORDING_STATE",
                            "RECORDING_UPLOADED",
                            "RECORDING_ANALYZED",
                            "RECORDING_REVIEWED",
                            "RECORDING_DELETED" -> {
                                val listener = recordingListener
                                if (listener != null) {
                                    val data = json.optJSONObject("data") ?: JSONObject()
                                    mainHandler.post { listener(json.optString("type"), data) }
                                }
                            }
                            "INTERRUPT_ACK" -> {
                                val success = json.optBoolean("success", false)
                                val message = json.optString("message", "")
                                lastInterruptCallback?.invoke(success, message)
                                lastInterruptCallback = null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析 WebSocket 消息失败", e)
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket 已关闭: $reason")
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
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
        val delay = minOf(3000L * (1L shl minOf(reconnectAttempts, 5)), 60000L)
        reconnectAttempts += 1
        Log.i(TAG, "WebSocket 已断开，${delay / 1000}s 后重连...")
        val runnable = Runnable {
            reconnectTimer = null
            connect()
        }
        reconnectTimer = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    /** 前台无监听者时的兜底提示（简单 Toast，避免后台弹窗打扰） */
    private fun showBackgroundAlert(data: JSONObject) {
        val context = appContext ?: return
        mainHandler.post {
            android.widget.Toast.makeText(
                context,
                "⚠️ 收到防诈预警: ${data.optString("event_type", "风险事件")}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
