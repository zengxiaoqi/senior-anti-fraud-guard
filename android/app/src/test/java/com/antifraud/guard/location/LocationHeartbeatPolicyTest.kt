package com.antifraud.guard.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位心跳兜底策略测试。
 *
 * ## 为什么需要这层兜底
 * 事故复盘时暴露的一个更隐蔽的问题：`LOCATION_UPDATE` 只有一个产生点 ——
 * `LocationGuardService.onLocationChanged()`。一旦系统停止投递回调（无论是没拿到
 * 后台定位权限，还是被厂商 ROM 的省电策略限制），上报就**永久归零**，
 * 而且界面上完全看不出区别：
 *
 *   - "老人一直待在家里没动" → 子女端：没有新位置
 *   - "定位已经死了12 小时" → 子女端：也没有新位置
 *
 * 对防诈产品来说这是致命的：家属看到"没有异常"，会以为一切正常。
 * 所以必须有一个**不依赖定位回调**的独立心跳，每隔一段时间就把
 * "最后已知位置"重新报一次 —— 这样"没动"和"死了"在数据上就分得开了。
 *
 * 同时心跳要负责**重新注册**定位订阅：厂商 ROM 会在后台悄悄把
 * `requestLocationUpdates` 的注册清掉，而 App 完全不知情。
 *
 * 纯逻辑，不依赖 android.*，可在 JVM 上直接测。
 */
class LocationHeartbeatPolicyTest {

    private companion object {
        const val SECOND = 1000L
        const val MINUTE = 60 * SECOND
    }

    // ── 首次心跳：必须立刻发一次，否则刚启动时是哑的 ──────────────────────

    @Test
    fun `从未上报过时第一次心跳应立即触发`() {
        assertTrue(
            "刚启动还没有任何上报记录时，必须立刻发一次，",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = 1_000_000L,
                lastReportAtMs = 0L,
                intervalMs = 5 * MINUTE
            )
        )
    }

    @Test
    fun `刚上报过的心跳不应立刻重复触发`() {
        assertFalse(
            LocationHeartbeatPolicy.shouldReport(
                nowMs = 1_000_000L + MINUTE,
                lastReportAtMs = 1_000_000L,
                intervalMs = 5 * MINUTE
            )
        )
    }

    // ── 常规节流：没到间隔就不发，避免耗电和刷屏 ─────────────────────────

    @Test
    fun `未到心跳间隔不上报`() {
        val t0 = 1_000_000L
        // 走到刚好差 1 毫秒
        assertFalse(
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 5 * MINUTE - 1,
                lastReportAtMs = t0,
                intervalMs = 5 * MINUTE
            )
        )
    }

    @Test
    fun `到达心跳间隔就上报 边界含等号`() {
        val t0 = 1_000_000L
        assertTrue(
            "恰好等于间隔时应当上报",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 5 * MINUTE,
                lastReportAtMs = t0,
                intervalMs = 5 * MINUTE
            )
        )
    }

    @Test
    fun `远超心跳间隔也只记为需要上报一次`() {
        val t0 = 1_000_000L
        // 停了 3 小时：应该补报一次，而不是补报 36 次把服务端刷爆
        assertTrue(
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 3 * 60 * MINUTE,
                lastReportAtMs = t0,
                intervalMs = 5 * MINUTE
            )
        )
    }

    // ── 异常输入：不能崩，也不能导致死循环刷屏 ───────────────────────────

    @Test
    fun `心跳间隔非正数时不会除零崩溃`() {
        // 配置写坏时（0 / 负数）必须被抬到下界，而不是除零崩掉或退化成每毫秒一发
        val t0 = 1_000_000L

        // 从未上报过：无论间隔多离谱，都要立刻发一次
        assertTrue(LocationHeartbeatPolicy.shouldReport(t0, 0L, 0L))
        assertTrue(LocationHeartbeatPolicy.shouldReport(t0, 0L, -5 * MINUTE))

        // 已上报过且间隔被写坏：实际按 30 秒下界判定
        assertFalse(
            "间隔 0 时，29 秒内不应上报（下界守卫生效）",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 29 * SECOND,
                lastReportAtMs = t0,
                intervalMs = 0L
            )
        )
        assertTrue(
            "间隔 0 时，超过 30 秒下界才上报",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 31 * SECOND,
                lastReportAtMs = t0,
                intervalMs = 0L
            )
        )
        assertFalse(
            "间隔为负数时同样按 30 秒下界处理",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 10 * SECOND,
                lastReportAtMs = t0,
                intervalMs = -5 * MINUTE
            )
        )
    }

    @Test
    fun `心跳间隔下界被抬到合理值 不会因为配置成 1 毫秒而刷屏`() {
        val t0 = 1_000_000L
        // 配置成 1 毫秒，但实际至少要等 MIN_EFFECTIVE_INTERVAL_MS
        assertFalse(
            "1 毫秒间隔不应被真的采纳，否则等于没有节流",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + 100L,
                lastReportAtMs = t0,
                intervalMs = 1L
            )
        )
        assertTrue(
            "抬到下界之后，到点仍会上报",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = t0 + LocationHeartbeatPolicy.MIN_EFFECTIVE_INTERVAL_MS,
                lastReportAtMs = t0,
                intervalMs = 1L
            )
        )
    }

    @Test
    fun `系统时间被往前调时不会卡死心跳`() {
        // lastReport 在未来（用户或 NTP 把时钟改了）会导致 now-last 为负
        val future = 10_000_000L
        assertTrue(
            "时钟回拨应视为需要上报，而不是永久静默",
            LocationHeartbeatPolicy.shouldReport(
                nowMs = 1_000_000L,
                lastReportAtMs = future,
                intervalMs = 5 * MINUTE
            )
        )
    }

    // ── 降级间隔：没拿到后台定位时必须更频繁 ────────────────────────────
    // 只给前台定位时，退到后台就收不到回调了。心跳此时是唯一的活体证明，
    // 所以要更频繁地报"我还在原地"，让子女至少能看出设备是活的。

    @Test
    fun `降级模式下的间隔比正常模式更短`() {
        assertTrue(
            "未拿到后台定位时，心跳必须比正常情况更频繁",
            LocationHeartbeatPolicy.DEGRADED_INTERVAL_MS < LocationHeartbeatPolicy.NORMAL_INTERVAL_MS
        )
    }

    @Test
    fun `intervalFor 会依据是否拿到后台定位返回不同间隔`() {
        assertEquals(
            LocationHeartbeatPolicy.DEGRADED_INTERVAL_MS,
            LocationHeartbeatPolicy.intervalFor(hasBackgroundLocation = false)
        )
        assertEquals(
            LocationHeartbeatPolicy.NORMAL_INTERVAL_MS,
            LocationHeartbeatPolicy.intervalFor(hasBackgroundLocation = true)
        )
    }

    // ── 是否需要重新注册定位订阅 ─────────────────────────────────────────

    @Test
    fun `从未注册过时需要重新注册`() {
        assertTrue(LocationHeartbeatPolicy.needsReregister(registeredAtMs = 0L, nowMs = 1_000_000L))
    }

    @Test
    fun `注册未超期时不重新注册`() {
        val t = 1_000_000L
        assertFalse(
            LocationHeartbeatPolicy.needsReregister(
                registeredAtMs = t,
                nowMs = t + LocationHeartbeatPolicy.REREGISTER_INTERVAL_MS - 1
            )
        )
    }

    @Test
    fun `注册超过安全窗口后必须重新注册 因为厂商 ROM 会偷偷清掉订阅`() {
        val t = 1_000_000L
        assertTrue(
            "厂商 ROM 会在后台清掉 requestLocationUpdates，必须周期性重注册",
            LocationHeartbeatPolicy.needsReregister(
                registeredAtMs = t,
                nowMs = t + LocationHeartbeatPolicy.REREGISTER_INTERVAL_MS
            )
        )
    }
}