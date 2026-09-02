package com.xinfen.wxassistant.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMessageSummarizerTest {
    @Test
    fun `builds deterministic fallback preview for one group`() {
        val start = LocalDateTime.of(2026, 9, 1, 8, 0)
        val messages = listOf(
            GroupMessage("1", "g", "项目群", "张三", "方案已经通过", start.plusHours(1), MessageSource.NOTIFICATION),
            GroupMessage("2", "g", "项目群", "李四", "请明天下午5点提交发布清单", start.plusHours(2), MessageSource.ACCESSIBILITY),
            GroupMessage("3", "other", "其他群", "王五", "无关消息", start.plusHours(2), MessageSource.NOTIFICATION),
        )

        val summary = LocalMessageSummarizer().summarize(
            GroupChatRef("g", "项目群"),
            messages,
            start,
            start.plusHours(12),
        )

        assertEquals(2, summary.messageCount)
        assertEquals(2, summary.participantCount)
        assertEquals(1, summary.tasks.size)
        assertTrue(summary.overview.contains("本地识别1项待办"))
        assertTrue(summary.highlights.any { it.contains("发布清单") })
    }
}
