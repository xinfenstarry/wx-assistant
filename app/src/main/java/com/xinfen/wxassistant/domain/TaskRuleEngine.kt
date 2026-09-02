package com.xinfen.wxassistant.domain

import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDateTime
import java.util.Locale

/** Deterministic local task extraction for preview and offline fallback. */
class LocalTaskRuleEngine(
    private val deadlineParser: ChineseDeadlineParser = ChineseDeadlineParser(),
    private val deduplicator: TaskDeduplicator = TaskDeduplicator(),
) {
    fun extract(messages: List<GroupMessage>): List<ExtractedTask> =
        deduplicator.deduplicate(messages.flatMap(::extract))

    fun extract(message: GroupMessage): List<ExtractedTask> {
        return splitClauses(message.content).mapNotNull { clause ->
            extractClause(message, clause)
        }
    }

    private fun extractClause(message: GroupMessage, rawClause: String): ExtractedTask? {
        val clause = rawClause.trim()
        if (clause.isBlank() || COMPLETED_ONLY.containsMatchIn(clause)) return null

        val deadline = deadlineParser.parse(clause, message.receivedAt)
        val hasMarker = TASK_MARKER.containsMatchIn(clause)
        val hasAction = ACTION_VERB.containsMatchIn(clause)
        if (!hasMarker && !(deadline != null && hasAction)) return null

        val assignee = extractAssignee(clause)
        val title = cleanTitle(clause, deadline, assignee)
        if (title.length < 2 || title.matches(Regex("^[\\p{P}\\p{S}\\s]+$"))) return null

        val confidence = when {
            deadline != null && hasMarker && hasAction -> TaskConfidence.HIGH
            hasMarker && hasAction -> TaskConfidence.MEDIUM
            deadline != null && hasAction -> TaskConfidence.MEDIUM
            else -> TaskConfidence.LOW
        }
        val normalized = TaskDeduplicator.normalizeTitle(title)
        val id = stableId("${message.groupId}|$normalized")
        return ExtractedTask(
            id = id,
            groupId = message.groupId,
            groupName = message.groupName,
            title = title,
            assignee = assignee,
            deadline = deadline?.dateTime,
            deadlineExpression = deadline?.matchedExpression?.ifBlank { null },
            sourceMessageIds = setOf(message.id),
            createdAt = message.receivedAt,
            confidence = confidence,
        )
    }

    private fun splitClauses(content: String): List<String> = content
        .split(CLAUSE_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun extractAssignee(text: String): String? {
        AT_ASSIGNEE.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        OWNER_LABEL.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        OWNED_BY.find(text)?.groupValues?.get(1)?.trim()?.let { return it }
        REQUESTED_PERSON.find(text)?.groupValues?.get(1)?.trim()?.let { candidate ->
            if (candidate !in GENERIC_ASSIGNEES) return candidate
        }
        return null
    }

    private fun cleanTitle(
        original: String,
        deadline: DeadlineParseResult?,
        assignee: String?,
    ): String {
        var title = original
        val temporalRanges = listOfNotNull(deadline?.dateRange, deadline?.timeRange)
            .distinct()
            .sortedByDescending { it.start }
        temporalRanges.forEach { range ->
            if (range.endExclusive <= title.length) {
                title = title.removeRange(range.start, range.endExclusive)
            }
        }

        title = title
            .replace(LIST_PREFIX, "")
            .replace(TASK_LABEL, "")
            .replace(AT_ASSIGNEE, "")
            .replace(OWNER_LABEL, "")
            .replace(Regex("(?i)\\b(?:deadline|ddl)\\b"), "")
            .replace(Regex("(?:最晚|截止|请于|请在|务必于|务必在|于|在)?\\s*(?:前|之前|以前)"), "")
            .replace(Regex("^[\\s:：,，、;；-]*(?:请|麻烦|请记得|记得|务必|需要|安排)\\s*"), "")
            .replace(Regex("[\\s,，、:：;；-]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '，', '、', ':', '：', ';', '；', '-', '。')

        if (!assignee.isNullOrBlank()) {
            title = title
                .replace(Regex("^${Regex.escape(assignee)}\\s*(?:负责|跟进|处理)?\\s*"), "")
                .trim()
        }
        return title.ifBlank { original.trim() }
    }

    private fun stableId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(10).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private val CLAUSE_SEPARATOR = Regex("[\\n\\r。；;！？!?]+")
        private val LIST_PREFIX = Regex("^\\s*(?:[-*•]|\\d{1,3}[.)、])\\s*")
        private val TASK_LABEL = Regex("^\\s*(?i:待办|任务|todo)\\s*[:：]?\\s*")
        private val TASK_MARKER = Regex("(?i)待办|任务|todo|请|麻烦|记得|务必|需要|安排|负责|截止|最晚")
        private val ACTION_VERB = Regex(
            "提交|发送|回复|确认|准备|整理|更新|发布|完成|跟进|处理|负责|参加|开会|交付|检查|审核|填写|汇报|联系|提醒|预约|同步|提供|修改|修复|上线|部署",
        )
        private val COMPLETED_ONLY = Regex("^(?:已|已经|刚刚)(?:完成|提交|发送|处理|回复|发布|更新)|(?:完成|提交|处理)了[。！!]?$" )
        private val AT_ASSIGNEE = Regex(
            "@([\\p{L}\\p{N}_-]{1,32}?)(?=\\s*(?:在|于|负责|跟进|处理|完成|提交|整理|发送|确认|截止|最晚)|[,，。；;\\s]|$)",
        )
        private val OWNER_LABEL = Regex("负责人\\s*[:：]\\s*([^,，。；;\\s]{1,24})")
        private val OWNED_BY = Regex("由\\s*([^,，。；;\\s]{1,24})\\s*(?:负责|跟进|处理|完成)")
        private val REQUESTED_PERSON = Regex("请\\s*([^,，。；;\\s]{1,12}?)\\s*(?:在|于|负责|跟进|处理|完成|提交|整理|发送|确认)")
        private val GENERIC_ASSIGNEES = setOf("大家", "各位", "所有人", "相关同学", "同学们")
    }
}

class TaskDeduplicator {
    fun deduplicate(tasks: List<ExtractedTask>): List<ExtractedTask> {
        if (tasks.size < 2) return tasks.sortedBy { it.createdAt }

        val merged = linkedMapOf<String, ExtractedTask>()
        tasks.sortedBy { it.updatedAt }.forEach { task ->
            val key = "${task.groupId}|${normalizeTitle(task.title)}"
            val previous = merged[key]
            merged[key] = if (previous == null) task else merge(previous, task)
        }
        return merged.values.sortedWith(compareBy<ExtractedTask> { it.deadline ?: LocalDateTime.MAX }.thenBy { it.createdAt })
    }

    private fun merge(older: ExtractedTask, newer: ExtractedTask): ExtractedTask {
        val latest = if (newer.updatedAt >= older.updatedAt) newer else older
        val earliest = if (older.createdAt <= newer.createdAt) older else newer
        val bestConfidence = if (older.confidence.rank >= newer.confidence.rank) older.confidence else newer.confidence
        return latest.copy(
            id = older.id,
            assignee = latest.assignee ?: earliest.assignee,
            deadline = latest.deadline ?: earliest.deadline,
            deadlineExpression = latest.deadlineExpression ?: earliest.deadlineExpression,
            sourceMessageIds = older.sourceMessageIds + newer.sourceMessageIds,
            createdAt = minOf(older.createdAt, newer.createdAt),
            updatedAt = maxOf(older.updatedAt, newer.updatedAt),
            confidence = bestConfidence,
        )
    }

    companion object {
        fun normalizeTitle(value: String): String = Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("^(?:请|麻烦|记得|务必|需要|安排|待办|任务|todo)[:：]?"), "")
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }
}
