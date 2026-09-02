package com.xinfen.wxassistant.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.xinfen.wxassistant.MainActivity
import com.xinfen.wxassistant.R
import com.xinfen.wxassistant.data.LocalStore
import com.xinfen.wxassistant.data.PlanChange
import com.xinfen.wxassistant.data.PlanChangeKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object PlanChangeNotifier {
    private const val CHANNEL_ID = "plan_changes"
    private const val NOTIFICATION_ID = 4107

    private val materialKinds = setOf(
        PlanChangeKind.CANCELLED,
        PlanChangeKind.REOPENED,
        PlanChangeKind.COMPLETED,
        PlanChangeKind.DEADLINE_EARLIER,
        PlanChangeKind.DEADLINE_LATER,
        PlanChangeKind.DEADLINE_ADDED,
        PlanChangeKind.DEADLINE_REMOVED,
        PlanChangeKind.ASSIGNEE_CHANGED,
    )

    fun notifyPending(context: Context): Boolean {
        val store = LocalStore.get(context)
        val pending = store.pendingChanges()
        val material = pending.filter { it.kind in materialKinds }
        if (material.isEmpty()) {
            // Non-material bookkeeping changes should not keep retrying forever.
            store.markChangesNotified(pending.map { it.id })
            return true
        }
        if (!canPostNotifications(context)) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "计划变更",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "课程取消、截止时间和负责人等计划变化"
            },
        )
        val lines = material.takeLast(8).map(::describe)
        val title = if (material.size == 1) "计划有 1 项变更" else "计划有 ${material.size} 项变更"
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PLAN
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "打开群聊助手查看")
            .setStyle(
                android.app.Notification.InboxStyle().also { style ->
                    lines.forEach { style.addLine(it) }
                    if (material.size > lines.size) style.setSummaryText("另有 ${material.size - lines.size} 项")
                },
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        store.markChangesNotified(pending.map { it.id })
        return true
    }

    fun canPostNotifications(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun describe(change: PlanChange): String = when (change.kind) {
        PlanChangeKind.CANCELLED -> "${change.groupName}：已取消「${change.title}」"
        PlanChangeKind.REOPENED -> "${change.groupName}：重新启用「${change.title}」"
        PlanChangeKind.COMPLETED -> "${change.groupName}：已完成「${change.title}」"
        PlanChangeKind.DEADLINE_EARLIER ->
            "${change.groupName}：截止提前至 ${formatDeadline(change.newValue)}｜${change.title}"
        PlanChangeKind.DEADLINE_LATER ->
            "${change.groupName}：截止延后至 ${formatDeadline(change.newValue)}｜${change.title}"
        PlanChangeKind.DEADLINE_ADDED ->
            "${change.groupName}：新增截止 ${formatDeadline(change.newValue)}｜${change.title}"
        PlanChangeKind.DEADLINE_REMOVED -> "${change.groupName}：已移除截止｜${change.title}"
        PlanChangeKind.ASSIGNEE_CHANGED ->
            "${change.groupName}：负责人改为 ${change.newValue ?: "未明确"}｜${change.title}"
        PlanChangeKind.CREATED -> "${change.groupName}：新增「${change.title}」"
        PlanChangeKind.DETAILS_UPDATED -> "${change.groupName}：已更新「${change.title}」"
    }

    private fun formatDeadline(epochValue: String?): String {
        val epoch = epochValue?.toLongOrNull() ?: return "未明确"
        return FORMATTER.format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))
    }

    private val FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
}
