package com.antifraud.guard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 老人端风险记录页改版（2026-10-11）新增的 EventLabels 能力回归测试。
 *
 * ## 背景
 * 老人端「我的风险记录」此前是老人自己看不懂的界面：列表行直接贴 details 原始 JSON，
 * 时间只有 "MM-dd HH:mm"，几十条记录是同一规格的流水账。改版后复用子女端的
 * 呈现规则（分组 + 计数总览 + 同类合并 + 人话详情），把新增的公共能力放在
 * EventLabels 里由两端共用 —— 这些函数一旦退化，两端会一起坏，所以必须有回归测试。
 *
 * 注：本文件只测不依赖 org.json 的纯函数。android.jar 的 org.json 在 JVM 单测里
 * 是 stub（`unitTests.returnDefaultValues = true`），JSONObject 相关的断言没有意义，
 * 那部分靠真机验证。
 */
class EventLabelsElderReadableTest {

    @Test
    fun `dayHeader对今天与昨天给出相对分组`() {
        val now = System.currentTimeMillis()
        assertEquals("今天", EventLabels.dayHeader(now))
        assertEquals("昨天", EventLabels.dayHeader(now - 86_400_000L))
    }

    @Test
    fun `dayHeader对更早的日期给出可读月日`() {
        val older = System.currentTimeMillis() - 10 * 86_400_000L
        val header = EventLabels.dayHeader(older)
        assertTrue(header.isNotEmpty())
        assertFalse(header.startsWith("今天"))
        assertFalse(header.startsWith("昨天"))
        // 不能是 "10-01" 这种还要自己算星期几的写法
        assertTrue(header.contains("月"))
    }

    /** 今日零点必须按本机时区算：UTC 日边界在东八区会整体偏 8 小时，把早上 8 点前的记录算成昨天的 */
    @Test
    fun `todayStartMillis落在本机零点`() {
        val now = System.currentTimeMillis()
        val start = EventLabels.todayStartMillis(now)
        assertTrue(start <= now)
        assertTrue(now - start < 86_400_000L)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `sameKind按类型与级别共同判定`() {
        assertTrue(EventLabels.sameKind("LOCATION_UPDATE", "LOW", "LOCATION_UPDATE", "LOW"))
        // 同类型不同级别不合并：高危的位置更新不能淹没进低危堆里
        assertFalse(EventLabels.sameKind("LOCATION_UPDATE", "LOW", "LOCATION_UPDATE", "HIGH"))
        assertFalse(EventLabels.sameKind("CALL_RISK", "HIGH", "PAYMENT_RISK", "HIGH"))
    }

    /** 「该怎么办」只给 actionable 的类型；位置更新这种日常记录不该硬凑一条建议 */
    @Test
    fun `elderAdvice只对需要行动的类型给出建议`() {
        for (type in listOf("PAYMENT_RISK", "CALL_RISK", "COERCION_RISK", "FALL_RISK", "SOS", "REMOTE_INTERRUPT")) {
            assertTrue("$type 应给出行动建议", EventLabels.elderAdvice(type).isNotEmpty())
        }
        assertEquals("", EventLabels.elderAdvice("LOCATION_UPDATE"))
        assertEquals("", EventLabels.elderAdvice("PING_TEST"))
    }

    @Test
    fun `shortLocalTime支持毫秒时间戳`() {
        val s = EventLabels.shortLocalTime(System.currentTimeMillis())
        assertTrue(s.startsWith("今天"))
    }
}
