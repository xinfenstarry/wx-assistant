package com.xinfen.wxassistant.data

enum class PlanItemType {
    COURSE,
    ASSIGNMENT,
    TASK,
    EVENT,
    OTHER;

    companion object {
        fun fromWire(value: String?): PlanItemType = when (value?.trim()?.lowercase()) {
            "course", "课程" -> COURSE
            "assignment", "homework", "作业" -> ASSIGNMENT
            "task", "任务" -> TASK
            "event", "活动", "事件" -> EVENT
            else -> OTHER
        }
    }
}

enum class PlanItemStatus {
    ACTIVE,
    CANCELLED,
    COMPLETED;

    companion object {
        fun fromWire(value: String?): PlanItemStatus = when (value?.trim()?.lowercase()) {
            "cancelled", "canceled", "取消", "已取消" -> CANCELLED
            "completed", "done", "完成", "已完成" -> COMPLETED
            else -> ACTIVE
        }
    }
}

enum class PlanConfidence {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromWire(value: String?): PlanConfidence = when (value?.trim()?.lowercase()) {
            "high", "高" -> HIGH
            "medium", "中" -> MEDIUM
            else -> LOW
        }
    }
}

enum class PlanChangeKind {
    CREATED,
    CANCELLED,
    REOPENED,
    COMPLETED,
    DEADLINE_EARLIER,
    DEADLINE_LATER,
    DEADLINE_ADDED,
    DEADLINE_REMOVED,
    ASSIGNEE_CHANGED,
    DETAILS_UPDATED,
}

data class PlanDraft(
    val stableKey: String?,
    val groupName: String,
    val title: String,
    val type: PlanItemType,
    val assignee: String?,
    val deadlineAt: Long?,
    val status: PlanItemStatus,
    val source: String,
    val confidence: PlanConfidence,
)

data class SummaryDraft(
    val groupName: String,
    val bullets: List<String>,
)

data class ImportedDeepSeekResult(
    val exchangeToken: String,
    val summaries: List<SummaryDraft>,
    val planItems: List<PlanDraft>,
    val rawResponse: String,
    val generatedAt: Long,
)

data class SavedSummary(
    val groupName: String,
    val text: String,
    val generatedAt: Long,
    val importedAt: Long,
)

data class PlanItem(
    val stableKey: String,
    val groupName: String,
    val title: String,
    val type: PlanItemType,
    val assignee: String?,
    val deadlineAt: Long?,
    val status: PlanItemStatus,
    val source: String,
    val confidence: PlanConfidence,
    val updatedAt: Long,
)

data class PlanChange(
    val id: Long = 0L,
    val stableKey: String,
    val groupName: String,
    val title: String,
    val kind: PlanChangeKind,
    val oldValue: String?,
    val newValue: String?,
    val detectedAt: Long,
    val notified: Boolean = false,
)

data class MergeReport(
    val summariesUpdated: Int,
    val planItemsUpdated: Int,
    val changes: List<PlanChange>,
)
