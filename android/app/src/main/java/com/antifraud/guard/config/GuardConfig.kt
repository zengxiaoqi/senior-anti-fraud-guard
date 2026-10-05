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

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
