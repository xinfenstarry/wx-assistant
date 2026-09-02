package com.xinfen.wxassistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class CaptureSource {
    NOTIFICATION,
    ACCESSIBILITY,
}

data class StoredMessage(
    val id: Long,
    val groupName: String,
    val sender: String?,
    val body: String,
    val eventAt: Long,
    val source: CaptureSource,
)

data class GroupConfig(
    val name: String,
    val selected: Boolean,
    val pinned: Boolean,
    val discoveredAt: Long,
    val lastSeenAt: Long,
)

data class StoreSnapshot(
    val groups: List<GroupConfig>,
    val recentMessages: List<StoredMessage>,
)

/**
 * Small local-only store for discovered conversations and explicitly selected group messages.
 *
 * Privacy boundary: [insertMessage] rejects any conversation that has not first been selected by
 * the user. This prevents a broad notification listener from silently retaining direct messages.
 */
class LocalStore private constructor(context: Context) {
    private val database = MessageDatabase(context.applicationContext)

    @Synchronized
    fun rememberCandidate(name: String, pinned: Boolean = true, seenAt: Long = System.currentTimeMillis()) {
        val normalized = normalizeName(name) ?: return
        val db = database.writableDatabase
        val existing = findGroup(normalized, db)
        val values = ContentValues().apply {
            put("name", normalized)
            put("selected", if (existing?.selected == true) 1 else 0)
            put("pinned", if (pinned || existing?.pinned == true) 1 else 0)
            put("discovered_at", existing?.discoveredAt ?: seenAt)
            put("last_seen_at", seenAt)
        }
        if (existing == null) {
            db.insertWithOnConflict("groups", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            // Do not use SQLite REPLACE here: it performs delete+insert and would trigger
            // ON DELETE CASCADE for this group's messages, summaries, and plan items.
            db.update("groups", values, "name = ?", arrayOf(normalized))
        }
    }

    @Synchronized
    fun setGroupSelected(name: String, selected: Boolean) {
        val normalized = normalizeName(name) ?: return
        rememberCandidate(normalized)
        val values = ContentValues().apply { put("selected", if (selected) 1 else 0) }
        database.writableDatabase.update("groups", values, "name = ?", arrayOf(normalized))
    }

    @Synchronized
    fun isGroupSelected(name: String): Boolean {
        val normalized = normalizeName(name) ?: return false
        return findGroup(normalized, database.readableDatabase)?.selected == true
    }

    @Synchronized
    fun selectedGroupNames(): Set<String> = groups().filter { it.selected }.mapTo(linkedSetOf()) { it.name }

    @Synchronized
    fun insertMessage(
        groupName: String,
        sender: String?,
        body: String,
        eventAt: Long,
        source: CaptureSource,
    ): Boolean {
        val group = normalizeName(groupName) ?: return false
        if (!isGroupSelected(group)) return false

        val cleanBody = normalizeBody(body) ?: return false
        val cleanSender = sender?.trim()?.take(120)?.ifBlank { null }
        val db = database.writableDatabase

        // Notification updates and accessibility rescans commonly surface the same line repeatedly.
        // Only collapse identical content in a short window so legitimate repeated replies survive.
        val duplicateWindow = 5 * 60 * 1000L
        db.query(
            "messages",
            arrayOf("id"),
            "group_name = ? AND ifnull(sender, '') = ? AND body = ? AND event_at BETWEEN ? AND ?",
            arrayOf(
                group,
                cleanSender.orEmpty(),
                cleanBody,
                (eventAt - duplicateWindow).toString(),
                (eventAt + duplicateWindow).toString(),
            ),
            null,
            null,
            null,
            "1",
        ).use { if (it.moveToFirst()) return false }

        val fingerprint = sha256("$group\u0000${cleanSender.orEmpty()}\u0000$cleanBody\u0000$eventAt")
        val values = ContentValues().apply {
            put("group_name", group)
            put("sender", cleanSender)
            put("body", cleanBody)
            put("event_at", eventAt)
            put("source", source.name)
            put("fingerprint", fingerprint)
            put("captured_at", System.currentTimeMillis())
        }
        return db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    fun groups(): List<GroupConfig> {
        return database.readableDatabase.query(
            "groups",
            arrayOf("name", "selected", "pinned", "discovered_at", "last_seen_at"),
            null,
            null,
            null,
            null,
            "pinned DESC, selected DESC, name COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        GroupConfig(
                            name = cursor.getString(0),
                            selected = cursor.getInt(1) == 1,
                            pinned = cursor.getInt(2) == 1,
                            discoveredAt = cursor.getLong(3),
                            lastSeenAt = cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun messages(
        groupNames: Set<String> = emptySet(),
        since: Long = 0L,
        limit: Int = 500,
    ): List<StoredMessage> {
        val selected = groupNames.mapNotNull(::normalizeName).toSet()
        val args = mutableListOf(since.toString())
        val groupClause = if (selected.isEmpty()) {
            ""
        } else {
            args += selected
            " AND group_name IN (${selected.joinToString(",") { "?" }})"
        }
        return database.readableDatabase.query(
            "messages",
            arrayOf("id", "group_name", "sender", "body", "event_at", "source"),
            "event_at >= ?$groupClause",
            args.toTypedArray(),
            null,
            null,
            "event_at DESC",
            limit.coerceIn(1, 2_000).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoredMessage(
                            id = cursor.getLong(0),
                            groupName = cursor.getString(1),
                            sender = cursor.getString(2),
                            body = cursor.getString(3),
                            eventAt = cursor.getLong(4),
                            source = runCatching { CaptureSource.valueOf(cursor.getString(5)) }
                                .getOrDefault(CaptureSource.ACCESSIBILITY),
                        ),
                    )
                }
            }.asReversed()
        }
    }

    @Synchronized
    fun summaries(): List<SavedSummary> {
        return database.readableDatabase.query(
            "summaries",
            arrayOf("group_name", "summary_text", "generated_at", "imported_at"),
            null,
            null,
            null,
            null,
            "imported_at DESC, group_name COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SavedSummary(
                            groupName = cursor.getString(0),
                            text = cursor.getString(1),
                            generatedAt = cursor.getLong(2),
                            importedAt = cursor.getLong(3),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun planItems(includeCompleted: Boolean = true): List<PlanItem> {
        val selection = if (includeCompleted) null else "status != ?"
        val args = if (includeCompleted) null else arrayOf(PlanItemStatus.COMPLETED.name)
        return database.readableDatabase.query(
            "plan_items",
            arrayOf(
                "stable_key",
                "group_name",
                "title",
                "item_type",
                "assignee",
                "deadline_at",
                "status",
                "source",
                "confidence",
                "updated_at",
            ),
            selection,
            args,
            null,
            null,
            "CASE status WHEN 'ACTIVE' THEN 0 WHEN 'CANCELLED' THEN 1 ELSE 2 END, " +
                "CASE WHEN deadline_at IS NULL THEN 1 ELSE 0 END, deadline_at ASC, updated_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toPlanItem())
            }
        }
    }

    /**
     * Reconciles one user-shared ChatGPT result with the existing plan in a transaction.
     * Missing items are never deleted: cancellation/completion must be explicit in the import.
     */
    @Synchronized
    fun mergeChatGptResult(
        result: ImportedChatGptResult,
        importedAt: Long = System.currentTimeMillis(),
    ): MergeReport {
        val db = database.writableDatabase
        val selectedGroups = selectedGroupNames()
        val acceptedSummaries = result.summaries.filter { it.groupName in selectedGroups }
        val acceptedDrafts = result.planItems.filter { it.groupName in selectedGroups }
        val changes = mutableListOf<PlanChange>()
        var summaryCount = 0
        var itemCount = 0

        db.beginTransaction()
        try {
            acceptedSummaries.forEach { summary ->
                val text = summary.bullets
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n") { "• $it" }
                    .take(16_000)
                if (text.isBlank()) return@forEach
                val values = ContentValues().apply {
                    put("group_name", summary.groupName)
                    put("summary_text", text)
                    put("generated_at", result.generatedAt)
                    put("imported_at", importedAt)
                }
                if (db.insertWithOnConflict(
                        "summaries",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L
                ) {
                    summaryCount += 1
                }
            }

            acceptedDrafts.forEach { draft ->
                val existingByHint = draft.stableKey
                    ?.takeIf { SAFE_STABLE_KEY.matches(it) }
                    ?.let { findPlanItem(it, db) }
                    ?.takeIf {
                        it.groupName == draft.groupName &&
                            titlesReferToSameItem(it.title, draft.title)
                    }
                val stableKey = existingByHint?.stableKey ?: canonicalPlanKey(draft)
                val existing = existingByHint ?: findPlanItem(stableKey, db)
                val normalized = draft.copy(
                    stableKey = stableKey,
                    title = draft.title.trim().take(240),
                    assignee = draft.assignee?.trim()?.take(120)?.ifBlank { null },
                    source = draft.source.trim().take(600),
                )
                if (normalized.title.isBlank()) return@forEach

                changes += comparePlan(existing, normalized, stableKey, importedAt)
                val values = ContentValues().apply {
                    put("stable_key", stableKey)
                    put("group_name", normalized.groupName)
                    put("title", normalized.title)
                    put("item_type", normalized.type.name)
                    put("assignee", normalized.assignee)
                    if (normalized.deadlineAt == null) putNull("deadline_at") else put("deadline_at", normalized.deadlineAt)
                    put("status", normalized.status.name)
                    put("source", normalized.source)
                    put("confidence", normalized.confidence.name)
                    put("updated_at", importedAt)
                }
                if (db.insertWithOnConflict(
                        "plan_items",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L
                ) {
                    itemCount += 1
                }
            }

            changes.forEach { change ->
                val values = ContentValues().apply {
                    put("stable_key", change.stableKey)
                    put("group_name", change.groupName)
                    put("title", change.title)
                    put("change_kind", change.kind.name)
                    put("old_value", change.oldValue)
                    put("new_value", change.newValue)
                    put("detected_at", change.detectedAt)
                    put("notified", 0)
                }
                db.insert("plan_changes", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MergeReport(summaryCount, itemCount, changes)
    }

    @Synchronized
    fun pendingChanges(): List<PlanChange> {
        return database.readableDatabase.query(
            "plan_changes",
            arrayOf(
                "id",
                "stable_key",
                "group_name",
                "title",
                "change_kind",
                "old_value",
                "new_value",
                "detected_at",
                "notified",
            ),
            "notified = 0",
            null,
            null,
            null,
            "detected_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PlanChange(
                            id = cursor.getLong(0),
                            stableKey = cursor.getString(1),
                            groupName = cursor.getString(2),
                            title = cursor.getString(3),
                            kind = PlanChangeKind.valueOf(cursor.getString(4)),
                            oldValue = cursor.getString(5),
                            newValue = cursor.getString(6),
                            detectedAt = cursor.getLong(7),
                            notified = cursor.getInt(8) == 1,
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun markChangesNotified(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val values = ContentValues().apply { put("notified", 1) }
        val placeholders = ids.joinToString(",") { "?" }
        database.writableDatabase.update(
            "plan_changes",
            values,
            "id IN ($placeholders)",
            ids.map(Long::toString).toTypedArray(),
        )
    }

    @Synchronized
    fun snapshot(limit: Int = 200): StoreSnapshot = StoreSnapshot(groups(), messages(limit = limit))

    @Synchronized
    fun deleteMessagesOlderThan(cutoff: Long): Int = database.writableDatabase.delete(
        "messages",
        "event_at < ?",
        arrayOf(cutoff.toString()),
    )

    @Synchronized
    fun deleteAllCapturedData() {
        database.writableDatabase.beginTransaction()
        try {
            database.writableDatabase.delete("messages", null, null)
            database.writableDatabase.delete("groups", null, null)
            database.writableDatabase.delete("summaries", null, null)
            database.writableDatabase.delete("plan_items", null, null)
            database.writableDatabase.delete("plan_changes", null, null)
            database.writableDatabase.setTransactionSuccessful()
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    private fun findGroup(name: String, db: SQLiteDatabase): GroupConfig? {
        return db.query(
            "groups",
            arrayOf("name", "selected", "pinned", "discovered_at", "last_seen_at"),
            "name = ?",
            arrayOf(name),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else GroupConfig(
                name = cursor.getString(0),
                selected = cursor.getInt(1) == 1,
                pinned = cursor.getInt(2) == 1,
                discoveredAt = cursor.getLong(3),
                lastSeenAt = cursor.getLong(4),
            )
        }
    }

    private fun findPlanItem(stableKey: String, db: SQLiteDatabase): PlanItem? {
        return db.query(
            "plan_items",
            arrayOf(
                "stable_key",
                "group_name",
                "title",
                "item_type",
                "assignee",
                "deadline_at",
                "status",
                "source",
                "confidence",
                "updated_at",
            ),
            "stable_key = ?",
            arrayOf(stableKey),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toPlanItem() else null }
    }

    private fun comparePlan(
        old: PlanItem?,
        draft: PlanDraft,
        stableKey: String,
        now: Long,
    ): List<PlanChange> {
        fun change(kind: PlanChangeKind, oldValue: String?, newValue: String?) = PlanChange(
            stableKey = stableKey,
            groupName = draft.groupName,
            title = draft.title,
            kind = kind,
            oldValue = oldValue,
            newValue = newValue,
            detectedAt = now,
        )

        if (old == null) {
            val initialKind = when (draft.status) {
                PlanItemStatus.CANCELLED -> PlanChangeKind.CANCELLED
                PlanItemStatus.COMPLETED -> PlanChangeKind.COMPLETED
                PlanItemStatus.ACTIVE -> PlanChangeKind.CREATED
            }
            return listOf(change(initialKind, null, draft.status.name))
        }
        val result = mutableListOf<PlanChange>()
        if (old.status != draft.status) {
            val kind = when (draft.status) {
                PlanItemStatus.CANCELLED -> PlanChangeKind.CANCELLED
                PlanItemStatus.COMPLETED -> PlanChangeKind.COMPLETED
                PlanItemStatus.ACTIVE -> PlanChangeKind.REOPENED
            }
            result += change(kind, old.status.name, draft.status.name)
        }
        if (old.deadlineAt != draft.deadlineAt) {
            val kind = when {
                old.deadlineAt == null -> PlanChangeKind.DEADLINE_ADDED
                draft.deadlineAt == null -> PlanChangeKind.DEADLINE_REMOVED
                draft.deadlineAt < old.deadlineAt -> PlanChangeKind.DEADLINE_EARLIER
                else -> PlanChangeKind.DEADLINE_LATER
            }
            result += change(kind, old.deadlineAt?.toString(), draft.deadlineAt?.toString())
        }
        if (old.assignee != draft.assignee) {
            result += change(PlanChangeKind.ASSIGNEE_CHANGED, old.assignee, draft.assignee)
        }
        if (old.title != draft.title || old.type != draft.type || old.source != draft.source) {
            result += change(PlanChangeKind.DETAILS_UPDATED, old.title, draft.title)
        }
        return result
    }

    private fun canonicalPlanKey(draft: PlanDraft): String {
        val normalizedTitle = normalizePlanTitle(draft.title)
        return "plan_" + sha256("${draft.groupName}|${draft.type}|$normalizedTitle").take(24)
    }

    private fun titlesReferToSameItem(first: String, second: String): Boolean {
        val left = normalizePlanTitle(first)
        val right = normalizePlanTitle(second)
        if (left.isBlank() || right.isBlank()) return false
        return left == right ||
            (minOf(left.length, right.length) >= 4 && (left.contains(right) || right.contains(left)))
    }

    private fun normalizePlanTitle(value: String): String = value
        .lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
        .take(180)

    companion object {
        @Volatile
        private var instance: LocalStore? = null

        fun get(context: Context): LocalStore = instance ?: synchronized(this) {
            instance ?: LocalStore(context).also { instance = it }
        }

        private fun normalizeName(value: String): String? = value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
            .ifBlank { null }

        private fun normalizeBody(value: String): String? = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(8_000)
            .ifBlank { null }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private val SAFE_STABLE_KEY = Regex("[A-Za-z0-9._:-]{3,120}")
    }
}

private fun android.database.Cursor.toPlanItem(): PlanItem = PlanItem(
    stableKey = getString(0),
    groupName = getString(1),
    title = getString(2),
    type = runCatching { PlanItemType.valueOf(getString(3)) }.getOrDefault(PlanItemType.OTHER),
    assignee = getString(4),
    deadlineAt = if (isNull(5)) null else getLong(5),
    status = runCatching { PlanItemStatus.valueOf(getString(6)) }.getOrDefault(PlanItemStatus.ACTIVE),
    source = getString(7),
    confidence = runCatching { PlanConfidence.valueOf(getString(8)) }.getOrDefault(PlanConfidence.LOW),
    updatedAt = getLong(9),
)

private class MessageDatabase(context: Context) : SQLiteOpenHelper(context, "wx_assistant.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE groups (
                name TEXT PRIMARY KEY NOT NULL,
                selected INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 1,
                discovered_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                group_name TEXT NOT NULL,
                sender TEXT,
                body TEXT NOT NULL,
                event_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                fingerprint TEXT NOT NULL UNIQUE,
                captured_at INTEGER NOT NULL,
                FOREIGN KEY(group_name) REFERENCES groups(name) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_group_time ON messages(group_name, event_at DESC)")
        createPlanTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createPlanTables(db)
    }

    private fun createPlanTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS summaries (
                group_name TEXT PRIMARY KEY NOT NULL,
                summary_text TEXT NOT NULL,
                generated_at INTEGER NOT NULL,
                imported_at INTEGER NOT NULL,
                FOREIGN KEY(group_name) REFERENCES groups(name) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_items (
                stable_key TEXT PRIMARY KEY NOT NULL,
                group_name TEXT NOT NULL,
                title TEXT NOT NULL,
                item_type TEXT NOT NULL,
                assignee TEXT,
                deadline_at INTEGER,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                confidence TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(group_name) REFERENCES groups(name) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS plan_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                stable_key TEXT NOT NULL,
                group_name TEXT NOT NULL,
                title TEXT NOT NULL,
                change_kind TEXT NOT NULL,
                old_value TEXT,
                new_value TEXT,
                detected_at INTEGER NOT NULL,
                notified INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS plan_deadlines ON plan_items(status, deadline_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS changes_pending ON plan_changes(notified, detected_at)")
    }
}
