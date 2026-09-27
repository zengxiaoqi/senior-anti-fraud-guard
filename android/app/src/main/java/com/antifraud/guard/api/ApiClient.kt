package com.antifraud.guard.api

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object ApiClient {
    private var serverUrl = "http://10.0.2.2:3000/api/events/report"

    fun setServerBaseUrl(rawUrl: String) {
        val clean = rawUrl.trim().removeSuffix("/")
        serverUrl = if (clean.startsWith("http://") || clean.startsWith("https://")) {
            "$clean/api/events/report"
        } else {
            "http://$clean:3000/api/events/report"
        }
    }

    fun getServerUrl(): String = serverUrl

    /**
     * 上报风险事件到 Express + SQLite 后端
     */
    fun reportRiskEvent(
        elderId: Int,
        eventType: String,
        severity: String,
        details: JSONObject,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        thread {
            try {
                val url = URL(serverUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val payload = JSONObject().apply {
                    put("elderId", elderId)
                    put("eventType", eventType)
                    put("severity", severity)
                    put("details", details)
                }

                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(payload.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode == 200 || responseCode == 201) {
                    onSuccess?.invoke()
                } else {
                    onError?.invoke("HTTP 错误码: $responseCode")
                }
                conn.disconnect()
            } catch (e: Exception) {
                onError?.invoke(e.localizedMessage ?: "网络请求失败")
            }
        }
    }
}
