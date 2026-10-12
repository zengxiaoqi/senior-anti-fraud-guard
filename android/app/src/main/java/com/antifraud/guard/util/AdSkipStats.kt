package com.antifraud.guard.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开屏广告拦截的本机计数（不进服务端）。
 *
 * 为什么只存本机：这是给老人看的「今天帮你挡了多少次」的正反馈，
 * 不是守护证据，不需要跨端同步；随 guard_settings 上行会把统计写进配置管道，
 * 让 sanitize/白名单多背一份不相干的职责。
 */
object AdSkipStats {

    private const val PREF = "ad_skip_stats"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val KEY_LAST_TEXT = "last_text"
    private const val KEY_LAST_MS = "last_ms"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** 今日拦截次数（跨天自动归零，不需要定时任务） */
    fun todayCount(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_DATE, "") == today()) p.getInt(KEY_COUNT, 0) else 0
    }

    /** 记一次拦截。同时留下最近一次的应用、文案与时间戳，供设置页展示与自检页判定 */
    fun recordSkip(context: Context, pkg: String, text: String) {
        val p = prefs(context)
        val t = today()
        val count = if (p.getString(KEY_DATE, "") == t) p.getInt(KEY_COUNT, 0) + 1 else 1
        p.edit()
            .putString(KEY_DATE, t)
            .putInt(KEY_COUNT, count)
            .putString(KEY_LAST_PKG, pkg)
            .putString(KEY_LAST_TEXT, text)
            .putLong(KEY_LAST_MS, System.currentTimeMillis())
            .apply()
    }

    /** 最近一次拦截的（应用包名, 按钮文案, 时间戳毫秒）；今天没有则 null */
    fun lastSkip(context: Context): Triple<String, String, Long>? {
        val p = prefs(context)
        if (p.getString(KEY_DATE, "") != today()) return null
        val pkg = p.getString(KEY_LAST_PKG, "") ?: ""
        if (pkg.isEmpty()) return null
        return Triple(pkg, p.getString(KEY_LAST_TEXT, "") ?: "", p.getLong(KEY_LAST_MS, 0L))
    }

    /** 仅供自检/测试清零 */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
