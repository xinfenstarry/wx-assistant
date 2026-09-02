package com.xinfen.wxassistant.domain

import java.time.LocalDateTime

/**
 * Small deterministic local preview. The final summary is composed by [DeepSeekPromptComposer]
 * and sent to the user's configured DeepSeek model.
 */
class LocalMessageSummarizer(
    private val taskRuleEngine: LocalTaskRuleEngine = LocalTaskRuleEngine(),
    private val maxHighlights: Int = 5,
) {
    init {
        require(maxHighlights > 0)
    }

    fun summarize(
        group: GroupChatRef,
        messages: List<GroupMessage>,
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime,
    ): LocalGroupSummary {
        require(!rangeEnd.isBefore(rangeStart)) { "rangeEnd cannot be before rangeStart" }
        val inRange = messages
            .asSequence()
            .filter { it.groupId == group.id }
            .filter { !it.receivedAt.isBefore(rangeStart) && !it.receivedAt.isAfter(rangeEnd) }
            .sortedBy { it.receivedAt }
            .toList()
        val tasks = taskRuleEngine.extract(inRange)
        val highlights = inRange
            .distinctBy { normalizeForHighlight(it.content) }
            .map { it to highlightScore(it.content) }
            .sortedWith(compareByDescending<Pair<GroupMessage, Int>> { it.second }.thenByDescending { it.first.receivedAt })
            .take(maxHighlights)
            .sortedBy { it.first.receivedAt }
            .map { (message, _) ->
                val sender = message.sender?.takeIf { it.isNotBlank() } ?: "未知发送者"
                "$sender：${message.content.trim().replace(Regex("\\s+"), " ").take(120)}"
            }
        val participantCount = inRange.mapNotNull { it.sender?.trim()?.takeIf(String::isNotBlank) }.distinct().size
        val overview = "${group.name}在所选时间内共${inRange.size}条消息，${participantCount}人发言，本地识别${tasks.size}项待办。"
        return LocalGroupSummary(
            groupId = group.id,
            groupName = group.name,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            messageCount = inRange.size,
            participantCount = participantCount,
            overview = overview,
            highlights = highlights,
            tasks = tasks,
        )
    }

    private fun highlightScore(content: String): Int {
        var score = 0
        if (DEADLINE_HINT.containsMatchIn(content)) score += 4
        if (TASK_HINT.containsMatchIn(content)) score += 3
        if (DECISION_HINT.containsMatchIn(content)) score += 2
        if ('@' in content) score += 1
        if (content.length >= 20) score += 1
        return score
    }

    private fun normalizeForHighlight(content: String): String = content
        .lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    companion object {
        private val DEADLINE_HINT = Regex("截止|最晚|今天|明天|后天|周[一二三四五六日天]|\\d{1,2}月\\d{1,2}[日号]|\\d{1,2}[:：]\\d{2}")
        private val TASK_HINT = Regex("(?i)待办|任务|todo|请|麻烦|务必|需要|安排|提交|完成|跟进|处理")
        private val DECISION_HINT = Regex("决定|确定|结论|方案|改为|取消|通过|发布|上线|风险|阻塞")
    }
}
