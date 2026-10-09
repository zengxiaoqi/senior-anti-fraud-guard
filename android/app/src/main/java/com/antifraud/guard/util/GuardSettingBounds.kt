package com.antifraud.guard.util

/**
 * 守护规则取值范围的单一权威（Android 侧）。
 *
 * ## 跨语言一致性问题
 * 同样的上下界在两处手写：
 *   - 服务端 `routes/auth.js` / `services/guardSettingsPolicy.js` 的 `GUARD_SETTING_BOUNDS`
 *   - 这里
 *
 * 两处都会改，而没有任何机制会提示"你改的这处和那边不一致"。
 * 后果是用户设了 600 米、服务端静默收敛成 500，界面上显示 600、
 * 实际按 500 守护，且不报任何错 —— 用户和子女都无从察觉。
 *
 * 因为跨语言无法用测试断言一致性，所以定一条硬规则：
 * **服务端返回的 merged 永远是最终权威**，客户端 clamp 只为改善体验。
 * 任何客户端侧写入成功后，都必须用服务端回传的 merged 覆盖本机
 * （照 `SettingsActivity.pushSettingsThenFinish` 的既有做法）。
 *
 * 修改本文件时必须同步 `services/guardSettingsPolicy.js` 与
 * `workers/src/routes/auth.ts`。
 */
object GuardSettingBounds {

    // ── 位置守护阈值 ────────────────────────────────────────────────────
    const val AWAY_RADIUS_MIN = 100
    const val AWAY_RADIUS_MAX = 5000
    const val AWAY_RADIUS_DEFAULT = 500

    const val STAY_MOVE_MIN = 20
    const val STAY_MOVE_MAX = 1000
    const val STAY_MOVE_DEFAULT = 100

    const val STAY_MINUTES_MIN = 5
    const val STAY_MINUTES_MAX = 240
    const val STAY_MINUTES_DEFAULT = 40

    // ── 通话与支付 ──────────────────────────────────────────────────────
    const val CALL_MINUTES_MIN = 1
    const val CALL_MINUTES_MAX = 240
    const val CALL_MINUTES_DEFAULT = 15

    // ── 录音存证 ────────────────────────────────────────────────────────
    const val REC_SEGMENTS_MIN = 1
    const val REC_SEGMENTS_MAX = 6
    const val REC_SEGMENTS_DEFAULT = 3

    const val REC_SEGMENT_MINUTES_MIN = 1
    const val REC_SEGMENT_MINUTES_MAX = 10
    const val REC_SEGMENT_MINUTES_DEFAULT = 10

    /** 离家判定半径（米）。负数也会被抬到下界 —— 负半径会让「距离 > 负数」恒真。 */
    fun awayRadius(v: Int): Int = v.coerceIn(AWAY_RADIUS_MIN, AWAY_RADIUS_MAX)

    /** 停留判定半径（米） */
    fun stayMoveMeters(v: Int): Int = v.coerceIn(STAY_MOVE_MIN, STAY_MOVE_MAX)

    /** 陌生地点停留时长（分钟） */
    fun stayMinutes(v: Int): Int = v.coerceIn(STAY_MINUTES_MIN, STAY_MINUTES_MAX)

    /** 通话预警时长（分钟） */
    fun callMinutes(v: Int): Int = v.coerceIn(CALL_MINUTES_MIN, CALL_MINUTES_MAX)

    /** 单次录音段数 */
    fun recordingSegments(v: Int): Int = v.coerceIn(REC_SEGMENTS_MIN, REC_SEGMENTS_MAX)

    /** 单段录音时长（分钟） */
    fun recordingSegmentMinutes(v: Int): Int =
        v.coerceIn(REC_SEGMENT_MINUTES_MIN, REC_SEGMENT_MINUTES_MAX)

    /**
     * 校验纬度，非法时返回 null。
     *
     * 坐标是唯一**不能 clamp** 的字段：把纬度 999 收敛成 90
     * 会静默把家基准设到北极，界面看不出任何异常。
     * 0 被一并拒绝，因为 GuardConfig.hasFamilyHome 也把 0/0 当未设置，
     * 两处口径必须一致，否则会出现"服务端认为已设、客户端认为未设"。
     */
    fun validLatitude(v: Double?): Double? =
        if (v == null || v.isNaN() || v.isInfinite()) null
        else if (v == 0.0) null
        else if (v < -90.0 || v > 90.0) null
        else v

    /** 校验经度，非法时返回 null。理由同 [validLatitude]。 */
    fun validLongitude(v: Double?): Double? =
        if (v == null || v.isNaN() || v.isInfinite()) null
        else if (v == 0.0) null
        else if (v < -180.0 || v > 180.0) null
        else v
}
