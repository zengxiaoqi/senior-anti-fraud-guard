package com.antifraud.guard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「跳过」按钮识别规则。
 *
 * 重点在**反向断言**：误点真实按钮（「关闭蓝牙」这类）比漏点一次广告危险得多，
 * 所以测试的大头是"这些东西不许命中"。
 */
class AdSkipMatcherTest {

    // ── 正向：真实开屏广告的常见形态 ──────────────────────────────────

    @Test
    fun `白名单文案命中`() {
        assertTrue(AdSkipMatcher.matchesText("跳过"))
        assertTrue(AdSkipMatcher.matchesText("跳过广告"))
        assertTrue(AdSkipMatcher.matchesText("跳过片头"))
        assertTrue(AdSkipMatcher.matchesText("跳过片头广告"))
        assertTrue(AdSkipMatcher.matchesText("跳过此广告"))
        assertTrue(AdSkipMatcher.matchesText("关闭广告"))
        assertTrue(AdSkipMatcher.matchesText("关闭开屏广告"))
    }

    @Test
    fun `倒计时变体命中`() {
        assertTrue(AdSkipMatcher.matchesText("跳过 5"))
        assertTrue(AdSkipMatcher.matchesText("跳过5"))
        assertTrue(AdSkipMatcher.matchesText("跳过 5s"))
        assertTrue(AdSkipMatcher.matchesText("跳过 05 秒"))
        assertTrue(AdSkipMatcher.matchesText("跳过广告 3"))
        assertTrue(AdSkipMatcher.matchesText("关闭广告 2"))
        assertTrue(AdSkipMatcher.matchesText("跳 过"))
    }

    @Test
    fun `英文跳过按钮命中`() {
        assertTrue(AdSkipMatcher.matchesText("Skip"))
        assertTrue(AdSkipMatcher.matchesText("Skip Ad"))
        assertTrue(AdSkipMatcher.matchesText("skip 5s"))
    }

    @Test
    fun `空白与尾标点被归一化`() {
        assertTrue(AdSkipMatcher.matchesText(" 跳过 "))
        assertTrue(AdSkipMatcher.matchesText("跳过 >"))
        assertTrue(AdSkipMatcher.matchesText("跳过\n"))
    }

    @Test
    fun `控件 ID 特征命中`() {
        assertTrue(AdSkipMatcher.matchesViewId("com.tt.skip.ad:id/tt_skip_iv"))
        assertTrue(AdSkipMatcher.matchesViewId("com.example:id/btn_skip"))
        assertTrue(AdSkipMatcher.matchesViewId("com.example:id/ad_close"))
        assertTrue(AdSkipMatcher.matchesViewId("com.example:id/count_down_skip"))
        assertTrue(AdSkipMatcher.matchesViewId("com.example:id/AdClose"))
    }

    // ── 反向：这些一个都不许命中 ──────────────────────────────────────

    @Test
    fun `宽泛关闭类文案不命中`() {
        // 「关闭」二字太常见：蓝牙/通知/Wi-Fi 开关都叫这个，点了就是安全事故
        assertFalse(AdSkipMatcher.matchesText("关闭"))
        assertFalse(AdSkipMatcher.matchesText("关闭蓝牙"))
        assertFalse(AdSkipMatcher.matchesText("关闭通知"))
        assertFalse(AdSkipMatcher.matchesText("关闭弹窗"))
    }

    @Test
    fun `长句里的跳过字样不命中`() {
        assertFalse(AdSkipMatcher.matchesText("跳过这一步将无法享受完整服务"))
        assertFalse(AdSkipMatcher.matchesText("跳过登录可以直接体验，但数据不会保存"))
        assertFalse(AdSkipMatcher.matchesText("您可以跳过此步骤"))
    }

    @Test
    fun `空与 null 不命中`() {
        assertFalse(AdSkipMatcher.matchesText(null))
        assertFalse(AdSkipMatcher.matchesText(""))
        assertFalse(AdSkipMatcher.matchesText("   "))
        assertFalse(AdSkipMatcher.matchesViewId(null))
        assertFalse(AdSkipMatcher.matchesViewId(""))
    }

    @Test
    fun `倒计时数字位数超限不命中`() {
        // 1~2 位是真实倒计时的形态；三位数说明是正文里的数字，不是按钮
        assertFalse(AdSkipMatcher.matchesText("跳过 100"))
    }

    @Test
    fun `相似但不相同的文案不命中`() {
        assertFalse(AdSkipMatcher.matchesText("跳转"))
        assertFalse(AdSkipMatcher.matchesText("跳过券"))
        assertFalse(AdSkipMatcher.matchesText("跳过，立即体验完整版"))
        assertFalse(AdSkipMatcher.matchesText("skipping"))
    }

    @Test
    fun `普通控件 ID 不命中`() {
        assertFalse(AdSkipMatcher.matchesViewId("com.example:id/btn_close"))
        assertFalse(AdSkipMatcher.matchesViewId("com.example:id/skipper_layout"))
        assertFalse(AdSkipMatcher.matchesViewId("com.example:id/tv_title"))
    }
}
