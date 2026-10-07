package com.antifraud.guard.util

import org.json.JSONObject

/**
 * 安全读取 JSON 字符串字段。
 *
 * 为什么不能直接用 org.json 的 optString(name, "")：
 * SQLite 的 NULL 字段序列化到 JSON 是 `null`，而 Android 的 optString()
 * 对 JSON null 返回的是字符串 **"null"**（不是空串，也不是默认值），
 * 于是 `optString("mobile", "").ifEmpty { ... }` 这种兜底完全失效，
 * 界面就会把「老人手机 null」显示出来 —— 曾经真实踩过。
 *
 * optString 在字段不存在时才会用默认值，所以"缺失"和"显式 null"行为不一致，
 * 只能自己统一处理。
 */
fun JSONObject.optStringOrEmpty(name: String): String {
    if (!has(name) || isNull(name)) return ""
    val v = optString(name, "")
    // 兜底防御：某些实现/版本下仍可能吐字符串 "null"
    return if (v == "null" || v == "NULL") "" else v
}

/**
 * 取"真实手机号"：优先 mobile（真实号码），回退 phone（可能是用户名/占位号）。
 *
 * @param isPlaceholder 判断 phone 是否为无效占位（wx_ 前缀是微信登录占位号）
 * @param username 当前登录子女的用户名 —— phone 存的就是它，不能当号码用
 */
fun JSONObject.pickPhone(isPlaceholder: (String) -> Boolean = { it.startsWith("wx_") },
                          username: String = ""): String {
    val mobile = optStringOrEmpty("mobile")
    if (mobile.isNotEmpty() && !isPlaceholder(mobile) && mobile != username) return mobile
    val phone = optStringOrEmpty("phone")
    if (phone.isNotEmpty() && !isPlaceholder(phone) && phone != username) return phone
    return ""
}