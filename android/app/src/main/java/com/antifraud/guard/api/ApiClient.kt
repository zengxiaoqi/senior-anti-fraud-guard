package com.antifraud.guard.api

import android.content.Context
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.db.RiskEventDbHelper
import com.antifraud.guard.util.optStringOrEmpty
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.net.URL
import kotlin.concurrent.thread

object ApiClient {
    /**
     * 进程内缓存的完整上报地址（基地址 + /api/events/report）。
     * 未初始化时的默认值。10.0.2.2 是模拟器访问宿主机的别名，真机上不可用，
     * 所以任何依赖真实服务器的调用都必须先经过 init()。
     */
    private var serverUrl = "http://10.0.2.2:3000/api/events/report"
    private var dbHelper: RiskEventDbHelper? = null
    private var initialized = false

    fun init(context: Context) {
        GuardConfig.init(context)
        dbHelper = RiskEventDbHelper(context)
        setServerBaseUrl(GuardConfig.serverUrl)
        initialized = true
    }

    fun setServerBaseUrl(rawUrl: String) {
        val clean = rawUrl.trim().trimEnd('/')
        if (clean.isEmpty()) {
            // 空输入直接忽略：否则会拼出 "http://:3000/api/events/report"
            // 这种非法 URL，所有接口集体报 "no scheme was found"。
            // 保留上一次的有效配置，不要用脏值覆盖。
            android.util.Log.w("ApiClient", "服务器地址为空，忽略本次设置")
            return
        }
        serverUrl = if (clean.startsWith("http://") || clean.startsWith("https://")) {
            "$clean/api/events/report"
        } else {
            "http://$clean:3000/api/events/report"
        }
        GuardConfig.serverUrl = clean
    }

    fun getServerUrl(): String = serverUrl

    /**
     * 拼接一个绝对 URL，并在拼接后**当场校验**。
     *
     * 为什么要有这个：之前 `Request.Builder.url()` 拿到非法字符串时，
     * 异常发生在网络栈内部，报的是 "Expected URL scheme 'http' or 'https'
     * but no scheme was found" —— 这句话完全指不出是哪一行拼错的，
     * 排查成本极高（本次就为此绕了一大圈）。
     *
     * 在这里校验，失败时能直接报出「当前配置的地址是什么」，
     * 让问题在第一现场暴露。
     */
    fun buildUrl(path: String): String {
        val base = getBaseUrl()
        val url = base + path
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalStateException("服务器地址无效（当前配置：\"$base\"），请到设置页重新填写")
        }
        if (base.startsWith("http://10.0.2.2")) {
            // 不直接抛异常，只警告：模拟器上这是正确地址
            android.util.Log.w("ApiClient", "当前使用模拟器默认地址 10.0.2.2，真机上请填写实际服务器地址")
        }
        return url
    }

    /** 探测后端是否可达（不写任何数据）：GET /api/health，2 秒级超时快速失败 */
    fun checkHealth(onSuccess: () -> Unit, onError: (String) -> Unit) {
        thread {
            try {
                val url = URL("${getBaseUrl()}/api/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { onSuccess() }
                } else {
                    postError(onError, "HTTP $code")
                }
            } catch (e: Exception) {
                postError(onError, e.localizedMessage ?: "无法连接服务器")
            }
        }
    }

    /**
     * 老人端免登录 GET（与 /api/events/report 同信任模型），
     * 用于拉取敏感地点围栏等配置。onSuccess 回调完整响应 JSON。
     */
    fun elderGet(path: String, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        requestJson("GET", "${getBaseUrl()}$path", null, null, onSuccess, onError)
    }

    /** 干净的服务端基地址（不带路径），供子女端各接口拼接 */
    fun getBaseUrl(): String =
        serverUrl.removeSuffix("/api/events/report")

    // ════════════════════════════════════════
    //  子女端（App 版）：账号密码登录 + Token 请求
    // ════════════════════════════════════════

    /**
     * 子女端账号密码注册
     * phone 为真实手机号（11 位），服务端校验唯一性后存入 mobile 字段
     * 成功回调 data：{ userId, bindCode, mobile, boundUser, token }
     */
    fun familyRegister(
        username: String, password: String, name: String, phone: String,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            if (name.isNotEmpty()) put("name", name)
            put("phone", phone)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/register", body, null,
            onSuccess = onSuccess, onError = onError)
    }

    /**
     * 完善/修改本机子女端账号的手机号。
     * 用于历史账号（微信登录建号、早期注册）补录真实号码 —— 缺号时
     * 老人端紧急警报的一键拨号会退化成空拨号盘，等于功能失效。
     */
    fun familyUpdateMobile(
        phone: String,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit
    ) {
        val body = JSONObject().apply { put("phone", phone) }
        familyPost("/api/auth/profile-mobile", body,
            onSuccess = {
                GuardConfig.familyMobile = phone
                GuardConfig.familyMobileMissing = false
                onSuccess(it)
            },
            onError = onError)
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
                    // 真实手机号及是否缺失（缺失会让老人端紧急警报无法一键拨号）
                    // 用安全取值：登录响应的 mobile 可能是 JSON null，optString 会读成 "null"
                    GuardConfig.familyMobile = data.optStringOrEmpty("mobile")
                    GuardConfig.familyMobileMissing = GuardConfig.familyMobile.isEmpty()
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
     * onSuccess 回调完整响应 JSON（success/data 等顶层字段都在）
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
                        // ✅ 修复：回调返回完整响应 JSON（含 success/data/error 等顶层字段）。
                        // 原来用 optJSONObject("data") 解包，当 data 是数组（事件列表/轨迹）时
                        // 会得到空对象，导致子女端轨迹、告警列表永远显示为空。
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSuccess(json)
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
    //  老人端：账号激活（首次注册 / 找回 / 改资料）
    //  服务端 POST /api/auth/elder-register
    //  成功回调 res：{ success, elderId, bindCode, name, phone }
    // ════════════════════════════════════════
    //  老人端：录音分片上传
    // ════════════════════════════════════════

    /**
     * 上传一段录音到服务端（multipart/form-data）。
     *
     * 用 OkHttp 而非 HttpURLConnection：需要流式写 10MB 级别的文件，
     * 手写 multipart 分片很啰嗦，OkHttp 的 MultipartBody 干净且省内存。
     *
     * @return 永不返回 null：失败时返回 success=false + 具体原因，
     *         由调用方按 permanent 决定是否继续重试（原因必须可读，
     *         否则界面只能显示「网络不可达」，排查时无法区分超时与连不上）
     */
    fun uploadRecording(task: com.antifraud.guard.db.UploadTask): RecordingUploadResult {
        val file = java.io.File(task.filePath)
        if (!file.exists()) {
            return RecordingUploadResult(
                success = false,
                error = "本地录音文件不存在",
                permanent = true,
                recordingId = 0
            )
        }

        // 配置前置检查：还没 init 过，或当前仍是模拟器默认地址。
        // 这两种情况都会导致 URL 无效/不可达，直接说清楚，别丢给网络层报错。
        if (!initialized) {
            android.util.Log.e("ApiClient", "uploadRecording 在 ApiClient.init() 之前被调用")
            return RecordingUploadResult(
                success = false,
                error = "客户端未初始化，请重新打开 App",
                permanent = false,
                recordingId = 0
            )
        }

        return try {
            // 超时设定直接决定「7MB 录音能不能传上去」。
            //
            // 实测（cpolar 隧道，电脑端上行）：7MB 需 58s，约 127KB/s。
            // 老人手机在 4G/信号差/WiFi 边缘时上行常只有 15~60KB/s，
            // 7MB 要 120~490 秒 —— 原来的 writeTimeout=120s 必然击穿，
            // 抛 SocketTimeoutException 被 catch 成 null，界面显示「网络不可达」，
            // 而服务端一条请求日志都没有（连接建了，请求体没传完）。
            //
            // 所以：写入超时必须按「最慢合理上行 × 文件大小」给足余量，
            // 不能用常规接口那种几十秒的量级。
            val client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                // 10 分钟录音约 6.87MB。按 10KB/s 的极差上行算需要 11 分钟，
                // 这里给 10 分钟上限；超时的任务仍留在队列里等下次退避重试。
                .writeTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", task.fileName,
                    RequestBody.create(
                        // 按扩展名取真实 mime。
                        // 注意：这里原来写的是 task.filePath.toMediaTypeOrNull()，
                        // 那是把「文件路径」当 mime 解析，MediaType.parse 必然抛
                        // IllegalArgumentException，靠 ?: 回落 audio/mp4 才没出事
                        // —— 但 mimetype 决定了服务端 multer 的 fileFilter 判定，
                        // 一旦回落失效就会整段被拒（400「只接受音频文件」）。
                        audioMimeOf(task.fileName),
                        file
                    )
                )
                .addFormDataPart("elderId", task.elderId.toString())
                .addFormDataPart("sessionId", task.sessionId)
                .addFormDataPart("segmentIndex", task.segmentIndex.toString())
                .addFormDataPart("reason", task.reason)
                .addFormDataPart("durationMs", task.durationMs.toString())
                .addFormDataPart("recordedAt", utcFormatter.format(java.util.Date(task.recordedAt)))
                .addFormDataPart("sha256", task.sha256)
                .apply {
                    if (task.placeName.isNotEmpty()) addFormDataPart("placeName", task.placeName)
                    if (task.latitude != null && task.longitude != null) {
                        addFormDataPart("latitude", task.latitude.toString())
                        addFormDataPart("longitude", task.longitude.toString())
                    }
                    if (task.address.isNotEmpty()) addFormDataPart("address", task.address)
                }
                .build()

            val request = Request.Builder()
                .url(buildUrl("/api/recordings/upload"))
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(text)
                    val data = json.optJSONObject("data")
                    RecordingUploadResult(
                        success = true,
                        error = "",
                        permanent = false,
                        recordingId = data?.optInt("id", 0) ?: 0
                    )
                } else {
                    val errMsg = try {
                        JSONObject(text).optString("error")
                    } catch (e: Exception) { "" }
                    RecordingUploadResult(
                        success = false,
                        error = errMsg.ifEmpty { "HTTP ${response.code}" },
                        // 4xx 多半是文件本身有问题（超限/格式不对），重试无意义
                        permanent = response.code in 400..499,
                        recordingId = 0
                    )
                }
            }
        } catch (e: Exception) {
            // 交给队列退避重试，但必须把真实原因带出去。
            // 以前一律吞成「网络不可达」，导致「超时」和「连不上」无法区分，
            // 排查时只能靠猜 —— 现在按异常类型给出可执行的提示。
            android.util.Log.w("ApiClient", "录音上传失败（将重试）: ${e.message}", e)
            return RecordingUploadResult(
                success = false,
                error = describeUploadFailure(e, task),
                permanent = false,
                recordingId = 0
            )
        }
    }

    /** 按文件扩展名推断 mime（MediaRecorder 出的是 MPEG_4 容器，扩展名 .m4a） */
    private fun audioMimeOf(fileName: String): okhttp3.MediaType {
        val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "amr" -> "audio/amr"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "3gp" -> "audio/3gpp"
            else -> "audio/mp4"
        }
        return mime.toMediaTypeOrNull() ?: "audio/mp4".toMediaTypeOrNull()!!
    }

    /**
     * 把底层异常翻译成老人/子女看得懂、且能据以行动的原因。
     * 分三类：连不上（服务器没地址）、超时（网慢/文件大）、其余。
     */
    private fun describeUploadFailure(e: Exception, task: com.antifraud.guard.db.UploadTask): String {
        val name = e.javaClass.simpleName
        val mb = (task.sizeBytes / 1024 / 1024).coerceAtLeast(1)
        return when {
            // 地址本身非法：这是配置问题，不是网络问题，必须直说
            name.contains("IllegalStateException") || name.contains("IllegalArgumentException") ->
                e.localizedMessage ?: "服务器地址无效，请到设置页重新填写"
            name.contains("SocketTimeout") || name.contains("Timeout") ->
                "上传超时（${mb}MB 网速太慢，已排队重试）"
            name.contains("UnknownHost") ->
                "域名解析失败，请检查服务器地址"
            name.contains("ConnectException") || name.contains("NoRoute") ->
                "连不上服务器，请检查地址和网络"
            name.contains("SSL") || name.contains("Certificate") ->
                "HTTPS 证书校验失败"
            e.message?.contains("CLEARTEXT") == true ->
                "服务器地址是 http，但 App 不允许明文传输"
            e.message?.contains("scheme") == true ->
                "服务器地址格式不对，请到设置页重新填写"
            else -> "上传失败：${e.localizedMessage?.take(60) ?: name}"
        }
    }

    /** 上传录音时的 UTC 时间格式（服务端按 ISO 解析） */
    private val utcFormatter: java.text.SimpleDateFormat =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

    // ════════════════════════════════════════

    /**
     * 老人端账号激活 / 修改资料。
     *
     * elderId 有效 → 服务端按"就地改资料"处理（改名/改号都不动绑定关系与历史记录）。
     * elderId = 0 → 服务端按"新激活"处理；若同时带了 previousPhone，
     *   服务端会用旧号定位到原账号并把号换过去，而不是新建一个孤儿账号。
     *
     * previousPhone 默认取本机记录的上一次号码，这正是"老人换了手机号又重装"
     * 时唯一能证明"我还是原来那个人"的线索，不能省。
     */
    fun elderRegister(
        name: String, phone: String,
        elderId: Int = if (GuardConfig.elderActivated) GuardConfig.elderId else 0,
        previousPhone: String = GuardConfig.elderPhone,
        onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("elderId", elderId)
            put("name", name)
            put("phone", phone)
            // 与新号相同就不用带了，省得服务端做无意义的比对
            if (previousPhone.isNotEmpty() && previousPhone != phone) put("previousPhone", previousPhone)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/elder-register", body, null,
            onSuccess = { res ->
                if (res.optBoolean("success")) onSuccess(res)
                else onError(res.optString("error", "激活失败"))
            },
            onError = onError)
    }

    // ════════════════════════════════════════
    //  老人端：绑定码与服务器同步
    //  newCode 为 null 时仅拉取服务器当前绑定码；否则把新码刷到服务器
    //  成功回调返回服务器确认后的绑定码
    // ════════════════════════════════════════

    fun syncElderBindCode(
        newCode: String? = null,
        elderId: Int = GuardConfig.elderId,
        onSuccess: ((res: JSONObject) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val body = JSONObject().apply {
            put("elderId", elderId)
            if (newCode != null) put("bindCode", newCode)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/elder-bind-code", body, null,
            onSuccess = { res ->
                val code = res.optString("bindCode", "")
                if (code.isNotEmpty()) onSuccess?.invoke(res)
                else onError?.invoke("服务器返回数据异常")
            },
            onError = { err -> onError?.invoke(err) })
    }

    // ════════════════════════════════════════
    //  老人端：防护规则配置云端同步
    //  以前这些配置只存本机，换手机就静默回到默认值；现在以服务器为副本，
    //  换机登记时随账号一起恢复，界面上也要明确告诉用户"已从云端恢复"。
    // ════════════════════════════════════════

    /**
     * 拉取该老人账号在云端的防护规则配置。
     * 服务端没有配置时回调 null（不是空对象），客户端据此区分"没配过"与"配过但是空"。
     */
    fun fetchElderSettings(
        elderId: Int = GuardConfig.elderId,
        onSuccess: (JSONObject?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (elderId <= 0) {
            onSuccess(null)
            return
        }
        requestJson("GET", "${getBaseUrl()}/api/auth/elder-settings?elderId=$elderId", null, null,
            onSuccess = { res -> onSuccess(res.optJSONObject("settings")) },
            onError = onError)
    }

    /**
     * 把本机当前的防护规则配置上行到服务器。
     * 成功时回调服务端合并后的最终配置（多端各改各的字段时以它为准）。
     */
    fun pushElderSettings(
        settings: JSONObject = GuardConfig.currentSettingsPayload(),
        elderId: Int = GuardConfig.elderId,
        onSuccess: (JSONObject?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (elderId <= 0) {
            onError("尚未登记老人账号，配置无法同步到云端")
            return
        }
        val body = JSONObject().apply {
            put("elderId", elderId)
            put("settings", settings)
        }
        requestJson("POST", "${getBaseUrl()}/api/auth/elder-settings", body, null,
            onSuccess = { res -> onSuccess(res.optJSONObject("settings")) },
            onError = onError)
    }
}

/** 录音上传结果：permanent=true 表示服务端明确拒绝，重试也不会成功 */
data class RecordingUploadResult(
    val success: Boolean,
    val error: String,
    val permanent: Boolean,
    val recordingId: Int
)
