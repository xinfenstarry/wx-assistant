package com.xinfen.wxassistant.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Read-only WeChat accessibility collector.
 *
 * Security boundary:
 * - There is no API or command for composing or delivering messages.
 * - The only node actions admitted by [performReadOnlyNodeNavigation] are opening
 *   a conversation row and scrolling a conversation list.
 * - The only global action is returning to the previous screen after a bounded
 *   read pass.
 * - Navigation requires a fresh, one-time, app-private request and is disabled by
 *   default. Passive capture only observes an already-open approved group.
 *
 * WeChat's accessibility hierarchy and obfuscated view IDs change frequently.
 * For predictable results the host UI should call
 * [WeChatCaptureContract.replacePinnedGroupAllowlist] with user-approved pinned
 * group names. Without that list, discovery requires explicit accessible
 * "pinned" and "group chat" labels and intentionally errs on the side of not
 * opening a row.
 */
class WeChatReadOnlyAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seenRequestIds = LinkedHashMap<String, Long>()
    private val recentSnapshots = LinkedHashMap<String, Long>()
    private var activeSession: ReadSession? = null
    private var receiverRegistered = false
    private var lastWindowClassName: String = ""

    private val readRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WeChatCaptureContract.ACTION_REQUEST_PINNED_GROUP_READ) return
            acceptReadRequest(intent)
        }
    }

    private val scanRunnable = Runnable { inspectVisibleWeChatWindow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReadRequestReceiver()
        emitStatus(
            status = WeChatCaptureContract.STATUS_SERVICE_CONNECTED,
            detail = "Read-only WeChat accessibility capture is connected",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != WeChatCaptureContract.WECHAT_PACKAGE) return
        lastWindowClassName = event.className?.toString().orEmpty()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> scheduleInspection(EVENT_DEBOUNCE_MS)
        }
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacks(scanRunnable)
        activeSession = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(readRequestReceiver) }
            receiverRegistered = false
        }
        activeSession = null
        super.onDestroy()
    }

    private fun registerReadRequestReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(WeChatCaptureContract.ACTION_REQUEST_PINNED_GROUP_READ)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                readRequestReceiver,
                filter,
                WeChatCaptureContract.INTERNAL_PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                readRequestReceiver,
                filter,
                WeChatCaptureContract.INTERNAL_PERMISSION,
                null,
            )
        }
        receiverRegistered = true
    }

    private fun acceptReadRequest(intent: Intent) {
        val now = System.currentTimeMillis()
        pruneSeenRequests(now)

        val requestId = intent.getStringExtra(WeChatCaptureContract.EXTRA_REQUEST_ID)
            ?.trim()
            ?.takeIf { it.length in 16..128 }
        val requestedAt = intent.getLongExtra(WeChatCaptureContract.EXTRA_REQUESTED_AT_MS, 0L)
        val ttlMs = intent.getLongExtra(
            WeChatCaptureContract.EXTRA_TTL_MS,
            WeChatCaptureContract.DEFAULT_REQUEST_TTL_MS,
        ).coerceIn(
            WeChatCaptureContract.MIN_REQUEST_TTL_MS,
            WeChatCaptureContract.MAX_REQUEST_TTL_MS,
        )

        val rejection = when {
            requestId == null -> "missing or malformed request ID"
            requestedAt <= 0L -> "missing request timestamp"
            requestedAt > now + MAX_CLOCK_SKEW_MS -> "request timestamp is in the future"
            now - requestedAt > ttlMs -> "request expired"
            seenRequestIds.containsKey(requestId) -> "request ID was already used"
            activeSession?.isAlive(now) == true -> "another read pass is active"
            else -> null
        }
        if (rejection != null) {
            emitStatus(
                status = WeChatCaptureContract.STATUS_REQUEST_REJECTED,
                detail = rejection,
                requestId = requestId,
            )
            return
        }

        val requestedTitles = intent
            .getStringArrayListExtra(WeChatCaptureContract.EXTRA_GROUP_TITLES)
            .orEmpty()
            .mapNotNull(WeChatCaptureContract::normalizeGroupTitle)
            .toCollection(LinkedHashSet())
        val targets = if (requestedTitles.isNotEmpty()) {
            requestedTitles
        } else {
            WeChatCaptureContract.pinnedGroupAllowlist(this).toCollection(LinkedHashSet())
        }
        val allowNavigation = intent.getBooleanExtra(
            WeChatCaptureContract.EXTRA_ALLOW_NAVIGATION,
            false,
        )

        val acceptedId = requireNotNull(requestId)
        seenRequestIds[acceptedId] = now
        activeSession = ReadSession(
            requestId = acceptedId,
            deadlineMs = requestedAt + ttlMs,
            allowNavigation = allowNavigation,
            targetTitles = targets,
        )
        emitStatus(
            status = WeChatCaptureContract.STATUS_REQUEST_ACCEPTED,
            detail = if (allowNavigation) {
                "Read pass accepted with bounded conversation navigation"
            } else {
                "Read pass accepted for the currently visible screen only"
            },
            requestId = acceptedId,
        )
        scheduleInspection(0L)
    }

    private fun scheduleInspection(delayMs: Long) {
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, delayMs)
    }

    private fun inspectVisibleWeChatWindow() {
        val now = System.currentTimeMillis()
        val session = activeSession
        if (session != null && !session.isAlive(now)) {
            completeSession(session, "Read request expired")
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != WeChatCaptureContract.WECHAT_PACKAGE) {
            activeSession?.let {
                emitStatus(
                    status = WeChatCaptureContract.STATUS_WAITING_FOR_WECHAT,
                    detail = "Open WeChat to continue this read pass",
                    requestId = it.requestId,
                )
            }
            return
        }

        val nodes = flattenVisibleNodes(root)
        if (nodes.isEmpty()) return

        val currentSession = activeSession
        val approvedTitles = approvedTitles(currentSession)
        val expectedTitle = currentSession?.activeGroupTitle
        val visibleGroupTitle = findVisibleApprovedGroupTitle(nodes, expectedTitle, approvedTitles)

        if (visibleGroupTitle != null && looksLikeChatWindow(nodes, visibleGroupTitle)) {
            val messages = extractVisibleMessageText(nodes, visibleGroupTitle, root)
            emitGroupMessages(visibleGroupTitle, messages, currentSession?.requestId)

            if (currentSession?.activeGroupTitle != null) {
                currentSession.capturedTitles.add(visibleGroupTitle)
                currentSession.activeGroupTitle = null
                if (currentSession.allowNavigation && currentSession.isAlive(now)) {
                    // Returning is the only global UI operation, and only happens
                    // after this service itself opened a requested conversation.
                    mainHandler.postDelayed({
                        performReadOnlyGlobalReturn()
                        scheduleInspection(NAVIGATION_SETTLE_MS)
                    }, RETURN_DELAY_MS)
                } else {
                    completeSession(currentSession, "Visible group captured")
                }
            } else if (currentSession != null && !currentSession.allowNavigation) {
                currentSession.capturedTitles.add(visibleGroupTitle)
                completeSession(currentSession, "Visible group captured without navigation")
            }
            return
        }

        val rows = findPinnedGroupRows(nodes, approvedTitles, root)
        emitDiscoveredGroups(rows.map { it.groupTitle }, currentSession?.requestId)

        if (currentSession == null) return
        if (!currentSession.allowNavigation) {
            completeSession(currentSession, "Visible conversation list inspected; navigation was disabled")
            return
        }

        val next = rows.firstOrNull {
            it.groupTitle !in currentSession.capturedTitles &&
                it.groupTitle !in currentSession.failedTitles
        }
        if (next != null) {
            currentSession.activeGroupTitle = next.groupTitle
            val opened = performReadOnlyNodeNavigation(
                node = next.rowNode,
                action = AccessibilityNodeInfo.ACTION_CLICK,
            )
            if (!opened) {
                currentSession.activeGroupTitle = null
                currentSession.failedTitles.add(next.groupTitle)
                scheduleInspection(SHORT_RETRY_MS)
            } else {
                scheduleInspection(NAVIGATION_SETTLE_MS)
            }
            return
        }

        val allRequestedCaptured = currentSession.targetTitles.isNotEmpty() &&
            currentSession.targetTitles.all {
                it in currentSession.capturedTitles || it in currentSession.failedTitles
            }
        if (allRequestedCaptured || currentSession.listScrollAttempts >= MAX_LIST_SCROLL_ATTEMPTS) {
            completeSession(currentSession, "Pinned-group read pass finished")
            return
        }

        val scrollable = nodes
            .asSequence()
            .filter { it.isScrollable && !it.isEditable }
            .maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (scrollable != null) {
            currentSession.listScrollAttempts += 1
            val scrolled = performReadOnlyNodeNavigation(
                node = scrollable.node,
                action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            )
            if (scrolled) {
                scheduleInspection(NAVIGATION_SETTLE_MS)
                return
            }
        }
        completeSession(currentSession, "No additional approved pinned-group rows are visible")
    }

    private fun approvedTitles(session: ReadSession?): Set<String> {
        if (session != null && session.targetTitles.isNotEmpty()) return session.targetTitles
        return WeChatCaptureContract.pinnedGroupAllowlist(this)
    }

    private fun flattenVisibleNodes(root: AccessibilityNodeInfo): List<NodeRecord> {
        val result = ArrayList<NodeRecord>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && result.size < MAX_TREE_NODES) {
            val (node, depth) = queue.removeFirst()
            val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
            if (!visible && depth > 0) continue

            val bounds = Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            val record = NodeRecord(
                node = node,
                depth = depth,
                text = WeChatCaptureContract.boundedText(node.text),
                description = WeChatCaptureContract.boundedText(node.contentDescription),
                viewId = node.viewIdResourceName.orEmpty(),
                className = node.className?.toString().orEmpty(),
                bounds = bounds,
                isClickable = runCatching { node.isClickable }.getOrDefault(false),
                isScrollable = runCatching { node.isScrollable }.getOrDefault(false),
                isEditable = runCatching { node.isEditable }.getOrDefault(false),
                isPassword = runCatching { node.isPassword }.getOrDefault(false),
                childCount = runCatching { node.childCount }.getOrDefault(0),
            )
            result.add(record)
            for (index in 0 until record.childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let { queue.add(it to depth + 1) }
            }
        }
        return result
    }

    private fun findVisibleApprovedGroupTitle(
        nodes: List<NodeRecord>,
        expectedTitle: String?,
        approvedTitles: Set<String>,
    ): String? {
        if (expectedTitle != null) {
            if (nodes.any { it.matchesTitle(expectedTitle) } || looksLikeChatWindow(nodes, expectedTitle)) {
                return expectedTitle
            }
        }
        return approvedTitles.firstOrNull { title -> nodes.any { it.matchesTitle(title) } }
    }

    private fun looksLikeChatWindow(nodes: List<NodeRecord>, groupTitle: String): Boolean {
        if (lastWindowClassName.contains("chatting", ignoreCase = true)) return true
        val chattingIds = nodes.count { it.viewId.contains("chatting", ignoreCase = true) }
        if (chattingIds >= 2) return true

        val hasTitle = nodes.any { it.matchesTitle(groupTitle) }
        val hasChatInfo = nodes.any {
            it.labels().any { label ->
                label.equals("聊天信息", ignoreCase = true) ||
                    label.equals("Chat Info", ignoreCase = true)
            }
        }
        return hasTitle && hasChatInfo
    }

    private fun findPinnedGroupRows(
        nodes: List<NodeRecord>,
        approvedTitles: Set<String>,
        root: AccessibilityNodeInfo,
    ): List<GroupRow> {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val byBounds = LinkedHashMap<String, GroupRow>()

        if (approvedTitles.isNotEmpty()) {
            for (title in approvedTitles) {
                val titleNode = nodes.firstOrNull { it.matchesTitle(title) } ?: continue
                val rowNode = findSafeClickableAncestor(titleNode.node, rootBounds) ?: continue
                val rowBounds = Rect().also { rowNode.getBoundsInScreen(it) }
                byBounds[boundsKey(rowBounds)] = GroupRow(title, rowNode, rowBounds)
            }
            return byBounds.values.sortedBy { it.bounds.top }
        }

        // Heuristic fallback is deliberately strict: both signals must be exposed
        // by TalkBack/accessibility metadata before this service treats a row as
        // an unconfigured pinned group.
        for (record in nodes.filter { it.isClickable }) {
            if (!isPlausibleConversationRow(record.bounds, rootBounds)) continue
            val labels = collectSubtreeLabels(record.node, MAX_ROW_NODES)
            val combined = labels.joinToString(" ").lowercase()
            val hasPinnedSignal = combined.contains("置顶") || combined.contains("pinned")
            val hasGroupSignal = combined.contains("群聊") ||
                combined.contains("群组") ||
                combined.contains("group chat")
            if (!hasPinnedSignal || !hasGroupSignal) continue
            val title = labels.firstOrNull(::isPlausibleGroupTitle) ?: continue
            byBounds[boundsKey(record.bounds)] = GroupRow(title, record.node, record.bounds)
        }
        return byBounds.values.sortedBy { it.bounds.top }
    }

    private fun findSafeClickableAncestor(
        start: AccessibilityNodeInfo,
        rootBounds: Rect,
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        var hops = 0
        while (current != null && hops < MAX_PARENT_HOPS) {
            val bounds = Rect().also { current.getBoundsInScreen(it) }
            if (current.isClickable &&
                !current.isEditable &&
                isPlausibleConversationRow(bounds, rootBounds)
            ) {
                return current
            }
            current = current.parent
            hops += 1
        }
        return null
    }

    private fun isPlausibleConversationRow(bounds: Rect, rootBounds: Rect): Boolean {
        if (bounds.isEmpty || rootBounds.isEmpty) return false
        val rootHeight = rootBounds.height().coerceAtLeast(1)
        val rootWidth = rootBounds.width().coerceAtLeast(1)
        return bounds.height() in (rootHeight / 25)..(rootHeight / 3) &&
            bounds.width() >= rootWidth / 2 &&
            bounds.top >= rootBounds.top + rootHeight / 20 &&
            bounds.bottom <= rootBounds.bottom - rootHeight / 12
    }

    private fun collectSubtreeLabels(
        root: AccessibilityNodeInfo,
        limit: Int,
    ): List<String> {
        val result = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val node = queue.removeFirst()
            visited += 1
            WeChatCaptureContract.boundedText(node.text)?.let(result::add)
            WeChatCaptureContract.boundedText(node.contentDescription)?.let(result::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return result.distinct()
    }

    private fun isPlausibleGroupTitle(label: String): Boolean {
        val normalized = label.trim()
        if (normalized.length !in 1..WeChatCaptureContract.MAX_GROUP_TITLE_CHARS) return false
        return normalized.lowercase() !in UI_LABELS &&
            !normalized.contains("置顶") &&
            !normalized.contains("群聊") &&
            !normalized.equals("pinned", ignoreCase = true) &&
            !normalized.equals("group chat", ignoreCase = true)
    }

    private fun extractVisibleMessageText(
        nodes: List<NodeRecord>,
        groupTitle: String,
        root: AccessibilityNodeInfo,
    ): List<String> {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val topCutoff = rootBounds.top + rootBounds.height() / 10
        val bottomCutoff = rootBounds.bottom - rootBounds.height() / 7
        val ordered = nodes
            .asSequence()
            .filter { it.childCount == 0 }
            .filter { !it.isEditable && !it.isPassword }
            .filter { it.bounds.top >= topCutoff && it.bounds.bottom <= bottomCutoff }
            .sortedWith(compareBy<NodeRecord> { it.bounds.top }.thenBy { it.bounds.left })

        val messages = ArrayList<String>()
        var totalChars = 0
        for (record in ordered) {
            val label = record.text ?: record.description ?: continue
            if (record.matchesTitle(groupTitle)) continue
            if (shouldIgnoreMessageLabel(label)) continue
            if (messages.lastOrNull() == label) continue
            if (totalChars + label.length > WeChatCaptureContract.MAX_CAPTURE_CHARS) break
            messages.add(label)
            totalChars += label.length
            if (messages.size >= WeChatCaptureContract.MAX_MESSAGES_PER_CAPTURE) break
        }
        return messages
    }

    private fun shouldIgnoreMessageLabel(label: String): Boolean {
        val normalized = label.trim().lowercase()
        if (normalized in UI_LABELS) return true
        if (normalized.endsWith("头像") || normalized.endsWith("avatar")) return true
        return normalized.startsWith("返回") || normalized.startsWith("back,")
    }

    private fun emitDiscoveredGroups(groupTitles: List<String>, requestId: String?) {
        val normalized = groupTitles
            .mapNotNull(WeChatCaptureContract::normalizeGroupTitle)
            .distinct()
        if (normalized.isEmpty()) return
        val hash = sha256(normalized.joinToString("\u001f"))
        if (wasRecentlyEmitted("groups:$hash", DISCOVERY_DEDUP_TTL_MS)) return

        val intent = WeChatCaptureContract.internalBroadcast(
            this,
            WeChatCaptureContract.ACTION_PINNED_GROUPS_DISCOVERED,
        )
            .putStringArrayListExtra(
                WeChatCaptureContract.EXTRA_GROUP_TITLES,
                ArrayList(normalized),
            )
            .putExtra(WeChatCaptureContract.EXTRA_CAPTURED_AT_MS, System.currentTimeMillis())
            .putExtra(WeChatCaptureContract.EXTRA_CONTENT_HASH, hash)
        requestId?.let { intent.putExtra(WeChatCaptureContract.EXTRA_REQUEST_ID, it) }
        WeChatCaptureContract.sendInternalBroadcast(this, intent)
    }

    private fun emitGroupMessages(
        groupTitle: String,
        messages: List<String>,
        requestId: String?,
    ) {
        if (messages.isEmpty()) return
        val hash = sha256(listOf(groupTitle, messages.joinToString("\n")).joinToString("\u001f"))
        if (wasRecentlyEmitted("messages:$hash", MESSAGE_DEDUP_TTL_MS)) return

        val intent = WeChatCaptureContract.internalBroadcast(
            this,
            WeChatCaptureContract.ACTION_GROUP_MESSAGES_CAPTURED,
        )
            .putExtra(WeChatCaptureContract.EXTRA_GROUP_TITLE, groupTitle)
            .putStringArrayListExtra(
                WeChatCaptureContract.EXTRA_MESSAGES,
                ArrayList(messages),
            )
            .putExtra(WeChatCaptureContract.EXTRA_CAPTURED_AT_MS, System.currentTimeMillis())
            .putExtra(WeChatCaptureContract.EXTRA_CONTENT_HASH, hash)
        requestId?.let { intent.putExtra(WeChatCaptureContract.EXTRA_REQUEST_ID, it) }
        WeChatCaptureContract.sendInternalBroadcast(this, intent)
    }

    private fun emitStatus(status: String, detail: String, requestId: String? = null) {
        val intent = WeChatCaptureContract.internalBroadcast(
            this,
            WeChatCaptureContract.ACTION_CAPTURE_STATUS,
        )
            .putExtra(WeChatCaptureContract.EXTRA_STATUS, status)
            .putExtra(WeChatCaptureContract.EXTRA_DETAIL, detail)
            .putExtra(WeChatCaptureContract.EXTRA_CAPTURED_AT_MS, System.currentTimeMillis())
        requestId?.let { intent.putExtra(WeChatCaptureContract.EXTRA_REQUEST_ID, it) }
        WeChatCaptureContract.sendInternalBroadcast(this, intent)
    }

    private fun completeSession(session: ReadSession, detail: String) {
        if (activeSession !== session) return
        activeSession = null
        emitStatus(
            status = WeChatCaptureContract.STATUS_SCAN_COMPLETE,
            detail = detail,
            requestId = session.requestId,
        )
    }

    /**
     * The sole gateway for node actions. Its allowlist makes the read-only
     * boundary auditable: only conversation opening and list scrolling exist.
     */
    private fun performReadOnlyNodeNavigation(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isEditable || node.isPassword) return false
        if (action != AccessibilityNodeInfo.ACTION_CLICK &&
            action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD &&
            action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        ) {
            return false
        }
        return runCatching { node.performAction(action) }.getOrDefault(false)
    }

    /** Returns only from a conversation that this service opened itself. */
    private fun performReadOnlyGlobalReturn(): Boolean {
        return runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
    }

    @Synchronized
    private fun wasRecentlyEmitted(key: String, ttlMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - maxOf(ttlMs, MESSAGE_DEDUP_TTL_MS)
        val iterator = recentSnapshots.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        val previous = recentSnapshots[key]
        if (previous != null && now - previous <= ttlMs) return true
        recentSnapshots[key] = now
        while (recentSnapshots.size > MAX_RECENT_SNAPSHOTS) {
            val first = recentSnapshots.entries.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            }
        }
        return false
    }

    private fun pruneSeenRequests(now: Long) {
        val iterator = seenRequestIds.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > REQUEST_REPLAY_CACHE_MS) iterator.remove()
        }
        while (seenRequestIds.size > MAX_SEEN_REQUEST_IDS) {
            val first = seenRequestIds.entries.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            }
        }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun boundsKey(bounds: Rect): String {
        return "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    private data class ReadSession(
        val requestId: String,
        val deadlineMs: Long,
        val allowNavigation: Boolean,
        val targetTitles: LinkedHashSet<String>,
        val capturedTitles: LinkedHashSet<String> = LinkedHashSet(),
        val failedTitles: LinkedHashSet<String> = LinkedHashSet(),
        var activeGroupTitle: String? = null,
        var listScrollAttempts: Int = 0,
    ) {
        fun isAlive(now: Long): Boolean = now <= deadlineMs
    }

    private data class NodeRecord(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val text: String?,
        val description: String?,
        val viewId: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val isEditable: Boolean,
        val isPassword: Boolean,
        val childCount: Int,
    ) {
        fun labels(): Sequence<String> = sequenceOf(text, description).filterNotNull()

        fun matchesTitle(title: String): Boolean {
            return labels().any { label ->
                label.equals(title, ignoreCase = true) ||
                    label.startsWith("$title，") ||
                    label.startsWith("$title,")
            }
        }
    }

    private data class GroupRow(
        val groupTitle: String,
        val rowNode: AccessibilityNodeInfo,
        val bounds: Rect,
    )

    private companion object {
        const val EVENT_DEBOUNCE_MS = 350L
        const val NAVIGATION_SETTLE_MS = 800L
        const val RETURN_DELAY_MS = 250L
        const val SHORT_RETRY_MS = 250L
        const val MAX_CLOCK_SKEW_MS = 5_000L
        const val REQUEST_REPLAY_CACHE_MS = 10 * 60_000L
        const val MESSAGE_DEDUP_TTL_MS = 60_000L
        const val DISCOVERY_DEDUP_TTL_MS = 15_000L
        const val MAX_TREE_NODES = 1_500
        const val MAX_ROW_NODES = 80
        const val MAX_PARENT_HOPS = 8
        const val MAX_LIST_SCROLL_ATTEMPTS = 3
        const val MAX_RECENT_SNAPSHOTS = 256
        const val MAX_SEEN_REQUEST_IDS = 128

        val UI_LABELS = setOf(
            "微信",
            "通讯录",
            "发现",
            "我",
            "搜索",
            "更多",
            "返回",
            "聊天信息",
            "按住说话",
            "切换到按住说话",
            "切换到键盘",
            "表情",
            "more",
            "back",
            "search",
            "chat info",
            "contacts",
            "discover",
            "me",
        )
    }
}
