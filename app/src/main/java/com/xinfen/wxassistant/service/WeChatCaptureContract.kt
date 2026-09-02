package com.xinfen.wxassistant.service

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * The small, dependency-free contract between the Android UI/data layer and the
 * two WeChat capture services.
 *
 * All broadcasts are explicitly scoped to this application package and protected
 * by [INTERNAL_PERMISSION]. The permission must be declared as `signature` in the
 * manifest (see the service README/report). Captured content must stay on-device
 * unless the user explicitly enables a separate, disclosed sync feature.
 *
 * This contract deliberately contains no command that can compose or deliver a
 * WeChat message. The accessibility service is read-only apart from bounded UI
 * navigation needed to open a requested conversation, scroll its history, and
 * return to the conversation list.
 */
object WeChatCaptureContract {
    const val WECHAT_PACKAGE = "com.tencent.mm"

    const val INTERNAL_PERMISSION =
        "com.xinfen.wxassistant.permission.INTERNAL_WECHAT_CAPTURE"

    const val ACTION_GROUP_NOTIFICATION_CAPTURED =
        "com.xinfen.wxassistant.action.GROUP_NOTIFICATION_CAPTURED"
    const val ACTION_PINNED_GROUPS_DISCOVERED =
        "com.xinfen.wxassistant.action.PINNED_GROUPS_DISCOVERED"
    const val ACTION_GROUP_MESSAGES_CAPTURED =
        "com.xinfen.wxassistant.action.GROUP_MESSAGES_CAPTURED"
    const val ACTION_CAPTURE_STATUS =
        "com.xinfen.wxassistant.action.WECHAT_CAPTURE_STATUS"
    const val ACTION_REQUEST_PINNED_GROUP_READ =
        "com.xinfen.wxassistant.action.REQUEST_PINNED_GROUP_READ"

    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_REQUESTED_AT_MS = "requested_at_ms"
    const val EXTRA_TTL_MS = "ttl_ms"
    const val EXTRA_ALLOW_NAVIGATION = "allow_navigation"
    const val EXTRA_GROUP_TITLES = "group_titles"
    const val EXTRA_GROUP_TITLE = "group_title"
    const val EXTRA_SENDER = "sender"
    const val EXTRA_MESSAGES = "messages"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_CAPTURED_AT_MS = "captured_at_ms"
    const val EXTRA_SOURCE_POSTED_AT_MS = "source_posted_at_ms"
    const val EXTRA_NOTIFICATION_KEY = "notification_key"
    const val EXTRA_CONTENT_HASH = "content_hash"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_IS_LIKELY_GROUP = "is_likely_group"

    const val STATUS_SERVICE_CONNECTED = "service_connected"
    const val STATUS_REQUEST_ACCEPTED = "request_accepted"
    const val STATUS_REQUEST_REJECTED = "request_rejected"
    const val STATUS_WAITING_FOR_WECHAT = "waiting_for_wechat"
    const val STATUS_SCAN_COMPLETE = "scan_complete"

    const val DEFAULT_REQUEST_TTL_MS = 30_000L
    const val MIN_REQUEST_TTL_MS = 2_000L
    const val MAX_REQUEST_TTL_MS = 60_000L

    private const val PREFS_NAME = "wechat_read_only_capture"
    private const val PREF_PINNED_GROUP_ALLOWLIST = "pinned_group_allowlist"

    /**
     * Replaces the user-approved pinned-group names used by both services.
     *
     * An explicit list is substantially more reliable than trying to infer a
     * pinned/group row from WeChat's frequently changing, obfuscated view IDs.
     */
    @JvmStatic
    fun replacePinnedGroupAllowlist(context: Context, titles: Collection<String>) {
        val normalized = titles
            .mapNotNull(::normalizeGroupTitle)
            .toSet()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_PINNED_GROUP_ALLOWLIST, normalized)
            .apply()
    }

    @JvmStatic
    fun pinnedGroupAllowlist(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(PREF_PINNED_GROUP_ALLOWLIST, emptySet())
            .orEmpty()
            .mapNotNull(::normalizeGroupTitle)
            .toSet()
    }

    /**
     * Requests one bounded read pass. Navigation is opt-in for every request;
     * when false, the service only inspects the currently visible WeChat screen.
     *
     * The return value is the one-time request ID echoed by result broadcasts.
     */
    @JvmStatic
    @JvmOverloads
    fun requestPinnedGroupRead(
        context: Context,
        groupTitles: Collection<String> = emptyList(),
        allowNavigation: Boolean = false,
        ttlMs: Long = DEFAULT_REQUEST_TTL_MS,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val normalizedTitles = ArrayList(groupTitles.mapNotNull(::normalizeGroupTitle))
        val intent = Intent(ACTION_REQUEST_PINNED_GROUP_READ)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_REQUESTED_AT_MS, System.currentTimeMillis())
            .putExtra(EXTRA_TTL_MS, ttlMs.coerceIn(MIN_REQUEST_TTL_MS, MAX_REQUEST_TTL_MS))
            .putExtra(EXTRA_ALLOW_NAVIGATION, allowNavigation)
            .putStringArrayListExtra(EXTRA_GROUP_TITLES, normalizedTitles)
        context.sendBroadcast(intent, INTERNAL_PERMISSION)
        return requestId
    }

    internal fun internalBroadcast(context: Context, action: String): Intent {
        return Intent(action).setPackage(context.packageName)
    }

    internal fun sendInternalBroadcast(context: Context, intent: Intent) {
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent, INTERNAL_PERMISSION)
    }

    internal fun normalizeGroupTitle(value: String?): String? {
        val normalized = value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return normalized.take(MAX_GROUP_TITLE_CHARS)
    }

    internal fun boundedText(value: CharSequence?, maxChars: Int = MAX_MESSAGE_CHARS): String? {
        val normalized = value
            ?.toString()
            ?.replace('\u0000', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return normalized.take(maxChars)
    }

    internal const val MAX_GROUP_TITLE_CHARS = 160
    internal const val MAX_MESSAGE_CHARS = 2_000
    internal const val MAX_MESSAGES_PER_CAPTURE = 160
    internal const val MAX_CAPTURE_CHARS = 120_000
}
