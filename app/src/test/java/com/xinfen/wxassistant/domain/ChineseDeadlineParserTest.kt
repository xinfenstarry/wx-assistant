package com.xinfen.wxassistant.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseDeadlineParserTest {
    private val parser = ChineseDeadlineParser()
    private val reference = LocalDateTime.of(2026, 9, 1, 10, 0) // Tuesday

    @Test
    fun `parses relative day and Chinese afternoon time`() {
        val result = parser.parse("请在明天下午3点30分前提交", reference)!!

        assertEquals(LocalDateTime.of(2026, 9, 2, 15, 30), result.dateTime)
        assertEquals(DateExpressionKind.RELATIVE_DAY, result.dateExpressionKind)
        assertTrue(result.timeWasExplicit)
    }

    @Test
    fun `parses weekday and rolls elapsed unqualified weekday`() {
        val friday = parser.parse("周五 18:00 截止", reference)!!
        val nextTuesday = parser.parse("周二上午9点前完成", reference)!!

        assertEquals(LocalDateTime.of(2026, 9, 4, 18, 0), friday.dateTime)
        assertEquals(LocalDateTime.of(2026, 9, 8, 9, 0), nextTuesday.dateTime)
    }

    @Test
    fun `parses month day and explicit year month day`() {
        val newYearReference = LocalDateTime.of(2026, 12, 31, 10, 0)
        val monthDay = parser.parse("1月2日上午9:05交付", newYearReference)!!
        val fullDate = parser.parse("截止2027年2月3日晚上8点", reference)!!

        assertEquals(LocalDateTime.of(2027, 1, 2, 9, 5), monthDay.dateTime)
        assertEquals(LocalDateTime.of(2027, 2, 3, 20, 0), fullDate.dateTime)
        assertEquals(DateExpressionKind.FULL_DATE, fullDate.dateExpressionKind)
    }

    @Test
    fun `uses default evening time when only date is given`() {
        val result = parser.parse("后天完成", reference)!!

        assertEquals(LocalDateTime.of(2026, 9, 3, 18, 0), result.dateTime)
        assertFalse(result.timeWasExplicit)
    }

    @Test
    fun `bare elapsed time resolves to next occurrence`() {
        val result = parser.parse("上午9点提交", reference)!!

        assertEquals(LocalDateTime.of(2026, 9, 2, 9, 0), result.dateTime)
    }

    @Test
    fun `rejects text with no temporal expression`() {
        assertNull(parser.parse("收到，谢谢", reference))
    }
}
