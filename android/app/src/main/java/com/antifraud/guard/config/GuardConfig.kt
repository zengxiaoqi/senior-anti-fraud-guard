package com.antifraud.guard.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object GuardConfig {
    private const val PREF_NAME      = "guard_config"

    /** 单段录音时长的默认值（分钟）。改这一处即可整体调整出厂默认粒度，已装机设备以本地/云端配置为准 */
    const val DEFAULT_REC_SEGMENT_MINUTES = 5
    private const val KEY_ELDER_ID   = "elder_id"
    private const val KEY_ELDER_NAME = "elder_name"
    private const val KEY_ELDER_PHONE = "elder_phone"
    private const val KEY_ELDER_ACTIVATED = "elder_activated"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_SERVER_URL_MIGRATED = "server_url_migrated_to_fixed_host"
    private const val KEY_BIND_CODE  = "bind_code"
    private const val KEY_BOUND_FAMILY_NAME = "bound_family_name"
    private const val KEY_BOUND_FAMILY_PHONE = "bound_family_phone"
    private const val KEY_CALL_THRESHOLD_MIN = "call_threshold_min"
    private const val KEY_PAYMENT_THRESHOLD  = "payment_threshold"
    private const val KEY_GUARD_ENABLED      = "guard_enabled"
    private const val KEY_REC_MAX_SEGMENTS   = "rec_max_segments"
    private const val KEY_REC_SEGMENT_MINUTES = "rec_segment_minutes"
    private const val KEY_REC_UPLOAD_RETRY   = "rec_upload_retry"

    // ── Phase 1 · 通话行为判定与位置阈值（1-8/1-9/1-10 + 信任列表）──
    private const val KEY_TRUSTED_NUMBERS   = "trusted_call_numbers"       // JSON 数组字符串
    private const val KEY_HOME_AWAY_RADIUS  = "home_away_radius_meters"    // 原硬编码 500
    private const val KEY_STAY_MOVE_METERS  = "stay_move_meters"           // 原硬编码 100
    private const val KEY_HOME_STAY_MINUTES = "home_stay_minutes"          // 原硬编码 40
    private const val KEY_HOME_LAT          = "home_lat"                   // 1-9 子女端显式设家
    private const val KEY_HOME_LNG          = "home_lng"

    // ── 角色与子女端（App 版）会话 ──
    private const val KEY_APP_ROLE           = "app_role"          // "" 未选择 / "elder" / "family"
    private const val KEY_FAMILY_USERNAME    = "family_username"
    private const val KEY_FAMILY_PASSWORD    = "family_password"   // 用于 401 静默重登
    private const val KEY_FAMILY_TOKEN       = "family_token"
    private const val KEY_FAMILY_USER_ID     = "family_user_id"
    private const val KEY_FAMILY_BIND_CODE   = "family_bind_code"
    private const val KEY_BOUND_ELDER_ID     = "bound_elder_id"    // 0 = 未绑定
    private const val KEY_BOUND_ELDER_NAME   = "bound_elder_name"
    private const val KEY_FAMILY_MOBILE      = "family_mobile"
    private const val KEY_FAMILY_MOBILE_MISSING = "family_mobile_missing"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        migrateServerUrlOnce()
    }

    /**
     * 云端固定域名，服务器地址的**唯一默认值**。
     *
     * 2026-10-08 起后端走 Cloudflare named tunnel，域名重启不变，
     * 所以可以写死；对应服务端记录见 `scripts/cloudflare/hostname.txt`。
     * 改域名时只改这一处（外加 `set-domain.js` 写的 `config.js`）。
     */
    const val DEFAULT_SERVER_BASE_URL = "https://guard.chataifree.eu.org"

    /**
     * 旧安装的一次性迁移。
     *
     * 只改默认值是不够的：已经装过 App 的设备把地址写进了 SharedPreferences，
     * 新默认值对它们根本不生效，仍会连向早已失效的旧地址（改起来还得重装清数据）。
     * 这里识别「历史遗留地址」并统一迁到固定域名，**用户手填的自建服务器地址一律保留**。
     */
    private fun migrateServerUrlOnce() {
        if (prefs.getBoolean(KEY_SERVER_URL_MIGRATED, false)) return
        val stored = prefs.getString(KEY_SERVER_URL, null)
        if (stored == null || isLegacyServerUrl(stored)) {
            prefs.edit()
                .putString(KEY_SERVER_URL, DEFAULT_SERVER_BASE_URL)
                .putBoolean(KEY_SERVER_URL_MIGRATED, true)
                .apply()
        } else {
            prefs.edit().putBoolean(KEY_SERVER_URL_MIGRATED, true).apply()
        }
    }

    /** 历史遗留地址：模拟器默认、以及已停用的 cpolar 随机域名 */
    private fun isLegacyServerUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.isEmpty()) return true
        if (u.startsWith("http://10.0.2.2") || u.startsWith("http://127.0.0.1")) return true
        if (u.contains(".cpolar.")) return true
        return false
    }

    /** 当前 App 角色："" 未选择 / "elder" 老人端 / "family" 子女端 */
    var appRole: String
        get() = prefs.getString(KEY_APP_ROLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_ROLE, value).apply()

    /** 子女端登录用户名（同时作为服务端 users.phone 唯一标识） */
    var familyUsername: String
        get() = prefs.getString(KEY_FAMILY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FAMILY_USERNAME, value).apply()

    /** 子女端登录密码（仅本机保存，用于 Token 过期 401 时静默重登） */
    var familyPassword: String
        get() = prefs.getString(KEY_FAMILY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FAMILY_PASSWORD, value).apply()

    /** 子女端服务端签发的登录态 Token（X-Auth-Token 头携带） */
    var familyToken: String
        get() = prefs.getString(KEY_FAMILY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FAMILY_TOKEN, value).apply()

    /** 子女端在服务端的用户 ID */
    var familyUserId: Int
        get() = prefs.getInt(KEY_FAMILY_USER_ID, 0)
        set(value) = prefs.edit().putInt(KEY_FAMILY_USER_ID, value).apply()

    /** 子女端自己的绑定码（可被老人端绑定用，本 App 场景主要用不到） */
    var familyBindCode: String
        get() = prefs.getString(KEY_FAMILY_BIND_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FAMILY_BIND_CODE, value).apply()

    /** 已绑定老人的服务端用户 ID，0 表示未绑定 */
    var boundElderId: Int
        get() = prefs.getInt(KEY_BOUND_ELDER_ID, 0)
        set(value) = prefs.edit().putInt(KEY_BOUND_ELDER_ID, value).apply()

    /** 已绑定老人的姓名 */
    var boundElderName: String
        get() = prefs.getString(KEY_BOUND_ELDER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOUND_ELDER_NAME, value).apply()

    /** 子女端是否已绑定老人 */
    val isFamilyBound: Boolean get() = boundElderId > 0

    /** 本机登录的子女端账号的真实手机号（注册时必填） */
    var familyMobile: String
        get() = prefs.getString(KEY_FAMILY_MOBILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FAMILY_MOBILE, value).apply()

    /**
     * 是否还没完善手机号（微信登录建号 / 早期版本注册的历史账号）。
     * 缺号时老人端紧急警报无法一键拨给子女，控制台需要主动提示补录。
     */
    var familyMobileMissing: Boolean
        get() = prefs.getBoolean(KEY_FAMILY_MOBILE_MISSING, false)
        set(value) = prefs.edit().putBoolean(KEY_FAMILY_MOBILE_MISSING, value).apply()

    /** 退出子女端登录：清空会话，保留用户名方便下次登录 */
    fun clearFamilySession() {
        prefs.edit()
            .putString(KEY_FAMILY_TOKEN, "")
            .putInt(KEY_FAMILY_USER_ID, 0)
            .putString(KEY_FAMILY_BIND_CODE, "")
            .putInt(KEY_BOUND_ELDER_ID, 0)
            .putString(KEY_BOUND_ELDER_NAME, "")
            .apply()
    }

    /**
     * 退出老人端登录。
     *
     * 只清"本机身份"，不动服务器上的账号 —— 服务器的 elder 记录、绑定关系、
     * 历史录音证据都要留着，重新登记时凭手机号就能找回原账号。
     *
     * elderId 置 0 而不是保留：它是所有上报（风险事件/录音/位置）的归属键，
     * 清会话后若还有后台服务在跑，会把数据挂到错误的默认 id 上（脏数据）。
     * 所以调用方必须先停掉所有守护服务再调这里。
     *
     * 刻意保留 KEY_ELDER_PHONE：下次登记界面用它预填，
     * 也让服务端能用它做换号找回（见 routes/auth.js 的 previousPhone）。
     */
    fun clearElderSession() {
        prefs.edit()
            .putInt(KEY_ELDER_ID, 0)
            .putBoolean(KEY_ELDER_ACTIVATED, false)
            .putString(KEY_ELDER_NAME, "")
            .putString(KEY_BIND_CODE, "")
            .putString(KEY_BOUND_FAMILY_NAME, "")
            .putString(KEY_BOUND_FAMILY_PHONE, "")
            .apply()
    }

    /**
     * 老人端在服务端的用户 ID。
     * 默认 0 = 未登记。历史版本默认值是 1，那会让"从未登记过的设备"
     * 把风险事件挂到服务器上真实存在的 id=1 账号上（大概率是别人的），
     * 造成脏数据；一律用 0，由 elderActivated 判断是否真的可用。
     */
    var elderId: Int
        get() = prefs.getInt(KEY_ELDER_ID, 0)
        set(value) = prefs.edit().putInt(KEY_ELDER_ID, value).apply()

    var elderName: String
        get() = prefs.getString(KEY_ELDER_NAME, "默认账号") ?: "默认账号"
        set(value) = prefs.edit().putString(KEY_ELDER_NAME, value).apply()

    /** 老人本机登记的手机号（ elder-register 提交） */
    var elderPhone: String
        get() = prefs.getString(KEY_ELDER_PHONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELDER_PHONE, value).apply()

    /** 是否已完成老人账号激活（姓名+手机号注册到服务器），未激活时 elderId 仅是默认值 */
    var elderActivated: Boolean
        get() = prefs.getBoolean(KEY_ELDER_ACTIVATED, false)
        set(value) = prefs.edit().putBoolean(KEY_ELDER_ACTIVATED, value).apply()

    /** 服务器基地址。默认值取 [DEFAULT_SERVER_BASE_URL]（云端固定域名，不带尾斜杠） */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_BASE_URL) ?: DEFAULT_SERVER_BASE_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    /** 6 位亲情绑定码 */
    var bindCode: String
        get() = prefs.getString(KEY_BIND_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BIND_CODE, value).apply()

    /** 已绑定的子女姓名 */
    var boundFamilyName: String
        get() = prefs.getString(KEY_BOUND_FAMILY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOUND_FAMILY_NAME, value).apply()

    /** 已绑定的子女手机号（由服务器 elder-bind-code 接口下发） */
    var boundFamilyPhone: String
        get() = prefs.getString(KEY_BOUND_FAMILY_PHONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOUND_FAMILY_PHONE, value).apply()

    /** 陌生通话时长预警阈值（分钟），默认 15 */
    var callThresholdMinutes: Int
        get() = prefs.getInt(KEY_CALL_THRESHOLD_MIN, 15)
        set(value) = prefs.edit().putInt(KEY_CALL_THRESHOLD_MIN, value).apply()

    /** 大额支付预警阈值（元），默认 500 */
    var paymentThreshold: Double
        get() = prefs.getFloat(KEY_PAYMENT_THRESHOLD, 500f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_PAYMENT_THRESHOLD, value.toFloat()).apply()

    /**
     * 守护总开关的用户意图（持久化）。
     * 默认 true —— 全天候防诈守护对老人端属于「默认开启、主动关闭」，
     * 不因服务被系统回收 / 重新进入 App 而退回关闭状态。
     * 界面显示的「服务是否在跑」是另一回事，由 MainActivity 读运行态展示。
     */
    var guardEnabled: Boolean
        get() = prefs.getBoolean(KEY_GUARD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_GUARD_ENABLED, value).apply()

    /**
     * 单次连续录音最多录几段，默认 3 段。
     * 每段多长由 [recordingSegmentMinutes] 决定，两者相乘才是总时长上限。
     *
     * 为什么要有上限：老人可能按了 SOS 后忘了这回事，或者进了围栏迟迟不走。
     * 没有上限的话服务会一直录下去，把老人手机存储和服务器磁盘同时撑爆，
     * 也会让"什么都是证据"失去意义 —— 长时间的录音反而更难被采信。
     * 取值范围限制在 1..6 段，设置页可调。
     */
    var recordingMaxSegments: Int
        get() = prefs.getInt(KEY_REC_MAX_SEGMENTS, 3).coerceIn(1, 6)
        set(value) = prefs.edit().putInt(KEY_REC_MAX_SEGMENTS, value.coerceIn(1, 6)).apply()

    /**
     * 单段录音的时长上限（分钟），默认 5 分钟，可调 1~10 分钟。
     *
     * 从 10 分钟下调到 5 分钟的原因：经 Cloudflare 隧道实测，单段 10 分钟录音
     * （约 7MB）的上传时间在 29s~95s 之间大幅波动，最慢的几次已逼近云厂商
     * 边缘约 100s 的请求时限 —— 一旦被掐断，这段证据就丢了。
     * 单段减半可以把最坏情况压到一半左右，同时让"边录边传"更快给付到子女手里。
     *
     * 代价是同样时长会产生更多分段（文件数翻倍），但每段更小也更容易重试成功。
     * 老用户升级后若希望恢复原来的粗粒度，在设置页把这项改回 10 即可。
     */
    var recordingSegmentMinutes: Int
        get() = prefs.getInt(KEY_REC_SEGMENT_MINUTES, DEFAULT_REC_SEGMENT_MINUTES).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_REC_SEGMENT_MINUTES, value.coerceIn(1, 10)).apply()

    /** 录音最长总时长（分钟），由段数 × 每段时长换算，供界面直接展示 */
    val recordingMaxMinutes: Int
        get() = recordingMaxSegments * recordingSegmentMinutes

    /** 是否开启"录完自动上传"（关闭后仅本机留存，需子女端无法收听，故默认开启） */
    var recordingAutoUpload: Boolean
        get() = prefs.getBoolean(KEY_REC_UPLOAD_RETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_REC_UPLOAD_RETRY, value).apply()

    // ──────────────────────────────────────────
    //  Phase 1：信任来电 + 位置阈值 + 家基准
    // ──────────────────────────────────────────

    /**
     * 信任来电号码（JSON 数组字符串）。
     * 刻意不用 StringSet：SharedPreferences 的 StringSet 返回内部引用，
     * 改完必须整体替换才落盘，是"存了但没存上"的经典陷阱。存 JSON 字符串最稳。
     */
    var trustedCallNumbersJson: String
        get() = prefs.getString(KEY_TRUSTED_NUMBERS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_TRUSTED_NUMBERS, value).apply()

    /** 是否信任来电号码（取后 8 位数字比对，消掉 +86/空格/横线的格式差异） */
    fun isTrustedCallNumber(raw: String): Boolean {
        val tail = raw.filter { it.isDigit() }.takeLast(8)
        if (tail.length < 7) return false
        return try {
            val arr = org.json.JSONArray(trustedCallNumbersJson)
            (0 until arr.length()).any {
                arr.optString(it).filter { ch -> ch.isDigit() }.takeLast(8) == tail
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 加入信任列表（按后 8 位去重）。UI 入口后置，本版本先落数据层与云端同步 */
    fun addTrustedCallNumber(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 7) return false
        if (isTrustedCallNumber(digits)) return true
        return try {
            val arr = org.json.JSONArray(trustedCallNumbersJson)
            arr.put(digits)
            trustedCallNumbersJson = arr.toString()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 从信任列表移除 */
    fun removeTrustedCallNumber(raw: String): Boolean {
        val tail = raw.filter { it.isDigit() }.takeLast(8)
        return try {
            val arr = org.json.JSONArray(trustedCallNumbersJson)
            val keep = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val d = arr.optString(i).filter { it.isDigit() }
                if (d.takeLast(8) != tail) keep.put(d)
            }
            trustedCallNumbersJson = keep.toString()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 离家判定半径（米）。原硬编码 500，现在走本机+云端双向同步（1-10） */
    var homeAwayRadiusMeters: Int
        get() = prefs.getInt(KEY_HOME_AWAY_RADIUS, 500).coerceIn(100, 5000)
        set(value) = prefs.edit().putInt(KEY_HOME_AWAY_RADIUS, value.coerceIn(100, 5000)).apply()

    /** 停留判定移动阈值（米）：移动小于该值视为原地停留。原硬编码 100（1-10） */
    var stayMoveMeters: Int
        get() = prefs.getInt(KEY_STAY_MOVE_METERS, 100).coerceIn(20, 1000)
        set(value) = prefs.edit().putInt(KEY_STAY_MOVE_METERS, value.coerceIn(20, 1000)).apply()

    /** 陌生地点停留告警阈值（分钟）。原硬编码 40（1-10） */
    var homeStayMinutes: Int
        get() = prefs.getInt(KEY_HOME_STAY_MINUTES, 40).coerceIn(5, 240)
        set(value) = prefs.edit().putInt(KEY_HOME_STAY_MINUTES, value.coerceIn(5, 240)).apply()

    /** 家基准纬度。0 = 未设置（1-9：子女端显式设置优先于"首次定位即家"） */
    var homeLat: Double
        get() = Double.fromBits(prefs.getLong(KEY_HOME_LAT, 0L))
        set(value) = prefs.edit().putLong(KEY_HOME_LAT, value.toRawBits()).apply()

    /** 家基准经度。0 = 未设置 */
    var homeLng: Double
        get() = Double.fromBits(prefs.getLong(KEY_HOME_LNG, 0L))
        set(value) = prefs.edit().putLong(KEY_HOME_LNG, value.toRawBits()).apply()

    /** 是否已显式设置家的基准位置 */
    val hasFamilyHome: Boolean
        get() = homeLat != 0.0 || homeLng != 0.0

    fun getElderNameForId(id: Int): String {
        return prefs.getString("elder_name_$id", "老人账号 $id") ?: "老人账号 $id"
    }

    fun setElderNameForId(id: Int, name: String) {
        prefs.edit().putString("elder_name_$id", name).apply()
    }

    // ──────────────────────────────────────────
    //  防护规则配置的云端副本
    //
    //  通话预警时长、支付预警金额、录音段数以前只写本地，于是"老人换个手机"
    //  会把它们静默打回默认值：老手机上设的"通话超 10 分钟就告警"在新手机上
    //  悄无声息地失效，界面上还一切正常 —— 用户完全无从察觉。
    //  现在每次保存都上行一份到服务器，换机登记时随账号一起恢复。
    // ──────────────────────────────────────────

    /** 本机当前全部防护规则，作为上传载荷 */
    fun currentSettingsPayload(): JSONObject = JSONObject().apply {
        put("callThresholdMinutes", callThresholdMinutes)
        put("paymentThreshold", paymentThreshold)
        put("recordingMaxSegments", recordingMaxSegments)
        put("recordingSegmentMinutes", recordingSegmentMinutes)
        put("recordingAutoUpload", recordingAutoUpload)
        put("homeAwayRadiusMeters", homeAwayRadiusMeters)
        put("stayMoveMeters", stayMoveMeters)
        put("homeStayMinutes", homeStayMinutes)
        put("trustedCallNumbersJson", trustedCallNumbersJson)
        // 家基准只在已设置时上行，避免把 0/0 当有效值推给服务端
        if (hasFamilyHome) {
            put("homeLat", homeLat)
            put("homeLng", homeLng)
        }
    }

    /**
     * 用服务端下发的配置覆盖本机值。
     *
     * 逐项应用而不是整体替换：服务端可能只存了部分字段（比如老版本 App 只上传过
     * 两个阈值），整体替换会把本机剩下的默认值也一起写坏。
     * 非法值（负数、超范围、类型不对）一律忽略继续用本地值 —— 云端配置是"锦上添花"，
     * 不能反过来把正在工作的守护规则搞坏。
     *
     * @return 实际被服务端覆盖的字段名，供界面提示"已从云端恢复 N 项设置"
     */
    fun applySettingsFromServer(obj: JSONObject?): List<String> {
        if (obj == null) return emptyList()
        val applied = mutableListOf<String>()

        obj.optInt("callThresholdMinutes", -1).takeIf { it in 1..240 }?.let {
            callThresholdMinutes = it; applied += "通话预警时长"
        }
        obj.optDouble("paymentThreshold", -1.0).takeIf { it in 1.0..1_000_000.0 }?.let {
            paymentThreshold = it; applied += "支付预警金额"
        }
        obj.optInt("recordingMaxSegments", -1).takeIf { it in 1..6 }?.let {
            recordingMaxSegments = it; applied += "录音段数"
        }
        obj.optInt("recordingSegmentMinutes", -1).takeIf { it in 1..10 }?.let {
            recordingSegmentMinutes = it; applied += "单段录音时长"
        }
        if (obj.has("recordingAutoUpload") && !obj.isNull("recordingAutoUpload")) {
            when (val v = obj.opt("recordingAutoUpload")) {
                is Boolean -> { recordingAutoUpload = v; applied += "自动上传" }
                is Number -> { recordingAutoUpload = v.toInt() != 0; applied += "自动上传" }
                is String -> if (v == "true" || v == "1") {
                    recordingAutoUpload = true; applied += "自动上传"
                }
            }
        }
        // ── Phase 1 新增项 ──
        obj.optInt("homeAwayRadiusMeters", -1).takeIf { it in 100..5000 }?.let {
            homeAwayRadiusMeters = it; applied += "离家半径"
        }
        obj.optInt("stayMoveMeters", -1).takeIf { it in 20..1000 }?.let {
            stayMoveMeters = it; applied += "停留判定半径"
        }
        obj.optInt("homeStayMinutes", -1).takeIf { it in 5..240 }?.let {
            homeStayMinutes = it; applied += "离家停留时长"
        }
        // 家基准坐标：只接受"同时给出且有效"的一对，0/0 视为未设置
        if (obj.has("homeLat") && !obj.isNull("homeLat") &&
            obj.has("homeLng") && !obj.isNull("homeLng")
        ) {
            val lat = obj.optDouble("homeLat", 0.0)
            val lng = obj.optDouble("homeLng", 0.0)
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)) {
                homeLat = lat
                homeLng = lng
                applied += "家的基准位置"
            }
        }
        // 字符串字段注意 optString 遇 JSON null 会返回 "null" 陷阱：先 has/isNull 再取
        if (obj.has("trustedCallNumbersJson") && !obj.isNull("trustedCallNumbersJson")) {
            val s = obj.optString("trustedCallNumbersJson")
            if (s.trim().startsWith("[")) {
                trustedCallNumbersJson = s
                applied += "信任号码"
            }
        }
        // 高危 App 远程规则（1-1 可远程更新）：脏 JSON 由注册表内部兜底
        if (obj.has("highRiskPackages") && !obj.isNull("highRiskPackages")) {
            val s = obj.optString("highRiskPackages")
            if (s.trim().startsWith("[")) {
                com.antifraud.guard.util.HighRiskAppRegistry.applyRemoteConfig(s)
                applied += "高危应用规则"
            }
        }
        return applied
    }
}
