package com.antifraud.guard.api

import android.content.Context
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.db.RiskEventDbHelper
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object ApiClient {
    private var serverUrl = "http://10.0.2.2:3000/api/events/report"
    private var dbHelper: RiskEventDbHelper? = null

    fun init(context: Context) {
        GuardConfig.init(context)
        dbHelper = RiskEventDbHelper(context)
        setServerBaseUrl(GuardConfig.serverUrl)
    }

    fun setServerBaseUrl(rawUrl: String) {
        val clean = rawUrl.trim().removeSuffix("/")
        serverUrl = if (clean.startsWith("http://") || clean.startsWith("https://")) {
            "$clean/api/events/report"
        } else {
            "http://$clean:3000/api/events/report"
        }
        GuardConfig.serverUrl = clean
    }

    fun getServerUrl(): String = serverUrl

    /** 干净的服务端基地址（不带路径），供子女端各接口拼接 */
    fun getBaseUrl(): String =
        serverUrl.removeSuffix("/api/events/report")

    // ════════════════════════════════════════
    //  子女端（App 版）：账号密码登录 + Token 请求
    // ════════════════════════════════════════

    /**
     * 子女端账号密码注册
     * 成功回调 data：{ userId, bindCode, boundUser, token }
     */
    fun familyRegister(
        username: String, password: String, name: String,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            if (name.isNotEmpty()) put("name", name)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/register", body, null,
            onSuccess = onSuccess, onError = onError)
    }

    /**
     * 子女端账号密码登录，成功后把会话写入 GuardConfig
     */
    fun familyLogin(
        username: String, password: String,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/login", body, null,
            onSuccess = { res ->
                try {
                    val data = res.getJSONObject("data")
                    GuardConfig.familyUsername = username
                    GuardConfig.familyPassword = password
                    GuardConfig.familyToken = data.optString("token")
                    GuardConfig.familyUserId = data.optInt("userId", 0)
                    GuardConfig.familyBindCode = data.optString("bindCode")
                    val boundUser = data.optJSONObject("boundUser")
                    if (boundUser != null && boundUser.optInt("id", 0) > 0) {
                        GuardConfig.boundElderId = boundUser.optInt("id")
                        GuardConfig.boundElderName = boundUser.optString("name", "守护对象")
                    } else {
                        GuardConfig.boundElderId = 0
                        GuardConfig.boundElderName = ""
                    }
                    onSuccess(data)
                } catch (e: Exception) {
                    onError("解析登录响应失败: ${e.message}")
                }
            },
            onError = onError)
    }

    /** 401 后用本机保存的账密静默重登一次 */
    private fun silentRelogin(onDone: (Boolean, String) -> Unit) {
        val username = GuardConfig.familyUsername
        val password = GuardConfig.familyPassword
        if (username.isEmpty() || password.isEmpty()) {
            onDone(false, "登录态已失效，请重新登录")
            return
        }
        familyLogin(username, password, { onDone(true, "") }, { onDone(false, it) })
    }

    /**
     * 子女端带 X-Auth-Token 的 GET 请求（401 自动重登并重试一次）
     * onSuccess 回调响应 JSON 的 data 节点
     */
    fun familyGet(path: String, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        authedRequest("GET", "${getBaseUrl()}$path", null, onSuccess, onError)
    }

    /** 子女端带 X-Auth-Token 的 POST 请求 */
    fun familyPost(path: String, body: JSONObject?, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        authedRequest("POST", "${getBaseUrl()}$path", body, onSuccess, onError)
    }

    /** 通用无鉴权 JSON 请求：成功时在主线程回调完整响应 JSON */
    private fun requestJson(
        method: String, urlStr: String, body: JSONObject?, token: String?,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit
    ) {
        thread {
            try {
                val conn = openConnection(method, urlStr, body, token)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                conn.disconnect()

                if (code in 200..299) {
                    val json = JSONObject(text)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onSuccess(json) }
                } else {
                    val errMsg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                    postError(onError, errMsg.ifEmpty { "HTTP $code" })
                }
            } catch (e: Exception) {
                postError(onError, e.localizedMessage ?: "网络请求失败")
            }
        }
    }

    private fun authedRequest(
        method: String, urlStr: String, body: JSONObject?,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit, retried: Boolean = false
    ) {
        thread {
            var statusCode = -1
            try {
                val conn = openConnection(method, urlStr, body, GuardConfig.familyToken)
                statusCode = conn.responseCode
                val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                if (statusCode in 200..299) {
                    val json = JSONObject(text)
                    if (json.optBoolean("success")) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSuccess(json.optJSONObject("data") ?: JSONObject())
                        }
                    } else {
                        postError(onError, json.optString("error", "请求失败"))
                    }
                } else if (statusCode == 401 && !retried) {
                    // Token 失效 → 静默重登后重试一次
                    silentRelogin { ok, err ->
                        if (ok) authedRequest(method, urlStr, body, onSuccess, onError, retried = true)
                        else postError(onError, err)
                    }
                } else {
                    val errMsg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                    postError(onError, errMsg.ifEmpty { "HTTP $statusCode" })
                }
                conn.disconnect()
            } catch (e: Exception) {
                postError(onError, e.localizedMessage ?: "网络请求失败")
            }
        }
    }

    private fun postError(onError: (String) -> Unit, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onError(msg) }
    }

    /** 打开 HTTP 连接并写入请求（供本对象各方法复用） */
    private fun openConnection(
        method: String, urlStr: String, body: JSONObject?, token: String?
    ): HttpURLConnection {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        if (!token.isNullOrEmpty()) conn.setRequestProperty("X-Auth-Token", token)
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        if (body != null) {
            conn.doOutput = true
            val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
            writer.write(body.toString())
            writer.flush()
            writer.close()
        }
        return conn
    }

    // ════════════════════════════════════════
    //  老人端：风险事件上报
    // ════════════════════════════════════════

    fun reportRiskEvent(
        elderId: Int = GuardConfig.elderId,
        eventType: String,
        severity: String,
        details: JSONObject,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        dbHelper?.insertEvent(elderId, eventType, severity, details)

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

    // ════════════════════════════════════════
    //  老人端：绑定码与服务器同步
    //  newCode 为 null 时仅拉取服务器当前绑定码；否则把新码刷到服务器
    //  成功回调返回服务器确认后的绑定码
    // ════════════════════════════════════════

    fun syncElderBindCode(
        newCode: String? = null,
        elderId: Int = GuardConfig.elderId,
        onSuccess: ((serverCode: String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("elderId", elderId)
            if (newCode != null) put("bindCode", newCode)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/elder-bind-code", body, null,
            onSuccess = { res ->
                val code = res.optString("bindCode", "")
                if (code.isNotEmpty()) onSuccess?.invoke(code)
                else onError?.invoke("服务器返回数据异常")
            },
            onError = { err -> onError?.invoke(err) })
    }
}
