package com.antifraud.guard.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护规则取值范围的单一权威。
 *
 * ## 为什么要单独一个文件
 * 服务端 `GUARD_SETTING_BOUNDS` 与客户端的 clamp 原先是两处独立手写的数字。
 * 两处都会改，而没有任何东西会告诉开发者
 * "你改的这一处和那边不一致" —— 于是用户设了 600 米，服务端静默收敛成
 * 500，界面上显示 600，实际按 500 守护，且不报任何错。
 *
 * 跨语言一致性无法用测试断言，所以定一条规则：
 * **客户端 clamp 只为改善体验，服务端返回的 merged 永远是最终权威。**
 * 所有客户端侧写入都必须用服务端回传的 merged 覆盖本机。
 */
class GuardSettingBoundsTest {

    // ── 每个字段的上下界都必须真的生效 ──────────────────────────────────

    @Test
    fun `离家半径超出范围时收敛到边界`() {
        assertEquals(100, GuardSettingBounds.awayRadius(1))
        assertEquals(500, GuardSettingBounds.awayRadius(500))
        assertEquals(5000, GuardSettingBounds.awayRadius(99999))
    }

    @Test
    fun `停留判定半径超出范围时收敛到边界`() {
        assertEquals(20, GuardSettingBounds.stayMoveMeters(0))
        assertEquals(100, GuardSettingBounds.stayMoveMeters(100))
        assertEquals(1000, GuardSettingBounds.stayMoveMeters(5000))
    }

    @Test
    fun `停留时长超出范围时收敛到边界`() {
        assertEquals(5, GuardSettingBounds.stayMinutes(1))
        assertEquals(40, GuardSettingBounds.stayMinutes(40))
        assertEquals(240, GuardSettingBounds.stayMinutes(9999))
    }

    @Test
    fun `通话预警时长超出范围时收敛到边界`() {
        assertEquals(1, GuardSettingBounds.callMinutes(0))
        assertEquals(15, GuardSettingBounds.callMinutes(15))
        assertEquals(240, GuardSettingBounds.callMinutes(100000))
    }

    @Test
    fun `录音段数与单段时长超出范围时收敛到边界`() {
        assertEquals(1, GuardSettingBounds.recordingSegments(0))
        assertEquals(6, GuardSettingBounds.recordingSegments(99))
        assertEquals(1, GuardSettingBounds.recordingSegmentMinutes(0))
        assertEquals(10, GuardSettingBounds.recordingSegmentMinutes(99))
    }

    // ── 负数与非法输入：不能崩，也不能变成"无限制" ──────────────────────

    @Test
    fun `负数不会被当成有效值透传`() {
        // 负半径会让"距离 > 负数"恒真，等于永远判定为离家。
        // 任何一条守护规则被写成负数都必须收敛到下界，而不是照用。
        assertEquals(100, GuardSettingBounds.awayRadius(-1))
        assertEquals(20, GuardSettingBounds.stayMoveMeters(-999))
        assertEquals(5, GuardSettingBounds.stayMinutes(-40))
        assertEquals(1, GuardSettingBounds.callMinutes(-5))
        assertEquals(1, GuardSettingBounds.recordingSegments(-3))
    }

    // ── 坐标：这是唯一不能 clamp 的字段 ─────────────────────────────────
    //
    // 坐标落在合法范围内就原样通过。超范围要返回 null（"无效"）而不是
    // 收敛到边界 —— 把纬度 999 收敛成 90 会静默把家基准设到北极。

    @Test
    fun `合法坐标原样通过`() {
        assertEquals(28.2281, GuardSettingBounds.validLatitude(28.2281)!!, 1e-9)
        assertEquals(-33.8688, GuardSettingBounds.validLatitude(-33.8688)!!, 1e-9)
        assertEquals(112.9381, GuardSettingBounds.validLongitude(112.9381)!!, 1e-9)
        assertEquals(-70.6693, GuardSettingBounds.validLongitude(-70.6693)!!, 1e-9)
    }

    @Test
    fun `超范围坐标返回 null 而不是收敛到边界`() {
        assertEquals(null, GuardSettingBounds.validLatitude(91.0))
        assertEquals(null, GuardSettingBounds.validLatitude(-91.0))
        assertEquals(null, GuardSettingBounds.validLongitude(181.0))
        assertEquals(null, GuardSettingBounds.validLongitude(-181.0))
    }

    @Test
    fun `零坐标对家基准而言等于未设置`() {
        // 0,0 是几内亚湾外海的一个合法坐标点，但对"家"没有意义，
        // 且 GuardConfig.hasFamilyHome 也把它当未设置。保持一致。
        assertEquals(null, GuardSettingBounds.validLatitude(0.0))
        assertEquals(null, GuardSettingBounds.validLongitude(0.0))
    }

    @Test
    fun `NaN 与无穷大坐标一律拒绝`() {
        // 客户端 toDoubleOrNull() 拿不到 NaN，但云端脏数据可能带来
        assertEquals(null, GuardSettingBounds.validLatitude(Double.NaN))
        assertEquals(null, GuardSettingBounds.validLongitude(Double.POSITIVE_INFINITY))
    }
}
