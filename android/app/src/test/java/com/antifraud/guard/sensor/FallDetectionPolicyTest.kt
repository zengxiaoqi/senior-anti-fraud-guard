package com.antifraud.guard.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跌倒判定的纯逻辑测试。
 *
 * ## 为什么这组规则必须钉住
 * 判定写错有两个方向，后果完全不对称：
 *  - **漏报**（真摔了不告警）：老人出事没人知道，产品失去存在意义；
 *  - **误报**（没摔也告警）：子女被反复叫醒 → 集体忽略推送 → 之后的真告警也没人管。
 *
 * 所以每条用例都标注了它挡住的是哪一种失败 —— 尤其是几个"看起来多余"的
 * 约束（必须有失重前置、撞击后必须静止），它们不是过度设计，
 * 删掉任何一条都会让误报率回到不可用的水平。
 */
class FallDetectionPolicyTest {

    private val SECOND = 1000L
    private val MINUTE = 60 * SECOND

    /**
     * 造一次"摔一次"的完整采样序列。
     *
     * @return 这次序列是否真的判定了跌倒（而不是"看起来静止了"）
     */
    private fun simulateFall(d: FallDetectionPolicy.Detector, startAt: Long = 0L): Boolean {
        var t = startAt
        // 1) 自由落体：连续 200ms 低于 3 m/s²
        repeat(5) {
            assertFalse("自由落体阶段不应判定为跌倒", d.onSample(1.5f, t))
            t += 50
        }
        // 2) 撞击：25 m/s²（摔到地面的典型峰值）
        d.onSample(FallDetectionPolicy.IMPACT_THRESHOLD + 7f, t)
        t += 50
        // 3) 撞击后静止：读数几乎只剩重力，持续超过 STILL_REQUIRED_MS
        var confirmed = false
        repeat(14) {
            if (d.onSample(9.8f, t)) confirmed = true
            t += SECOND
        }
        return confirmed
    }

    @Test
    fun `完整的失重-撞击-静止序列判定为跌倒`() {
        val d = FallDetectionPolicy.Detector()
        assertTrue("跌倒必须被检出", simulateFall(d))
        assertEquals(FallDetectionPolicy.Detector.State.FALL_CONFIRMED, d.state)
    }

    @Test
    fun `纯撞击但没有失重前置不判定为跌倒`() {
        // 被撞、被抢、急刹车：只有一次冲击，没有自由落体段。
        // 没有失重前置的判定会把这些统统报成跌倒 —— 而它们恰恰是老人最危险的时刻
        // （被抢手机），绝不能因此淹没掉真正的 COERCION 类告警。
        val d = FallDetectionPolicy.Detector()
        var t = 0L
        d.onSample(30f, t)   // 直接一个巨大冲击，没有先失重
        t += 50
        repeat(20) {
            d.onSample(9.8f, t)
            t += SECOND
        }
        assertFalse("无失重前置的冲击不应判定为跌倒", d.onSample(9.8f, t))
        assertEquals(FallDetectionPolicy.Detector.State.IDLE, d.state)
    }

    @Test
    fun `撞击后继续走动不算跌倒`() {
        // 从沙发上站起来、坐到椅子上：一次冲击后马上继续移动。
        // 只看"撞击 + 峰值"会把这些全部误报 —— 而它们是老人一天几十次的正常动作。
        val d = FallDetectionPolicy.Detector()
        var t = 0L
        repeat(5) { d.onSample(1.5f, t); t += 50 }
        d.onSample(26f, t)
        t += 50
        // 静止 5 秒（还没到 12 秒门槛）
        repeat(5) { d.onSample(9.8f, t); t += SECOND }
        // 然后走开了
        d.onSample(28f, t)
        repeat(20) { d.onSample(9.8f, t); t += SECOND }
        assertFalse("撞击后继续走动不应判定为跌倒", d.onSample(9.8f, t))
        assertEquals(FallDetectionPolicy.Detector.State.IDLE, d.state)
    }

    @Test
    fun `失重后迟迟没有撞击则复位`() {
        // 弯腰捡东西、蹲下起身：会有失重但没有猛烈撞击。
        // 不复位的话状态会卡在 FREEFALL，等下一次真撞击来时
        // 撞击时刻已经超出窗口，真摔了反而检不出来 —— 这是最危险的一种失效。
        val d = FallDetectionPolicy.Detector()
        var t = 0L
        repeat(5) { d.onSample(1.5f, t); t += 50 }
        assertEquals(FallDetectionPolicy.Detector.State.FREEFALL, d.state)
        repeat(50) { d.onSample(9.8f, t); t += 100 }
        assertEquals("超时未撞击必须复位", FallDetectionPolicy.Detector.State.IDLE, d.state)
    }

    @Test
    fun `静止时长不够时不判定为跌倒`() {
        val d = FallDetectionPolicy.Detector()
        var t = 0L
        repeat(5) { d.onSample(1.5f, t); t += 50 }
        d.onSample(25f, t)
        t += 50
        // 只静止 6 秒
        repeat(6) {
            assertFalse(d.onSample(9.8f, t))
            t += SECOND
        }
        assertEquals(FallDetectionPolicy.Detector.State.IMPACTED, d.state)
    }

    @Test
    fun `判定成功后不会每个采样都重复触发`() {
        val d = FallDetectionPolicy.Detector()
        simulateFall(d)
        var t = 0L
        repeat(10) {
            assertFalse("确认后不得重复触发", d.onSample(9.8f, t))
            t += SECOND
        }
    }

    @Test
    fun `外部复位后下一次跌倒能被立刻检出`() {
        // 老人点了「我没事」之后必须能重新武装。
        // 不复位的话下一次真摔会在旧的 IMPACTED 状态上直接满足
        // "静止 12 秒"，等于凭空延迟 12 秒 —— 对急救是不可接受的。
        val d = FallDetectionPolicy.Detector()
        var t = 0L
        repeat(5) { d.onSample(1.5f, t); t += 50 }
        d.onSample(25f, t)
        t += 50
        repeat(6) { d.onSample(9.8f, t); t += SECOND }
        assertEquals(FallDetectionPolicy.Detector.State.IMPACTED, d.state)

        d.onSensorReset()
        assertEquals(FallDetectionPolicy.Detector.State.IDLE, d.state)

        var t2 = t + 5 * SECOND
        assertTrue("复位后必须能重新检出", simulateFall(d, t2))
    }
    @Test
    fun `合加速度与手机朝向无关`() {
        // 手机在裤兜里朝向是任意的：同一组物理数据在不同朝向下
        // 各轴分量完全不同，只有模长是稳定的。
        // 若用单轴判定，会出现"横着放不触发、竖着放触发"的诡异行为。
        val flat = FallDetectionPolicy.magnitude(0f, 0f, 9.8f)
        val upright = FallDetectionPolicy.magnitude(9.8f, 0f, 0f)
        val tilted = FallDetectionPolicy.magnitude(6.9f, 6.9f, 0f)
        assertEquals(9.8f, flat, 0.01f)
        assertEquals(9.8f, upright, 0.01f)
        assertEquals(9.75f, tilted, 0.05f)
    }

    @Test
    fun `冷却期内不重复告警`() {
        val now = 1_700_000_000_000L
        assertFalse("从未告警过不在冷却期", FallDetectionPolicy.inCooldown(0L, now))
        assertTrue(
            "刚报过 5 分钟应仍在冷却",
            FallDetectionPolicy.inCooldown(now - 5 * MINUTE, now)
        )
        assertFalse(
            "超过冷却时间可以再次上报",
            FallDetectionPolicy.inCooldown(now - 11 * MINUTE, now)
        )
    }

    @Test
    fun `时钟回拨时保守地当作仍在冷却`() {
        val now = 1_700_000_000_000L
        // 上次告警时间在未来（用户改了系统时间）
        assertTrue("时钟回拨不得导致刷屏", FallDetectionPolicy.inCooldown(now + 10 * MINUTE, now))
    }

    @Test
    fun `确认窗口为 60 秒`() {
        // 60 秒是老人从"意识到有提示"到"决定点我没事"的合理上限。
        // 太短（比如 10 秒）老人来不及反应，会导致误报全部变成真告警上报；
        // 太长（比如 5 分钟）真摔倒时子女要多等 5 分钟才被通知。
        assertEquals(60, FallDetectionPolicy.CONFIRM_WINDOW_SEC)
    }
}
