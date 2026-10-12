package com.antifraud.guard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.antifraud.guard.config.GuardConfig
import com.antifraud.guard.util.AdSkipMatcher
import com.antifraud.guard.util.AdSkipStats

/**
 * 开屏广告拦截服务（李跳跳式）：自动点击其他 App 开屏时的「跳过」按钮。
 *
 * ## 形态：系统绑定服务，不是前台 Service
 * 与 [NotificationPayListenerService] 同构 —— 由用户在系统「无障碍」里授权后
 * 由系统绑定，App 无法 startService 启停。功能开关 [GuardConfig.adSkipEnabled]
 * 只决定"绑定了之后处不处理事件"：关掉时服务空转（第一行就返回，事件洪水不构成开销），
 * 开启时无需任何启动动作，授权了就在。
 *
 * 刻意不挂 ForegroundGuardService：一是无障碍服务的生命周期不归 App 管，
 * 二是它不需要保活链路（系统绑定的服务本就常驻），挂上去只会让"守护总开关"
 * 与"广告拦截"两个语义不同的功能错误地同生共死。
 *
 * ## 三道安全闸
 *  1. **匹配窄**（见 [AdSkipMatcher]）：只认「跳过」白名单/倒计时/已知广告 SDK 控件 ID，
 *     绝不做包含「关闭」就点的宽泛匹配；
 *  2. **尺寸闸**：点击目标面积不得超过屏幕 25%，向上找可点击祖先时祖先面积
 *     不得超过文字节点 8 倍 —— 超过说明那是整个广告容器（点了就跳广告落地页）；
 *  3. **冷却 + 熔断**：同一「包名+文案」1.5 秒内只点一次；10 秒内超过 20 次
 *     自动停 10 秒，防止某个页面的节点树异常导致失控连点。
 *
 * ## 性能
 * TYPE_WINDOW_CONTENT_CHANGED 在动图/倒计时页面是每帧级洪水，所以：
 *  - 只在窗口状态变化后 8 秒内响应内容事件（开屏广告的全部生命周期都在里面）；
 *  - 内容事件另加 200ms 节流；
 *  - 开关关闭时第一行返回。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkip"

        /** 同一个「包名+文案」的点击冷却，防同一广告被重复点开 */
        private const val CLICK_COOLDOWN_MS = 1500L

        /** 内容事件最小处理间隔（事件洪水节流） */
        private const val CONTENT_DEBOUNCE_MS = 200L

        /** 窗口状态变化后，继续响应内容变化的时间窗（倒计时文字在这几秒内滚动） */
        private const val CONTENT_ARMED_WINDOW_MS = 8000L

        /** 全局熔断阈值与冷静期 */
        private const val MAX_CLICKS_PER_10S = 20
        private const val BREAKER_COOLDOWN_MS = 10_000L

        /** 点击目标面积占屏幕比例上限 */
        private const val MAX_NODE_AREA_RATIO = 0.25

        /** 向上找可点击祖先时的面积倍数上限 */
        private const val MAX_ANCESTOR_AREA_RATIO = 8.0

        /** 向上找可点击祖先的最大层数 */
        private const val MAX_ANCESTOR_DEPTH = 4

        /** 手工遍历树时的节点数上限（控件 ID 兜底路径） */
        private const val MAX_TRAVERSE_NODES = 200

        /** 不处理的包：系统 UI（通知栏/锁屏/导航栏）与自身 */
        private val EXCLUDED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings"
        )

        /** 文字检索词：[findAccessibilityNodeInfosByText] 是子串匹配，一词覆盖一族文案 */
        private val TEXT_QUERIES = listOf("跳过", "关闭广告", "关闭开屏广告", "广告关闭", "Skip", "skip")
    }

    /** 最近一次窗口状态变化的时间戳 */
    private var lastStateChangeAt = 0L

    /** 上次处理内容事件的时间戳 */
    private var lastContentCheckAt = 0L

    /** 「包名+文案」→ 上次点击时间戳 */
    private val clickCooldown = HashMap<String, Long>(16)

    /** 最近 10 秒的点击时间戳（熔断判定用） */
    private val recentClicks = ArrayDeque<Long>()

    /** 熔断解除时间戳 */
    private var breakerUntil = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardConfig.init(this)
        Log.i(TAG, "开屏广告拦截服务已连接（是否生效由守护设置开关决定）")
    }

    override fun onInterrupt() {
        // 无语音反馈，无需处理
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 开关关闭时全函数最便宜的一行：无障碍事件量极大，不能等走到解析才返回
        if (!GuardConfig.adSkipEnabled) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // 新窗口出现 = 开屏广告最可能的登场时刻，立即全量检查
                    lastStateChangeAt = System.currentTimeMillis()
                    handleWindow(event.packageName?.toString(), event.className?.toString())
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val now = System.currentTimeMillis()
                    // 倒计时文字滚动（跳过 5 → 跳过 4）只发生在窗口刚出现后的几秒内；
                    // 超出窗口的内容事件一律不处理，这是把事件洪水挡掉的主要手段
                    if (now - lastStateChangeAt > CONTENT_ARMED_WINDOW_MS) return
                    if (now - lastContentCheckAt < CONTENT_DEBOUNCE_MS) return
                    lastContentCheckAt = now
                    handleWindow(event.packageName?.toString(), event.className?.toString())
                }
            }
        } catch (e: Exception) {
            // 无障碍事件处理绝不能崩：崩一次整个服务就被系统解绑，且用户看不到任何提示
            Log.w(TAG, "处理无障碍事件失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        clickCooldown.clear()
        recentClicks.clear()
        super.onDestroy()
    }

    // ──────────────────────────────────────────
    //  窗口处理
    // ──────────────────────────────────────────

    private fun handleWindow(pkg: String?, cls: String?) {
        if (pkg.isNullOrEmpty()) return
        // 自己的普通页面不点（防误触自家 UI）；自检页是唯一例外 ——
        // 「测试拦截」按钮需要一个带「跳过 N」按钮的窗口来验证整条点击链路
        if (pkg == packageName && cls?.contains("AdSkipTestActivity") != true) return
        if (pkg in EXCLUDED_PACKAGES) return

        val now = System.currentTimeMillis()
        if (now < breakerUntil) return

        val root = rootInActiveWindow ?: return
        // 拿到的窗口可能不是事件所属应用的窗口（输入法/系统对话框）
        if (root.packageName != pkg) return

        val screenArea = resources.displayMetrics.widthPixels.toLong() *
            resources.displayMetrics.heightPixels.toLong()

        // 先按文字找（走系统索引，便宜）；找不到再按控件 ID 兜底（手工遍历，贵一些）
        val target = findByText(root) ?: findByViewId(root, screenArea) ?: return
        if (!isClickableTarget(target, screenArea)) return

        val text = target.text?.toString() ?: target.contentDescription?.toString() ?: ""
        val key = "$pkg|$text"

        // 同一广告的冷却窗口内不重复点
        val last = clickCooldown[key]
        if (last != null && now - last < CLICK_COOLDOWN_MS) return

        if (!clickWithAncestorFallback(target, screenArea)) return

        clickCooldown[key] = now
        registerClickForBreaker(now)
        AdSkipStats.recordSkip(this, pkg, text)
        Log.i(TAG, "已跳过开屏广告: $pkg 「$text」")
    }

    // ──────────────────────────────────────────
    //  查找
    // ──────────────────────────────────────────

    /** 按「跳过」文字族查找。findAccessibilityNodeInfosByText 是子串匹配，一词覆盖一族文案 */
    private fun findByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (query in TEXT_QUERIES) {
            val nodes = try {
                root.findAccessibilityNodeInfosByText(query)
            } catch (e: Exception) {
                null
            } ?: continue
            for (node in nodes) {
                if (!node.isVisibleToUser || !node.isEnabled) continue
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                if (AdSkipMatcher.matchesText(text) || AdSkipMatcher.matchesText(desc)) {
                    return node
                }
            }
        }
        return null
    }

    /** 按控件 ID 兜底：部分广告 SDK 的跳过键是纯图标（无文字），只能靠 viewId 认 */
    private fun findByViewId(root: AccessibilityNodeInfo, screenArea: Long): AccessibilityNodeInfo? {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && visited < MAX_TRAVERSE_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser && node.isEnabled &&
                AdSkipMatcher.matchesViewId(node.viewIdResourceName) &&
                isSmallEnough(node, screenArea)
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return null
    }

    // ──────────────────────────────────────────
    //  安全闸
    // ──────────────────────────────────────────

    /** 目标尺寸必须在安全范围内：太小是装饰件，太大是广告容器（点了跳落地页） */
    private fun isClickableTarget(node: AccessibilityNodeInfo, screenArea: Long): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val area = bounds.width().toLong() * bounds.height().toLong()
        if (area <= 0) return false
        // 上限：屏幕的 25%
        if (area > screenArea * MAX_NODE_AREA_RATIO) return false
        // 下限：小于 24x16dp 左右的点击目标不是给手指准备的，多半是装饰
        if (bounds.width() < 24 || bounds.height() < 16) return false
        return node.isClickable || hasSafeClickableAncestor(node, area, screenArea)
    }

    private fun isSmallEnough(node: AccessibilityNodeInfo, screenArea: Long): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val area = bounds.width().toLong() * bounds.height().toLong()
        return area > 0 && area <= screenArea * MAX_NODE_AREA_RATIO
    }

    /**
     * 文字节点自身不可点时，向上找可点击祖先。
     *
     * 面积倍数上限是关键：真实跳过按钮的文字与其可点击背景尺寸接近（1~3 倍），
     * 而广告容器（WebView/FrameLayout）通常是全屏 —— 面积大出几十倍。
     * 不设这个上限，找到的"可点击祖先"就是整个广告本身，点一下直接跳落地页。
     */
    private fun hasSafeClickableAncestor(node: AccessibilityNodeInfo, nodeArea: Long, screenArea: Long): Boolean {
        var current: AccessibilityNodeInfo? = node
        var area = nodeArea
        var depth = 0
        while (depth < MAX_ANCESTOR_DEPTH) {
            val parent = current?.parent ?: return false
            val parentArea = Rect().also { parent.getBoundsInScreen(it) }
                .let { it.width().toLong() * it.height().toLong() }
            if (parentArea > area * MAX_ANCESTOR_AREA_RATIO) return false
            if (parentArea > screenArea * MAX_NODE_AREA_RATIO) return false
            if (parent.isClickable && parent.isEnabled && parent.isVisibleToUser) return true
            area = parentArea
            current = parent
            depth++
        }
        return false
    }

    /** 点击；自身不可点时走 [hasSafeClickableAncestor] 已验过的祖先链 */
    private fun clickWithAncestorFallback(node: AccessibilityNodeInfo, screenArea: Long): Boolean {
        if (node.isClickable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var current: AccessibilityNodeInfo? = node
        var area = Rect().also { node.getBoundsInScreen(it) }.let { it.width().toLong() * it.height().toLong() }
        var depth = 0
        while (depth < MAX_ANCESTOR_DEPTH) {
            val parent = current?.parent ?: return false
            val parentArea = Rect().also { parent.getBoundsInScreen(it) }
                .let { it.width().toLong() * it.height().toLong() }
            if (parentArea > area * MAX_ANCESTOR_AREA_RATIO) return false
            if (parentArea > screenArea * MAX_NODE_AREA_RATIO) return false
            if (parent.isClickable && parent.isEnabled &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            area = parentArea
            current = parent
            depth++
        }
        return false
    }

    /** 熔断：短时间点击次数异常 → 冷静一段时间，防节点树异常导致失控连点 */
    private fun registerClickForBreaker(now: Long) {
        recentClicks.addLast(now)
        while (recentClicks.isNotEmpty() && recentClicks.first() < now - 10_000L) {
            recentClicks.removeFirst()
        }
        if (recentClicks.size >= MAX_CLICKS_PER_10S) {
            breakerUntil = now + BREAKER_COOLDOWN_MS
            recentClicks.clear()
            Log.w(TAG, "点击频率异常，熔断 ${BREAKER_COOLDOWN_MS / 1000} 秒")
        }
    }
}
