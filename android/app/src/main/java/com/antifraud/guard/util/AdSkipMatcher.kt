package com.antifraud.guard.util

/**
 * 开屏广告「跳过」按钮的识别规则（纯函数，无 Android 依赖，可本地 JVM 单测）。
 *
 * ## 为什么匹配做得这么窄
 * 自动点击是**替用户按真实按钮**，误点的代价不对称：
 *  - 漏点一次开屏广告 = 老人多看 3 秒广告，损失为零；
 *  - 误点一次真实开关（「关闭蓝牙」「跳过登录」以外的破坏性按钮）= 可能直接造成损失。
 * 所以宁可漏，不可错：只认白名单精确文案 + 倒计时变体 + 已知广告 SDK 的控件 ID，
 * 绝不做「包含『关闭』就点」这类宽泛匹配。
 *
 * 「跳过」本身是安全的：全世界的开屏广告跳过键都叫这个名字，
 * 而真实 App 里叫「跳过」的按钮（新手引导、登录页）点了也无害。
 */
object AdSkipMatcher {

    /** 精确文字白名单（归一化后全等匹配） */
    private val EXACT_TEXTS = setOf(
        "跳过", "跳过广告", "跳过片头", "跳过片头广告", "跳过广告页", "跳过此广告",
        "跳过推荐", "点击跳过", "广告关闭", "关闭广告", "关闭开屏广告"
    )

    /** 倒计时变体（归一化去空白后）：「跳过5」「跳过广告 5s」「关闭广告 05 秒」 */
    private val COUNTDOWN = Regex("""^(?:跳过(?:广告)?|关闭广告)\d{1,2}[sS秒]?$""")

    /** 英文开屏跳过按钮（YouTube / 海外 App）：「skip」「Skip Ad」「skip 5s」 */
    private val ENGLISH = Regex("""(?i)^skip(?:ad)?\d{0,2}s?$""")

    /**
     * 控件 ID 特征（[android.view.accessibility.AccessibilityNodeInfo.getViewIdResourceName]
     * 最后一段的模式匹配，大小写不敏感）。覆盖常见广告 SDK 的跳过按钮：
     * tt_skip_iv（穿山甲）、skip_iv、btn_skip、splash_skip、ad_close、close_ad 等。
     *
     * 为什么不用 contains("skip")：那会把 skipper_layout / skipCount 这类
     * 正常控件一起命中。按 `_` 边界切词后，skipper 这种前缀相近但不同的词自然排除。
     */
    private val ID_PATTERNS = listOf(
        Regex("""(^|_)skip(_|$)"""),
        Regex("""(^|_)count_?down_?skip(_|$)"""),
        Regex("""(^|_)(ad_?close|close_?ad)(_|$)""")
    )

    /**
     * 节点文字/内容描述是否是「跳过」按钮。
     *
     * 长度上限 12：超过这个长度的多半是正文里提到"跳过"二字的句子
     * （「跳过这一步将无法享受完整服务」），点它等于替用户做了决定。
     */
    fun matchesText(text: String?): Boolean {
        val t = normalize(text) ?: return false
        if (t.isEmpty() || t.length > 12) return false
        if (EXACT_TEXTS.contains(t)) return true
        if (COUNTDOWN.matches(t)) return true
        if (ENGLISH.matches(t)) return true
        return false
    }

    /** 控件 ID 是否是跳过按钮特征（只比 `pkg:id/` 之后的最后一段） */
    fun matchesViewId(viewId: String?): Boolean {
        val id = viewId?.lowercase() ?: return false
        if (id.isEmpty()) return false
        val segment = id.substringAfterLast('/')
        return ID_PATTERNS.any { it.containsMatchIn(segment) }
    }

    /**
     * 归一化：去掉全部空白 + 去尾标点。
     * 「跳 过」「跳过 」「跳过 >」「跳过 5 s」都能归到可比形态。
     */
    private fun normalize(text: String?): String? {
        if (text == null) return null
        val noWs = text.replace(WHITESPACE, "")
        return noWs.trimEnd('>', '»', '·', '.', '。', '>')
    }

    private val WHITESPACE = Regex("""\s+""")
}
