package com.antifraud.guard.config

import android.content.Context
import android.content.SharedPreferences

object GuardConfig {
    private const val PREF_NAME      = "guard_config"
    private const val KEY_ELDER_ID   = "elder_id"
    private const val KEY_ELDER_NAME = "elder_name"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_BIND_CODE  = "bind_code"
    private const val KEY_BOUND_FAMILY_NAME = "bound_family_name"
    private const val KEY_CALL_THRESHOLD_MIN = "call_threshold_min"
    private const val KEY_PAYMENT_THRESHOLD  = "payment_threshold"

    // ── 角色与子女端（App 版）会话 ──
    private const val KEY_APP_ROLE           = "app_role"          // "" 未选择 / "elder" / "family"
    private const val KEY_FAMILY_USERNAME    = "family_username"
    private const val KEY_FAMILY_PASSWORD    = "family_password"   // 用于 401 静默重登
    private const val KEY_FAMILY_TOKEN       = "family_token"
    private const val KEY_FAMILY_USER_ID     = "family_user_id"
    private const val KEY_FAMILY_BIND_CODE   = "family_bind_code"
    private const val KEY_BOUND_ELDER_ID     = "bound_elder_id"    // 0 = 未绑定
    private const val KEY_BOUND_ELDER_NAME   = "bound_elder_name"

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

    var elderId: Int
        get() = prefs.getInt(KEY_ELDER_ID, 1)
        set(value) = prefs.edit().putInt(KEY_ELDER_ID, value).apply()

    var elderName: String
        get() = prefs.getString(KEY_ELDER_NAME, "默认账号") ?: "默认账号"
        set(value) = prefs.edit().putString(KEY_ELDER_NAME, value).apply()

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

    /** 陌生通话时长预警阈值（分钟），默认 15 */
    var callThresholdMinutes: Int
        get() = prefs.getInt(KEY_CALL_THRESHOLD_MIN, 15)
        set(value) = prefs.edit().putInt(KEY_CALL_THRESHOLD_MIN, value).apply()

    /** 大额支付预警阈值（元），默认 500 */
    var paymentThreshold: Double
        get() = prefs.getFloat(KEY_PAYMENT_THRESHOLD, 500f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_PAYMENT_THRESHOLD, value.toFloat()).apply()

    fun getElderNameForId(id: Int): String {
        return prefs.getString("elder_name_$id", "老人账号 $id") ?: "老人账号 $id"
    }

    fun setElderNameForId(id: Int, name: String) {
        prefs.edit().putString("elder_name_$id", name).apply()
    }
}
