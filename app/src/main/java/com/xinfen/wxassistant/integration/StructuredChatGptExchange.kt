package com.xinfen.wxassistant.integration

import com.xinfen.wxassistant.data.ImportedDeepSeekResult
import com.xinfen.wxassistant.data.PlanConfidence
import com.xinfen.wxassistant.data.PlanDraft
import com.xinfen.wxassistant.data.PlanItem
import com.xinfen.wxassistant.data.PlanItemStatus
import com.xinfen.wxassistant.data.PlanItemType
import com.xinfen.wxassistant.data.SummaryDraft
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Adds a stable response schema and the current plan to the human-readable summary prompt. */
object StructuredDeepSeekPrompt {
    fun build(basePrompt: String, currentPlan: List<PlanItem>, exchangeToken: String): String {
        require(EXCHANGE_TOKEN.matches(exchangeToken)) { "Invalid exchange token" }
        val planJson = JSONArray().apply {
            currentPlan.forEach { item ->
                put(
                    JSONObject().apply {
                        put("stableKey", item.stableKey)
                        put("groupName", safeData(item.groupName))
                        put("title", safeData(item.title))
                        put("type", item.type.name.lowercase())
                        put("assignee", item.assignee?.let(::safeData) ?: JSONObject.NULL)
                        put(
                            "deadline",
                            item.deadlineAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL,
                        )
                        put("status", item.status.name.lowercase())
                    },
                )
            }
        }
        return """
            ${basePrompt.trim()}

            <current_plan>
            $planJson
            </current_plan>

            计划更新规则：
            - current_plan 是 App 当前计划。只有新消息明确说明变更时才更新旧条目；未提及的旧条目不要删除或取消。
            - 同一事项必须沿用 current_plan 中的 stableKey；新事项的 stableKey 写 null，由 App 生成。
            - 课程取消、任务取消写 status="cancelled"；明确完成写 "completed"；其他写 "active"。
            - deadline 必须换算为带时区的 ISO 8601 绝对时间，例如 2026-09-05T23:59:00+08:00；没有明确截止时间写 null。
            - exchangeToken 必须逐字复制为 "$exchangeToken"，不得更改。
            - 摘要和 Markdown 任务表之后，必须再输出且只输出一个 ```json 代码块，严格使用下列结构；所有字段都必须存在：
            {
              "schemaVersion": 1,
              "exchangeToken": "$exchangeToken",
              "generatedAt": "2026-09-01T20:00:00+08:00",
              "groups": [
                {
                  "groupName": "群名",
                  "summary": ["要点1", "要点2"],
                  "planItems": [
                    {
                      "stableKey": null,
                      "title": "事项名称",
                      "type": "course|assignment|task|event|other",
                      "assignee": null,
                      "deadline": null,
                      "status": "active|cancelled|completed",
                      "source": "发送者 / 消息时间 / 原文依据",
                      "confidence": "high|medium|low"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun safeData(value: String): String = value
        .replace('<', '＜')
        .replace('>', '＞')

    private val EXCHANGE_TOKEN = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )
}

class DeepSeekResultParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(sharedText: String, now: Long = System.currentTimeMillis()): ImportedDeepSeekResult {
        val raw = sharedText.trim()
        if (raw.isBlank()) throw ResultParseException("分享内容为空")
        if (raw.length > MAX_RAW_RESPONSE_CHARS) {
            throw ResultParseException("内容过长，请只导入本次 DeepSeek 的完整回复")
        }
        val jsonText = extractJson(raw)
        val root = try {
            JSONObject(jsonText)
        } catch (error: JSONException) {
            throw ResultParseException("没有找到有效的 DeepSeek 结构化结果", error)
        }
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            throw ResultParseException("结果版本不受支持，请使用 App 生成的新提示词重新总结")
        }
        val exchangeToken = root.nullableString("exchangeToken")
            ?.takeIf { EXCHANGE_TOKEN.matches(it) }
            ?: throw ResultParseException("结果缺少有效的交换凭证，请使用 App 生成的 DeepSeek 提示词重新整理")

        val summaries = mutableListOf<SummaryDraft>()
        val planItems = mutableListOf<PlanDraft>()
        val groups = root.optJSONArray("groups")
            ?: throw ResultParseException("结果缺少 groups")
        for (groupIndex in 0 until minOf(groups.length(), MAX_GROUPS)) {
            val group = groups.optJSONObject(groupIndex) ?: continue
            val groupName = group.optString("groupName").trim().take(MAX_GROUP_NAME_CHARS)
            if (groupName.isBlank()) continue
            val bullets = parseSummary(group.opt("summary"))
            if (bullets.isNotEmpty()) summaries += SummaryDraft(groupName, bullets)

            val items = group.optJSONArray("planItems") ?: JSONArray()
            for (itemIndex in 0 until minOf(items.length(), MAX_ITEMS_PER_GROUP)) {
                val item = items.optJSONObject(itemIndex) ?: continue
                val title = item.optString("title").trim().take(MAX_TITLE_CHARS)
                if (title.isBlank()) continue
                val stableKey = item.nullableString("stableKey")
                    ?.take(MAX_STABLE_KEY_CHARS)
                    ?.takeIf { SAFE_STABLE_KEY.matches(it) }
                val rawDeadline = item.nullableString("deadline")
                val deadlineAt = when {
                    rawDeadline == null || rawDeadline.equals("null", true) || rawDeadline == "未明确" -> null
                    else -> parseDeadline(rawDeadline) ?: continue
                }
                val rawStatus = item.nullableString("status")?.trim()?.lowercase() ?: continue
                if (rawStatus !in VALID_STATUSES) continue
                planItems += PlanDraft(
                    stableKey = stableKey,
                    groupName = groupName,
                    title = title,
                    type = PlanItemType.fromWire(item.nullableString("type")),
                    assignee = item.nullableString("assignee")?.take(MAX_ASSIGNEE_CHARS),
                    deadlineAt = deadlineAt,
                    status = PlanItemStatus.fromWire(rawStatus),
                    source = item.nullableString("source")?.take(MAX_SOURCE_CHARS) ?: "未提供来源",
                    confidence = PlanConfidence.fromWire(item.nullableString("confidence")),
                )
            }
        }
        if (summaries.isEmpty() && planItems.isEmpty()) {
            throw ResultParseException("结构化结果中没有可导入的摘要或计划")
        }
        val generatedAt = parseDeadline(root.nullableString("generatedAt")) ?: now
        return ImportedDeepSeekResult(
            exchangeToken = exchangeToken,
            summaries = summaries,
            planItems = planItems,
            rawResponse = raw,
            generatedAt = generatedAt,
        )
    }

    private fun extractJson(text: String): String {
        JSON_FENCE.findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.let { return it.trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        throw ResultParseException("未找到 JSON 结果，请粘贴 DeepSeek 的完整回复")
    }

    private fun parseSummary(value: Any?): List<String> = when (value) {
        is JSONArray -> buildList {
            for (index in 0 until minOf(value.length(), MAX_SUMMARY_BULLETS)) {
                value.optString(index).trim().take(MAX_SUMMARY_BULLET_CHARS)
                    .takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }

        is String -> value.lineSequence()
            .map { it.trim().trimStart('-', '*', '•').trim() }
            .filter { it.isNotBlank() }
            .take(MAX_SUMMARY_BULLETS)
            .map { it.take(MAX_SUMMARY_BULLET_CHARS) }
            .toList()

        else -> emptyList()
    }

    private fun parseDeadline(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim()
        if (normalized.equals("null", true) || normalized == "未明确") return null
        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(normalized).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(normalized).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(normalized).atZone(zoneId).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_GROUPS = 50
        private const val MAX_ITEMS_PER_GROUP = 200
        private const val MAX_SUMMARY_BULLETS = 30
        private const val MAX_GROUP_NAME_CHARS = 160
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_STABLE_KEY_CHARS = 120
        private const val MAX_ASSIGNEE_CHARS = 120
        private const val MAX_SOURCE_CHARS = 600
        private const val MAX_SUMMARY_BULLET_CHARS = 800
        private const val MAX_RAW_RESPONSE_CHARS = 200_000
        private val SAFE_STABLE_KEY = Regex("[A-Za-z0-9._:-]{3,120}")
        private val EXCHANGE_TOKEN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private val VALID_STATUSES = setOf(
            "active", "进行中", "有效",
            "cancelled", "canceled", "取消", "已取消",
            "completed", "done", "完成", "已完成",
        )
        private val JSON_FENCE = Regex("(?s)```(?:json)?\\s*(\\{.*?})\\s*```")
    }
}

class ResultParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
