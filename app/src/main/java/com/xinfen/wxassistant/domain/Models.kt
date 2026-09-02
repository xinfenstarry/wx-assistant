package com.xinfen.wxassistant.domain

import java.time.LocalDateTime

/** A lightweight reference to a WeChat group selected by the user. */
data class GroupChatRef(
    val id: String,
    val name: String,
) {
    init {
        require(id.isNotBlank()) { "Group id must not be blank" }
        require(name.isNotBlank()) { "Group name must not be blank" }
    }
}

/** Where a message was observed. A message may be observed by both sources. */
enum class MessageSource {
    ACCESSIBILITY,
    NOTIFICATION,
    MANUAL_IMPORT,
}

/**
 * A single immutable group-message observation.
 *
 * [id] should be stable for a captured observation. Duplicate content observed through
 * accessibility and notification APIs may have different ids; task deduplication handles that.
 */
data class GroupMessage(
    val id: String,
    val groupId: String,
    val groupName: String,
    val sender: String?,
    val content: String,
    val receivedAt: LocalDateTime,
    val source: MessageSource,
) {
    init {
        require(id.isNotBlank()) { "Message id must not be blank" }
        require(groupId.isNotBlank()) { "Group id must not be blank" }
        require(groupName.isNotBlank()) { "Group name must not be blank" }
        require(content.isNotBlank()) { "Message content must not be blank" }
    }
}

enum class TaskConfidence(val rank: Int, val displayName: String) {
    LOW(1, "低"),
    MEDIUM(2, "中"),
    HIGH(3, "高"),
}

/** A task extracted locally for diagnostics; DeepSeek remains the final organizer. */
data class ExtractedTask(
    val id: String,
    val groupId: String,
    val groupName: String,
    val title: String,
    val assignee: String?,
    val deadline: LocalDateTime?,
    val deadlineExpression: String?,
    val sourceMessageIds: Set<String>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime = createdAt,
    val confidence: TaskConfidence,
) {
    init {
        require(id.isNotBlank()) { "Task id must not be blank" }
        require(groupId.isNotBlank()) { "Group id must not be blank" }
        require(groupName.isNotBlank()) { "Group name must not be blank" }
        require(title.isNotBlank()) { "Task title must not be blank" }
        require(sourceMessageIds.isNotEmpty()) { "A task must have at least one source message" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt cannot be before createdAt" }
    }
}

/** Deterministic local preview used only when inspecting captured data locally. */
data class LocalGroupSummary(
    val groupId: String,
    val groupName: String,
    val rangeStart: LocalDateTime,
    val rangeEnd: LocalDateTime,
    val messageCount: Int,
    val participantCount: Int,
    val overview: String,
    val highlights: List<String>,
    val tasks: List<ExtractedTask>,
)

data class ComposedPrompt(
    val text: String,
    val totalEligibleMessageCount: Int,
    val includedMessageCount: Int,
    val truncated: Boolean,
)
