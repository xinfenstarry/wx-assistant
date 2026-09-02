package com.xinfen.wxassistant.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xinfen.wxassistant.service.WeChatCaptureContract

/** Persists app-private capture events while keeping the selected-group boundary in one place. */
class CaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = LocalStore.get(context)
        when (intent.action) {
            WeChatCaptureContract.ACTION_PINNED_GROUPS_DISCOVERED -> {
                stringList(intent, WeChatCaptureContract.EXTRA_GROUP_TITLES)
                    .forEach { store.rememberCandidate(it, pinned = true) }
            }

            WeChatCaptureContract.ACTION_GROUP_NOTIFICATION_CAPTURED -> {
                val group = intent.getStringExtra(WeChatCaptureContract.EXTRA_GROUP_TITLE) ?: return
                if (!store.isGroupSelected(group)) return
                val sender = intent.getStringExtra(WeChatCaptureContract.EXTRA_SENDER)
                val message = intent.getStringExtra(WeChatCaptureContract.EXTRA_MESSAGE) ?: return
                val postedAt = intent.getLongExtra(
                    WeChatCaptureContract.EXTRA_SOURCE_POSTED_AT_MS,
                    System.currentTimeMillis(),
                )
                store.insertMessage(group, sender, message, postedAt, CaptureSource.NOTIFICATION)
            }

            WeChatCaptureContract.ACTION_GROUP_MESSAGES_CAPTURED -> {
                val group = intent.getStringExtra(WeChatCaptureContract.EXTRA_GROUP_TITLE) ?: return
                if (!store.isGroupSelected(group)) return
                val capturedAt = intent.getLongExtra(
                    WeChatCaptureContract.EXTRA_CAPTURED_AT_MS,
                    System.currentTimeMillis(),
                )
                stringList(intent, WeChatCaptureContract.EXTRA_MESSAGES).forEachIndexed { index, line ->
                    val (sender, body) = splitSender(line)
                    // Preserve ordering without pretending that the UI exposed exact timestamps.
                    store.insertMessage(
                        groupName = group,
                        sender = sender,
                        body = body,
                        eventAt = capturedAt - ((lineCount(intent) - index).coerceAtLeast(0) * 1_000L),
                        source = CaptureSource.ACCESSIBILITY,
                    )
                }
            }
        }
    }

    private fun lineCount(intent: Intent): Int =
        intent.getStringArrayListExtra(WeChatCaptureContract.EXTRA_MESSAGES)?.size ?: 0

    private fun stringList(intent: Intent, key: String): List<String> =
        intent.getStringArrayListExtra(key)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun splitSender(line: String): Pair<String?, String> {
        val western = line.indexOf(':')
        val chinese = line.indexOf('：')
        val separator = listOf(western, chinese).filter { it in 1..80 }.minOrNull()
            ?: return null to line
        val sender = line.substring(0, separator).trim().takeIf { it.isNotBlank() }
        val body = line.substring(separator + 1).trim().ifBlank { line }
        return sender to body
    }
}
