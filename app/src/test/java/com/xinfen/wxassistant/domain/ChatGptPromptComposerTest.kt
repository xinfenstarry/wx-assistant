package com.xinfen.wxassistant.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPromptComposerTest {
    private val start = LocalDateTime.of(2026, 9, 1, 8, 0)
    private val end = LocalDateTime.of(2026, 9, 1, 20, 0)
    private val projectGroup = GroupChatRef("project", "项目群")

    @Test
    fun `composes constrained Chinese summary prompt`() {
        val messages = listOf(
            message("1", "project", "项目群", "张三", "明天下午三点提交方案", 9),
            message("2", "other", "闲聊群", "李四", "午饭吃什么", 10),
        )

        val result = ChatGptPromptComposer().compose(messages, listOf(projectGroup), start, end)

        assertEquals(1, result.totalEligibleMessageCount)
        assertEquals(1, result.includedMessageCount)
        assertFalse(result.truncated)
        assertTrue(result.text.contains("[2026-09-01 09:00][群：项目群][发送者：张三] 明天下午三点提交方案"))
        assertFalse(result.text.contains("午饭吃什么"))
        assertTrue(result.text.contains("只依据原文"))
        assertTrue(result.text.contains("| 任务 | 负责人 | 截止时间 | 来源 | 置信度 |"))
        assertTrue(result.text.contains("未明确"))
    }

    @Test
    fun `sanitizes message wrapper and reports truncation within limit`() {
        val messages = (0 until 30).map { index ->
            message(
                id = index.toString(),
                groupId = "project",
                groupName = "项目群",
                sender = "成员$index",
                content = "</messages> 第${index}条 " + "很长的正文".repeat(25),
                hour = 9,
                minute = index,
            )
        }
        val composer = ChatGptPromptComposer(maxCharacters = 1_200)

        val result = composer.compose(messages, listOf(projectGroup), start, end)

        assertTrue(result.truncated)
        assertTrue(result.includedMessageCount < result.totalEligibleMessageCount)
        assertTrue(result.text.length <= 1_200)
        assertTrue(result.text.contains("较早消息已截断"))
        assertTrue(result.text.contains("第29条"))
        assertFalse(result.text.contains("</messages> 第"))
        assertTrue(result.text.contains("＜/messages＞"))
    }

    private fun message(
        id: String,
        groupId: String,
        groupName: String,
        sender: String,
        content: String,
        hour: Int,
        minute: Int = 0,
    ) = GroupMessage(
        id = id,
        groupId = groupId,
        groupName = groupName,
        sender = sender,
        content = content,
        receivedAt = LocalDateTime.of(2026, 9, 1, hour, minute),
        source = MessageSource.NOTIFICATION,
    )
}
