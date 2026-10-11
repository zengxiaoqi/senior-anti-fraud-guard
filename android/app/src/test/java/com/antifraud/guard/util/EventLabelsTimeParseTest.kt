package com.antifraud.guard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EventLabels 时间解析回归测试。
 *
 * ## 背景（2026-10-10 线上实证）
 * 子女端接口统一把时间转成北京时间裸串 "2026-10-10 12:59:00" 下发
 * （见 workers/src/lib/timeFormat.ts toBeijingRows），而解析格式曾写死
 * "yyyy-MM-dd'T'HH:mm:ss"——字面量 'T' 与空格永远匹配不上，SimpleDateFormat
 * 必抛 ParseException → parseIsoDate 恒为 null：
 *  - 轨迹页 7 天过滤把所有点滤空 → "近 7 天暂无轨迹数据"（地图无标记）
 *  - 告警列表按时间过滤整页丢弃
 * 两种格式都必须能解析出同一时间点，此测试防止回归。
 */
class EventLabelsTimeParseTest {

    /** 北京时间裸串与等价 ISO UTC 必须解析出同一时刻（"2026-10-10 12:00" 北京 = 04:00 UTC） */
    @Test
    fun `北京时间裸串与ISO等价`() {
        val fromBeijing = EventLabels.parseIsoDate("2026-10-10 12:00:00")
        val fromIso = EventLabels.parseIsoDate("2026-10-10T04:00:00.000Z")
        assertNotNull(fromBeijing)
        assertNotNull(fromIso)
        assertEquals(fromIso!!.time, fromBeijing!!.time)
    }

    @Test
    fun `ISO无时区标记按UTC解析`() {
        val d = EventLabels.parseIsoDate("2026-10-10T04:00:00")
        assertNotNull(d)
        // UTC 04:00 = 北京 12:00
        val fmt = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+08:00")
        }
        assertEquals("12:00", fmt.format(d!!))
    }

    @Test
    fun `带小数秒的裸串也能解析`() {
        // 兜底：万一有链路下发 "2026-10-10 12:00:00.123"
        assertNotNull(EventLabels.parseIsoDate("2026-10-10 12:00:00.123"))
    }

    @Test
    fun `解析失败或空串返回null`() {
        assertNull(EventLabels.parseIsoDate(""))
        assertNull(EventLabels.parseIsoDate("not-a-date"))
        assertNull(EventLabels.parseIsoDate("null")) // JSON null 经 optString 变 "null" 字符串
    }

    /** shortLocalTime 此前对裸串恒返回空串，导致各页时间显示退化为原始兜底 */
    @Test
    fun `shortLocalTime对北京时间裸串返回相对时间`() {
        val s = EventLabels.shortLocalTime("2026-10-10 12:00:00")
        // 不再是空串即可（具体是"今天/昨天/MM-dd"取决于运行时钟，不做死断言）
        assert(s.isNotEmpty()) { "北京时间裸串应能解析出相对时间，实际为空" }
    }
}
