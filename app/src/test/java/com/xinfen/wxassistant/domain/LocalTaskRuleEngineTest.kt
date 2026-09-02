package com.xinfen.wxassistant.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskRuleEngineTest {
    private val now = LocalDateTime.of(2026, 9, 1, 10, 0)

    @Test
    fun `extracts assignee deadline and action`() {
        val message = message(
            id = "a1",
            source = MessageSource.NOTIFICATION,
            content = "请@张三在明天下午5点前提交发布清单",
        )

        val task = LocalTaskRuleEngine().extract(message).single()

        assertEquals("张三", task.assignee)
        assertEquals(LocalDateTime.of(2026, 9, 2, 17, 0), task.deadline)
        assertTrue(task.title.contains("提交发布清单"))
        assertEquals(TaskConfidence.HIGH, task.confidence)
    }

    @Test
    fun `deduplicates same task captured from notification and accessibility`() {
        val messages = listOf(
            message("notification-1", MessageSource.NOTIFICATION, "待办：明天下午5点提交发布清单"),
            message("accessibility-1", MessageSource.ACCESSIBILITY, "待办：明天下午5点提交发布清单")
                .copy(receivedAt = now.plusSeconds(2)),
        )

        val task = LocalTaskRuleEngine().extract(messages).single()

        assertEquals(setOf("notification-1", "accessibility-1"), task.sourceMessageIds)
        assertEquals(LocalDateTime.of(2026, 9, 2, 17, 0), task.deadline)
    }

    @Test
    fun `ignores ordinary chat and completed status`() {
        val messages = listOf(
            message("normal", MessageSource.NOTIFICATION, "大家中午吃什么？"),
            message("done", MessageSource.NOTIFICATION, "已经提交发布清单"),
        )

        assertTrue(LocalTaskRuleEngine().extract(messages).isEmpty())
    }

    private fun message(id: String, source: MessageSource, content: String) = GroupMessage(
        id = id,
        groupId = "project",
        groupName = "项目群",
        sender = "产品经理",
        content = content,
        receivedAt = now,
        source = source,
    )
}
