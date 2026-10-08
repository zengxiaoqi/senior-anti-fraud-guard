package com.antifraud.guard.location

/**
 * 定位心跳兜底策略（纯逻辑，不依赖 android.*，可在 JVM 上直接测）。
 *
 * ## 要解决的问题
 * `LOCATION_UPDATE` 原本只有一个产生点：`LocationGuardService.onLocationChanged()`。
 * 这意味着只要系统停止投递回调，上报就**永久归零**，而且界面上看不出任何区别：
 *
 * | 实际情况 | 子女端看到 |
 * |---|---|
 * | 老人一直待在家里没动 | 没有新位置 |
 * | 定位早就死了 12 小时 | 也没有新位置 |
 *
 * 对防诈产品这是致命的：家属看到"没有异常"就以为一切正常。
 * 2026-10-08 的线上事故正是这种形态 —— 位置在 00:27 停更，
 * 子女端到 13:01 都以为老人还在原地。
 *
 * ## 两层兜底
 * 1. **心跳**：不依赖定位回调，按间隔重报"最后已知位置"。
 *    于是"没动"表现为持续收到同一坐标，"死了"表现为**坐标戛然而止**——
 *    两者在数据上立刻分得开。
 * 2. **重注册**：周期性重新调用 `requestLocationUpdates`。
 *    厂商 ROM 会在后台悄悄清掉这个注册，而 App 完全不知情，
 *    结果就是一次订阅、永远不再有回调。
 *
 * 纯逻辑抽出来的原因同上：这些都是"看代码看不出问题、跑起来才知道"的规则。
 */
object LocationHeartbeatPolicy {

    private const val SECOND = 1000L
    private const val MINUTE = 60 * SECOND

    /** 拿到后台定位时的正常心跳间隔（10 分钟，与 requestLocationUpdates 的节奏一致） */
    const val NORMAL_INTERVAL_MS = 10 * MINUTE

    /**
     * 降级心跳间隔（2 分钟）。
     *
     * 只给前台定位时，App 一退到后台就收不到定位回调了，心跳成了唯一的活体证明，
     * 所以必须更频繁地报"我还在原地"。代价是耗电与流量略增 ——
     * 这个交换是值得的：看不见的守护等于没有守护。
     */
    const val DEGRADED_INTERVAL_MS = 2 * MINUTE

    /** 心跳间隔下界：配置写坏时兜底，防止间隔变成 1 毫秒导致刷屏 */
    const val MIN_EFFECTIVE_INTERVAL_MS = 30 * SECOND

    /** 定位订阅的安全窗口：超过这个时长就重新注册一次 */
    const val REREGISTER_INTERVAL_MS = 30 * MINUTE

    /** 按当前是否持有后台定位权限，选一个心跳间隔 */
    @JvmStatic
    fun intervalFor(hasBackgroundLocation: Boolean): Long =
        if (hasBackgroundLocation) NORMAL_INTERVAL_MS else DEGRADED_INTERVAL_MS

    /**
     * 到点了没有？该不该发这一次心跳上报。
     *
     * @param nowMs          当前时间
     * @param lastReportAtMs 上一次任何形式的位置上报时间，0 表示从未上报
     * @param intervalMs     心跳间隔
     */
    @JvmStatic
    fun shouldReport(nowMs: Long, lastReportAtMs: Long, intervalMs: Long): Boolean {
        // 从未上报过：立刻发一次。否则刚启动的那几分钟是哑的，
        // 而这恰恰是用户最可能在看 App 的时候。
        if (lastReportAtMs <= 0L) return true

        val effective = if (intervalMs < MIN_EFFECTIVE_INTERVAL_MS) MIN_EFFECTIVE_INTERVAL_MS else intervalMs
        val elapsed = nowMs - lastReportAtMs

        // elapsed <= 0 说明系统时间被往回拨过（NTP 校正、用户改时间、手动改表）。
        // 此时当作"需要上报"：宁可多报一次，也不要因为时钟回拨让守护永久静默。
        if (elapsed <= 0L) return true

        return elapsed >= effective
    }

    /**
     * 定位订阅是否需要重新注册。
     *
     * 厂商 ROM（MIUI/EMUI/ColorOS/OriginOS）在进入省电策略或长时间后台后，
     * 会清掉 `requestLocationUpdates` 的注册，且**不会通知 App**。
     * 症状与"系统停止投递"完全一样，只能靠周期性重注册来兜。
     */
    @JvmStatic
    fun needsReregister(registeredAtMs: Long, nowMs: Long): Boolean {
        if (registeredAtMs <= 0L) return true
        val elapsed = nowMs - registeredAtMs
        if (elapsed <= 0L) return false
        return elapsed >= REREGISTER_INTERVAL_MS
    }
}