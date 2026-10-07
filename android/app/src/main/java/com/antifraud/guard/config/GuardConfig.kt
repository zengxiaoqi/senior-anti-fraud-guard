package com.antifraud.guard.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object GuardConfig {
    private const val PREF_NAME      = "guard_config"
    private const val KEY_ELDER_ID   = "elder_id"
    private const val KEY_ELDER_NAME = "elder_name"
    private const val KEY_ELDER_PHONE = "elder_phone"
    private const val KEY_ELDER_ACTIVATED = "elder_activated"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_BIND_CODE  = "bind_code"
    private const val KEY_BOUND_FAMILY_NAME = "bound_family_name"
    private const val KEY_BOUND_FAMILY_PHONE = "bound_family_phone"
    private const val KEY_CALL_THRESHOLD_MIN = "call_threshold_min"
    private const val KEY_PAYMENT_THRESHOLD  = "payment_threshold"
    private const val KEY_GUARD_ENABLED      = "guard_enabled"
    private const val KEY_REC_MAX_SEGMENTS   = "rec_max_segments"
    private const val KEY_REC_UPLOAD_RETRY   = "rec_upload_retry"

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

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

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
     * 单次连续录音最多录几段。
     * 每段 10 分钟，默认 3 段 = 最多 30 分钟。
     *
     * 为什么要有上限：老人可能按了 SOS 后忘了这回事，或者进了围栏迟迟不走。
     * 没有上限的话服务会一直录下去，把老人手机存储和服务器磁盘同时撑爆，
     * 也会让"什么都是证据"失去意义 —— 长时间的录音反而更难被采信。
     * 取值范围限制在 1..6 段（10~60 分钟），设置页可调。
     */
    var recordingMaxSegments: Int
        get() = prefs.getInt(KEY_REC_MAX_SEGMENTS, 3).coerceIn(1, 6)
        set(value) = prefs.edit().putInt(KEY_REC_MAX_SEGMENTS, value.coerceIn(1, 6)).apply()

    /** 录音最长总时长（分钟），由段数换算，供界面直接展示 */
    val recordingMaxMinutes: Int
        get() = recordingMaxSegments * 10

    /** 是否开启"录完自动上传"（关闭后仅本机留存，需子女端无法收听，故默认开启） */
    var recordingAutoUpload: Boolean
        get() = prefs.getBoolean(KEY_REC_UPLOAD_RETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_REC_UPLOAD_RETRY, value).apply()

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
        put("recordingAutoUpload", recordingAutoUpload)
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
        if (obj.has("recordingAutoUpload") && !obj.isNull("recordingAutoUpload")) {
            when (val v = obj.opt("recordingAutoUpload")) {
                is Boolean -> { recordingAutoUpload = v; applied += "自动上传" }
                is Number -> { recordingAutoUpload = v.toInt() != 0; applied += "自动上传" }
                is String -> if (v == "true" || v == "1") {
                    recordingAutoUpload = true; applied += "自动上传"
                }
            }
        }
        return applied
    }
}
