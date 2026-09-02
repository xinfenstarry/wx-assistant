package com.xinfen.wxassistant.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * Captures likely WeChat *group* notifications and emits an app-private event.
 *
 * WeChat changes its notification shape between versions and OEMs. The parser
 * therefore combines Android's group-conversation metadata, the user-approved
 * pinned-group allowlist, and conservative sender/body heuristics. A consumer
 * should still surface the detected group name to the user before using it for
 * task extraction.
 *
 * This service never posts notifications and has no accessibility/UI authority.
 */
class WeChatNotificationListenerService : NotificationListenerService() {
    private val recentContent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != WeChatCaptureContract.WECHAT_PACKAGE) return

        val parsed = parseNotification(sbn) ?: return
        if (!parsed.isLikelyGroup) return

        val now = System.currentTimeMillis()
        if (wasRecentlyEmitted(parsed.contentHash, now)) return

        val intent = WeChatCaptureContract.internalBroadcast(
            this,
            WeChatCaptureContract.ACTION_GROUP_NOTIFICATION_CAPTURED,
        )
            .putExtra(WeChatCaptureContract.EXTRA_GROUP_TITLE, parsed.groupTitle)
            .putExtra(WeChatCaptureContract.EXTRA_SENDER, parsed.sender)
            .putExtra(WeChatCaptureContract.EXTRA_MESSAGE, parsed.message)
            .putExtra(WeChatCaptureContract.EXTRA_MESSAGES, ArrayList(parsed.lines))
            .putExtra(WeChatCaptureContract.EXTRA_SOURCE_POSTED_AT_MS, sbn.postTime)
            .putExtra(WeChatCaptureContract.EXTRA_CAPTURED_AT_MS, now)
            .putExtra(WeChatCaptureContract.EXTRA_NOTIFICATION_KEY, sbn.key)
            .putExtra(WeChatCaptureContract.EXTRA_CONTENT_HASH, parsed.contentHash)
            .putExtra(WeChatCaptureContract.EXTRA_IS_LIKELY_GROUP, true)
        WeChatCaptureContract.sendInternalBroadcast(this, intent)
    }

    private fun parseNotification(sbn: StatusBarNotification): ParsedNotification? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: Bundle.EMPTY

        val title = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        )
        val lines = extractLines(extras)
        if (title == null && lines.isEmpty()) return null

        val allowlist = WeChatCaptureContract.pinnedGroupAllowlist(this)
        val allowlistedTitle = matchAllowlistedTitle(allowlist, title, lines)
        val groupMetadata = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val primaryLine = lines.lastOrNull()
        val senderAndBody = splitSenderAndBody(primaryLine)
        val conversationTitle = WeChatCaptureContract.boundedText(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            WeChatCaptureContract.MAX_GROUP_TITLE_CHARS,
        )

        // A sender prefix is a useful conservative signal: WeChat group previews
        // commonly use "member: message", while one-to-one previews usually do not.
        val hasSenderPrefix = senderAndBody?.first != null
        val aggregateWechatPreview = title.equals("微信", ignoreCase = true) &&
            (primaryLine?.count { it == ':' || it == '：' } ?: 0) >= 2
        val isLikelyGroup = allowlistedTitle != null ||
            groupMetadata ||
            !conversationTitle.isNullOrBlank() ||
            (hasSenderPrefix && !title.equals("微信", ignoreCase = true)) ||
            aggregateWechatPreview

        val groupTitle = allowlistedTitle
            ?: conversationTitle
            ?: title
            ?: return null
        val sender = senderAndBody?.first
        val message = senderAndBody?.second ?: primaryLine ?: return null
        val safeLines = boundLines(lines.ifEmpty { listOf(message) })
        val hash = sha256(
            listOf(groupTitle, sender.orEmpty(), safeLines.joinToString("\n"), sbn.postTime.toString())
                .joinToString("\u001f"),
        )
        return ParsedNotification(
            groupTitle = groupTitle,
            sender = sender,
            message = message,
            lines = safeLines,
            isLikelyGroup = isLikelyGroup,
            contentHash = hash,
        )
    }

    private fun extractLines(extras: Bundle): List<String> {
        val result = ArrayList<String>()

        @Suppress("DEPRECATION")
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let { bundles ->
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
                .forEach { message ->
                    val body = WeChatCaptureContract.boundedText(message.text) ?: return@forEach
                    val senderName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        message.senderPerson?.name
                    } else {
                        @Suppress("DEPRECATION")
                        message.sender
                    }
                    val sender = WeChatCaptureContract.boundedText(senderName, 80)
                    result += if (sender == null) body else "$sender：$body"
                }
        }

        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.forEach { WeChatCaptureContract.boundedText(it)?.let(result::add) }

        val bigText = WeChatCaptureContract.boundedText(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        )
        if (bigText != null) {
            bigText.lineSequence()
                .mapNotNull { WeChatCaptureContract.boundedText(it) }
                .forEach(result::add)
        }

        WeChatCaptureContract.boundedText(extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.let(result::add)

        return result.distinct()
    }

    private fun boundLines(lines: List<String>): List<String> {
        val result = ArrayList<String>()
        var totalChars = 0
        for (line in lines.takeLast(WeChatCaptureContract.MAX_MESSAGES_PER_CAPTURE)) {
            val bounded = WeChatCaptureContract.boundedText(line) ?: continue
            if (totalChars + bounded.length > WeChatCaptureContract.MAX_CAPTURE_CHARS) break
            result.add(bounded)
            totalChars += bounded.length
        }
        return result
    }

    private fun firstNonBlank(vararg values: CharSequence?): String? {
        for (value in values) {
            val normalized = WeChatCaptureContract.boundedText(
                value,
                WeChatCaptureContract.MAX_GROUP_TITLE_CHARS,
            )
            if (normalized != null) return normalized
        }
        return null
    }

    private fun matchAllowlistedTitle(
        allowlist: Set<String>,
        title: String?,
        lines: List<String>,
    ): String? {
        if (allowlist.isEmpty()) return null
        val candidates = buildList {
            title?.let(::add)
            addAll(lines)
        }
        return allowlist.firstOrNull { allowed ->
            candidates.any { candidate ->
                candidate.equals(allowed, ignoreCase = true) ||
                    candidate.startsWith("$allowed:") ||
                    candidate.startsWith("$allowed：") ||
                    candidate.startsWith("[$allowed]") ||
                    candidate.startsWith("【$allowed】")
            }
        }
    }

    private fun splitSenderAndBody(line: String?): Pair<String?, String>? {
        if (line.isNullOrBlank()) return null
        val western = line.indexOf(':')
        val chinese = line.indexOf('：')
        val separator = listOf(western, chinese).filter { it in 1..80 }.minOrNull() ?: return null
        val sender = WeChatCaptureContract.boundedText(line.substring(0, separator), 80)
        val body = WeChatCaptureContract.boundedText(line.substring(separator + 1)) ?: return null
        return sender to body
    }

    @Synchronized
    private fun wasRecentlyEmitted(hash: String, now: Long): Boolean {
        val cutoff = now - DEDUP_TTL_MS
        val iterator = recentContent.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
        if (recentContent.containsKey(hash)) return true
        recentContent[hash] = now
        while (recentContent.size > MAX_RECENT_HASHES) {
            val first = recentContent.entries.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            }
        }
        return false
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private data class ParsedNotification(
        val groupTitle: String,
        val sender: String?,
        val message: String,
        val lines: List<String>,
        val isLikelyGroup: Boolean,
        val contentHash: String,
    )

    private companion object {
        const val DEDUP_TTL_MS = 5 * 60_000L
        const val MAX_RECENT_HASHES = 512
    }
}
