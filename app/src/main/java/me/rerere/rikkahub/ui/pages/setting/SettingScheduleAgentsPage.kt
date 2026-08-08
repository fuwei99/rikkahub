package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentManager
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentScheduler
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentTemplate
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 定时任务（Schedule Agents）设置页（PLAN_SCHEDULE_AGENTS §5）。
 *
 * 最小化 UI：列表 + 启停 Switch，符合「不手动管理」——配置本体在 JSON 文件
 * （filesDir/schedule-agents/ 下的 .json 文件），AI 可直接改文件，这里只做开关与只读展示。
 */
@Composable
fun SettingScheduleAgentsPage(
    manager: ScheduleAgentManager = koinInject(),
    scheduler: ScheduleAgentScheduler = koinInject(),
    settingsStore: SettingsStore = koinInject(),
) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var refreshKey by remember { mutableStateOf(0) }
    val templates = remember(refreshKey) { manager.listTemplates(includeDisabled = true) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("定时任务") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("定时任务 Agent", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "到点由调度器往该任务自己的可见对话投递系统消息，AI 干完活汇报会直接弹系统通知。" +
                            "配置 = JSON 文件，可直接让 AI 帮你改（${manager.templatesDirPath()}/*.json）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(templates, key = { it.id }) { template ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListItem(
                        headlineContent = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(scheduleSummary(template, settings), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            Switch(
                                checked = template.enabled,
                                onCheckedChange = { enabled ->
                                    manager.setTemplateEnabled(template.id, enabled)
                                    // 开关变化 → 重排 / 取消闹钟
                                    runCatching { scheduler.rescheduleAll() }
                                    refreshKey++
                                },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.onClick {
                            expandedId = if (expandedId == template.id) null else template.id
                        },
                    )
                    AnimatedVisibility(visible = expandedId == template.id) {
                        TemplateReadOnlyDetails(
                            template = template,
                            assistantName = template.assistantId
                                ?.let { id -> settings.assistants.firstOrNull { it.id == id }?.name?.ifBlank { "(未命名)" } }
                                ?: "未绑定（AGENTS / 模板人格）",
                        )
                    }
                }
            }
        }
    }
}

private fun scheduleSummary(template: ScheduleAgentTemplate, settings: me.rerere.rikkahub.data.datastore.Settings): String {
    val schedule = template.dailyAt?.let { "每天 $it" }
        ?: "每 ${template.intervalMinutes} 分钟"
    val mode = if (template.reuseConversation) "常驻会话" else "每次新会话"
    val scope = if (template.onlyDuringSupervision) " · 仅监督时段" else ""
    val assistant = template.assistantId
        ?.let { id -> settings.assistants.firstOrNull { it.id == id }?.name?.ifBlank { "(未命名)" } }
        ?: "未绑定助手"
    return "$schedule · $mode · $assistant$scope"
}

/** 只读展示 JSON 字段（不内置复杂编辑器，提示直接改文件） */
@Composable
private fun TemplateReadOnlyDetails(
    template: ScheduleAgentTemplate,
    assistantName: String,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (template.description.isNotBlank()) {
                Text(template.description, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailItem("周期", template.dailyAt ?: "${template.intervalMinutes} 分钟")
                DetailItem("会话", if (template.reuseConversation) "复用" else "每次新建")
                DetailItem("绑定", assistantName)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailItem("记忆", if (template.inheritMemory) "继承" else "隔离")
                DetailItem("记忆图", if (template.inheritMemoryGraph) "继承" else "关闭")
                DetailItem("仅监督时段", if (template.onlyDuringSupervision) "是" else "否")
            }
            Text(
                "任务指令：${template.taskPrompt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "想改周期 / 绑定的助手 / 指令等，直接编辑对应 JSON 文件或让 AI 帮你改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
