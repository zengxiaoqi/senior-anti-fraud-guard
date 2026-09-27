package com.antifraud.guard.config

import android.content.Context
import android.content.SharedPreferences

object GuardConfig {
    private const val PREF_NAME = "guard_config"
    private const val KEY_ELDER_ID = "elder_id"
    private const val KEY_ELDER_NAME = "elder_name"
    private const val KEY_SERVER_URL = "server_url"

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

    fun getElderNameForId(id: Int): String {
        return prefs.getString("elder_name_$id", "老人账号 $id") ?: "老人账号 $id"
    }

    fun setElderNameForId(id: Int, name: String) {
        prefs.edit().putString("elder_name_$id", name).apply()
    }
}
