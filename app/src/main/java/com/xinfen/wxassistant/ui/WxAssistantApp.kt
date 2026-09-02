package com.xinfen.wxassistant.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.xinfen.wxassistant.data.GroupConfig
import com.xinfen.wxassistant.data.LocalStore
import com.xinfen.wxassistant.data.PlanChangeKind
import com.xinfen.wxassistant.data.PlanItem
import com.xinfen.wxassistant.data.PlanItemStatus
import com.xinfen.wxassistant.data.SavedSummary
import com.xinfen.wxassistant.data.StoredMessage
import com.xinfen.wxassistant.data.UserPreferences
import com.xinfen.wxassistant.domain.ChatGptPromptComposer
import com.xinfen.wxassistant.domain.GroupChatRef
import com.xinfen.wxassistant.domain.GroupMessage
import com.xinfen.wxassistant.domain.MessageSource
import com.xinfen.wxassistant.integration.ChatGptHandoff
import com.xinfen.wxassistant.integration.ChatGptResultParser
import com.xinfen.wxassistant.integration.ResultParseException
import com.xinfen.wxassistant.integration.StructuredChatGptPrompt
import com.xinfen.wxassistant.notification.PlanChangeNotifier
import com.xinfen.wxassistant.service.WeChatCaptureContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun WxAssistantApp(incomingSharedText: String?, onSharedTextHandled: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { UserPreferences(context) }
    var accepted by remember { mutableStateOf(preferences.disclosureAccepted) }
    if (!accepted) {
        DisclosureScreen {
            preferences.disclosureAccepted = true
            accepted = true
        }
        return
    }
    DashboardScreen(incomingSharedText, onSharedTextHandled)
}

@Composable
private fun DisclosureScreen(onAccept: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("群聊助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("启用前请确认数据使用方式", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            DisclosurePoint("读取范围", "通知使用权读取微信通知；无障碍仅读取你选择的置顶群在当前屏幕可见的文字。")
            DisclosurePoint("严格只读微信", "不会在微信输入、回复、粘贴或发送任何消息。扫描只会打开已选会话、读取并返回。")
            DisclosurePoint("ChatGPT 交接", "消息先在本机整理；只有你点击“交给 ChatGPT”时，所选内容才通过系统分享交给 ChatGPT。")
            DisclosurePoint("结果回流", "在 ChatGPT 中把完整回复分享回本 App 后，App 会自动更新摘要和计划，并对重要变更发通知。")
            DisclosurePoint("本地控制", "未选中的聊天不落库；群消息默认保留 30 天，摘要与计划保留到更新或清空。本 App 自身不申请联网权限。")
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                Text("我已阅读并同意上述用途")
            }
            Button(
                onClick = onAccept,
                enabled = checked,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("同意并继续") }
        }
    }
}

@Composable
private fun DisclosurePoint(title: String, body: String) {
    Column(Modifier.padding(bottom = 14.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(incomingSharedText: String?, onSharedTextHandled: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalStore.get(context) }
    val preferences = remember { UserPreferences(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<GroupConfig>>(emptyList()) }
    var messages by remember { mutableStateOf<List<StoredMessage>>(emptyList()) }
    var summaries by remember { mutableStateOf<List<SavedSummary>>(emptyList()) }
    var plans by remember { mutableStateOf<List<PlanItem>>(emptyList()) }
    var notificationAccess by remember { mutableStateOf(false) }
    var accessibilityAccess by remember { mutableStateOf(false) }
    var chatGptInstalled by remember { mutableStateOf(false) }
    var rangeHours by remember { mutableIntStateOf(72) }
    var manualGroup by remember { mutableStateOf("") }
    var previewPrompt by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showManualImport by remember { mutableStateOf(false) }
    var manualImportText by remember { mutableStateOf("") }

    fun refresh() {
        groups = store.groups()
        messages = store.messages(limit = 500)
        summaries = store.summaries()
        plans = store.planItems()
        notificationAccess = hasNotificationAccess(context)
        accessibilityAccess = hasAccessibilityAccess(context)
        chatGptInstalled = ChatGptHandoff.isChatGptInstalled(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) PlanChangeNotifier.notifyPending(context)
        refresh()
    }

    suspend fun importChatGptResult(shared: String) {
        try {
            val parsed = ChatGptResultParser().parse(shared)
            if (!preferences.isValidChatGptExchange(parsed.exchangeToken)) {
                throw ResultParseException("结果不是由本 App 最近生成的提示词产生，或已超过 7 天")
            }
            val report = store.mergeChatGptResult(parsed)
            refresh()
            PlanChangeNotifier.notifyPending(context)
            val important = report.changes.count {
                it.kind != PlanChangeKind.CREATED && it.kind != PlanChangeKind.DETAILS_UPDATED
            }
            snackbar.showSnackbar(
                if (report.summariesUpdated == 0 && report.planItemsUpdated == 0) {
                    "没有导入：ChatGPT 返回的群名不在已选择列表"
                } else {
                    preferences.consumeChatGptExchange(parsed.exchangeToken)
                    "已导入 ${report.summariesUpdated} 份摘要、${report.planItemsUpdated} 项计划；发现 $important 项重要变更"
                },
            )
        } catch (error: ResultParseException) {
            snackbar.showSnackbar(error.message ?: "无法解析 ChatGPT 结果")
        }
    }

    LaunchedEffect(Unit) {
        val cutoff = System.currentTimeMillis() - preferences.retentionDays * 24L * 60L * 60L * 1000L
        store.deleteMessagesOlderThan(cutoff)
        while (true) {
            refresh()
            if (PlanChangeNotifier.canPostNotifications(context)) {
                PlanChangeNotifier.notifyPending(context)
            }
            delay(2_500L)
        }
    }

    LaunchedEffect(incomingSharedText) {
        val shared = incomingSharedText ?: return@LaunchedEffect
        try {
            importChatGptResult(shared)
        } finally {
            onSharedTextHandled()
        }
    }

    fun updateAllowlist() {
        WeChatCaptureContract.replacePinnedGroupAllowlist(context, store.selectedGroupNames())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("群聊助手", fontWeight = FontWeight.Bold)
                        Text("微信只读 · ChatGPT 总结", style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ReadOnlyBanner() }
            item { SectionTitle("1. 授权与采集", "通知后台自动收集；置顶群历史由你点一次扫描启动") }
            item {
                PermissionCard(
                    title = "微信通知使用权",
                    detail = if (notificationAccess) "已开启，只保存已选群" else "未开启，无法后台收集新群消息",
                    granted = notificationAccess,
                    button = "打开设置",
                    onClick = { safeStart(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                )
            }
            item {
                PermissionCard(
                    title = "无障碍只读服务",
                    detail = if (accessibilityAccess) "已开启，不具备输入和发送路径" else "未开启，无法扫描置顶群可见消息",
                    granted = accessibilityAccess,
                    button = "打开设置",
                    onClick = { safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    val canNotify = PlanChangeNotifier.canPostNotifications(context)
                    PermissionCard(
                        title = "计划变更通知",
                        detail = if (canNotify) "已允许" else "需要允许，才能提醒取消和截止变化",
                        granted = canNotify,
                        button = "允许通知",
                        onClick = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    )
                }
            }
            item { SectionTitle("2. 选择微信群", "未选中的会话不会保存消息，也不会交给 ChatGPT") }
            if (groups.isEmpty()) {
                item { EmptyCard("尚未发现群聊。可先输入准确群名，或开启无障碍后在微信会话列表点“发现置顶群”。") }
            } else {
                items(groups, key = { it.name }) { group ->
                    GroupRow(group) { selected ->
                        store.setGroupSelected(group.name, selected)
                        updateAllowlist()
                        refresh()
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = manualGroup,
                            onValueChange = { manualGroup = it.take(160) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("准确群名") },
                            supportingText = { Text("微信不暴露“置顶”标签时可手动添加") },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val name = manualGroup.trim()
                                    if (name.isNotEmpty()) {
                                        store.rememberCandidate(name)
                                        store.setGroupSelected(name, true)
                                        manualGroup = ""
                                        updateAllowlist()
                                        refresh()
                                    }
                                },
                                enabled = manualGroup.isNotBlank(),
                                modifier = Modifier.weight(1f),
                            ) { Text("添加并选择") }
                            Button(
                                onClick = {
                                    val selected = store.selectedGroupNames()
                                    WeChatCaptureContract.requestPinnedGroupRead(
                                        context = context,
                                        groupTitles = selected,
                                        allowNavigation = selected.isNotEmpty(),
                                        ttlMs = 60_000L,
                                    )
                                    val launch = context.packageManager.getLaunchIntentForPackage(
                                        WeChatCaptureContract.WECHAT_PACKAGE,
                                    )
                                    if (launch != null) context.startActivity(launch)
                                    else scope.launch { snackbar.showSnackbar("未找到微信") }
                                },
                                enabled = accessibilityAccess,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (groups.isEmpty()) "发现置顶群" else "扫描已选群") }
                        }
                    }
                }
            }
            item { SectionTitle("3. 交给 ChatGPT", "选时间范围，预览后通过系统分享进入手机 ChatGPT") }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (chatGptInstalled) "已检测到 ChatGPT" else "未检测到 ChatGPT，将打开系统分享选择器",
                            color = if (chatGptInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RangeChip("24 小时", 24, rangeHours) { rangeHours = it }
                            RangeChip("3 天", 72, rangeHours) { rangeHours = it }
                            RangeChip("7 天", 168, rangeHours) { rangeHours = it }
                        }
                        val selected = groups.filter { it.selected }
                        val selectedNames = selected.mapTo(hashSetOf()) { it.name }
                        val cutoff = System.currentTimeMillis() - rangeHours * 60L * 60L * 1000L
                        val count = messages.count { it.groupName in selectedNames && it.eventAt >= cutoff }
                        val selectedPlanCount = plans.count { it.groupName in selectedNames }
                        Text("将整理 $count 条消息，当前计划 $selectedPlanCount 项", style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                runCatching {
                                    val token = preferences.beginChatGptExchange()
                                    buildPrompt(messages, selected, plans, rangeHours, token)
                                }
                                    .onSuccess { previewPrompt = it }
                                    .onFailure {
                                        scope.launch { snackbar.showSnackbar(it.message ?: "无法生成提示词") }
                                    }
                            },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) { Text("预览并交给 ChatGPT") }
                        Text(
                            "ChatGPT 回答后：点回答的“分享” → 选择“群聊助手”。App 会自动更新下方摘要和计划表。",
                            modifier = Modifier.padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { showManualImport = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text("粘贴导入 ChatGPT 完整回复") }
                    }
                }
            }
            item { SectionTitle("摘要", if (summaries.isEmpty()) "等待从 ChatGPT 分享回结果" else "最近一次导入") }
            if (summaries.isEmpty()) item { EmptyCard("还没有 ChatGPT 摘要。") }
            else items(summaries, key = { it.groupName }) { SummaryCard(it) }
            item { SectionTitle("计划表", "取消、完成与截止变化会覆盖原条目，不会因一次遗漏而删除") }
            if (plans.isEmpty()) item { EmptyCard("还没有计划项。导入 ChatGPT 结果后会自动生成。") }
            else items(plans, key = { it.stableKey }) { PlanRow(it) }
            item { SectionTitle("最近采集", "本机预览，仅显示最近 8 条") }
            items(messages.takeLast(8).asReversed(), key = { it.id }) { MessageRow(it) }
            item {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp),
                ) { Text("清空全部本地数据") }
            }
        }
    }

    previewPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { previewPrompt = null },
            title = { Text("交给 ChatGPT 前确认") },
            text = {
                Column {
                    Text("这会把已选群在所选时间内的消息和当前计划分享给 ChatGPT。不会发送到微信。")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        prompt.take(1_500) + if (prompt.length > 1_500) "\n…（预览已截断）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 16,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    previewPrompt = null
                    try {
                        context.startActivity(ChatGptHandoff.createShareIntent(context, prompt))
                        preferences.lastChatGptHandoffAt = System.currentTimeMillis()
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("没有可接收文本的应用") }
                    }
                }) { Text("交给 ChatGPT") }
            },
            dismissButton = { TextButton(onClick = { previewPrompt = null }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("清空全部本地数据？") },
            text = { Text("将删除群白名单、采集消息、摘要、计划和变更记录，无法恢复。不会删除微信或 ChatGPT 中的内容。") },
            confirmButton = {
                Button(onClick = {
                    store.deleteAllCapturedData()
                    WeChatCaptureContract.replacePinnedGroupAllowlist(context, emptySet())
                    confirmDelete = false
                    refresh()
                }) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    if (showManualImport) {
        AlertDialog(
            onDismissRequest = { showManualImport = false },
            title = { Text("导入 ChatGPT 回复") },
            text = {
                OutlinedTextField(
                    value = manualImportText,
                    onValueChange = { manualImportText = it.take(200_000) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14,
                    label = { Text("粘贴包含 JSON 代码块的完整回复") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = manualImportText
                        showManualImport = false
                        manualImportText = ""
                        scope.launch { importChatGptResult(text) }
                    },
                    enabled = manualImportText.isNotBlank(),
                ) { Text("导入并更新计划") }
            },
            dismissButton = { TextButton(onClick = { showManualImport = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ReadOnlyBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text("微信严格只读", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "代码中没有微信文本输入、粘贴或发送动作。ChatGPT 结果通过你主动分享回 App。",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PermissionCard(
    title: String,
    detail: String,
    granted: Boolean,
    button: String,
    onClick: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) Text("已开启", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            else OutlinedButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun GroupRow(group: GroupConfig, onToggle: (Boolean) -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name, fontWeight = FontWeight.Medium)
                Text(if (group.pinned) "置顶群候选" else "群聊候选", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = group.selected, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun RangeChip(label: String, hours: Int, selectedHours: Int, onSelect: (Int) -> Unit) {
    FilterChip(selected = hours == selectedHours, onClick = { onSelect(hours) }, label = { Text(label) })
}

@Composable
private fun SummaryCard(summary: SavedSummary) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(summary.groupName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(summary.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                "导入于 ${formatDateTime(summary.importedAt)}",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlanRow(item: PlanItem) {
    val container = when (item.status) {
        PlanItemStatus.ACTIVE -> MaterialTheme.colorScheme.surfaceVariant
        PlanItemStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
        PlanItemStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(
                    when (item.status) {
                        PlanItemStatus.ACTIVE -> "进行中"
                        PlanItemStatus.CANCELLED -> "已取消"
                        PlanItemStatus.COMPLETED -> "已完成"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("群聊：${item.groupName}  ·  类型：${item.type.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
            Text("负责人：${item.assignee ?: "未明确"}", style = MaterialTheme.typography.bodySmall)
            Text("截止：${item.deadlineAt?.let(::formatDateTime) ?: "未明确"}", style = MaterialTheme.typography.bodyMedium)
            if (item.source.isNotBlank()) {
                Text(
                    "依据：${item.source}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(message: StoredMessage) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "${message.groupName} · ${message.sender ?: "未知发送者"} · ${formatDateTime(message.eventAt)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(message.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card {
        Box(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun buildPrompt(
    storedMessages: List<StoredMessage>,
    selectedGroups: List<GroupConfig>,
    currentPlan: List<PlanItem>,
    rangeHours: Int,
    exchangeToken: String,
): String {
    require(selectedGroups.isNotEmpty()) { "请先选择至少一个群" }
    val zone = ZoneId.systemDefault()
    val end = LocalDateTime.now(zone)
    val start = end.minusHours(rangeHours.toLong())
    val names = selectedGroups.mapTo(linkedSetOf()) { it.name }
    val messages = storedMessages
        .filter { it.groupName in names }
        .map { stored ->
            GroupMessage(
                id = stored.id.toString(),
                groupId = stored.groupName,
                groupName = stored.groupName,
                sender = stored.sender,
                content = stored.body,
                receivedAt = Instant.ofEpochMilli(stored.eventAt).atZone(zone).toLocalDateTime(),
                source = when (stored.source) {
                    com.xinfen.wxassistant.data.CaptureSource.ACCESSIBILITY -> MessageSource.ACCESSIBILITY
                    com.xinfen.wxassistant.data.CaptureSource.NOTIFICATION -> MessageSource.NOTIFICATION
                },
            )
        }
    val base = ChatGptPromptComposer().compose(
        messages = messages,
        selectedGroups = selectedGroups.map { GroupChatRef(it.name, it.name) },
        rangeStart = start,
        rangeEnd = end,
    ).text
    return StructuredChatGptPrompt.build(
        basePrompt = base,
        currentPlan = currentPlan.filter { it.groupName in names },
        exchangeToken = exchangeToken,
    )
}

private fun hasNotificationAccess(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

private fun hasAccessibilityAccess(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            info.resolveInfo?.serviceInfo?.let { service ->
                service.packageName == context.packageName &&
                    service.name.endsWith("WeChatReadOnlyAccessibilityService")
            } == true
        }
}

private fun safeStart(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { if (context is Activity) context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

private fun formatDateTime(epochMillis: Long): String = DATE_TIME_FORMAT.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
