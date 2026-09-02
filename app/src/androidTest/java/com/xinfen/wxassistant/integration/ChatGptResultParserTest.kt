package com.xinfen.wxassistant.integration

import com.xinfen.wxassistant.data.PlanItemStatus
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekResultParserTest {
    @Test
    fun parsesSummaryAndAbsoluteDeadline() {
        val response = """
            ## 要点摘要
            - 作业截止提前

            ```json
            {
              "schemaVersion": 1,
              "exchangeToken": "12345678-1234-1234-1234-123456789abc",
              "generatedAt": "2026-09-01T20:00:00+08:00",
              "groups": [{
                "groupName": "高数群",
                "summary": ["第三章作业截止提前"],
                "planItems": [{
                  "stableKey": "plan_math_3",
                  "title": "第三章作业",
                  "type": "assignment",
                  "assignee": null,
                  "deadline": "2026-09-03T20:00:00+08:00",
                  "status": "active",
                  "source": "老师 / 2026-09-01 18:00 / 周四前交",
                  "confidence": "high"
                }]
              }]
            }
            ```
        """.trimIndent()

        val result = DeepSeekResultParser(ZoneId.of("Asia/Shanghai")).parse(response)

        assertEquals("高数群", result.summaries.single().groupName)
        assertEquals(PlanItemStatus.ACTIVE, result.planItems.single().status)
        assertEquals(
            OffsetDateTime.parse("2026-09-03T20:00:00+08:00").toInstant().toEpochMilli(),
            result.planItems.single().deadlineAt,
        )
    }

    @Test
    fun skipsPlanItemWithRelativeDeadlineInsteadOfRemovingOldDeadline() {
        val response = """
            ```json
            {
              "schemaVersion": 1,
              "exchangeToken": "12345678-1234-1234-1234-123456789abc",
              "generatedAt": "2026-09-01T20:00:00+08:00",
              "groups": [{
                "groupName": "高数群",
                "summary": ["截止时间表达仍不明确"],
                "planItems": [{
                  "stableKey": "plan_math_3",
                  "title": "第三章作业",
                  "type": "assignment",
                  "assignee": null,
                  "deadline": "明天晚上",
                  "status": "active",
                  "source": "同学转述",
                  "confidence": "low"
                }]
              }]
            }
            ```
        """.trimIndent()

        val result = DeepSeekResultParser(ZoneId.of("Asia/Shanghai")).parse(response)

        assertTrue(result.planItems.isEmpty())
        assertEquals(1, result.summaries.size)
    }
}
