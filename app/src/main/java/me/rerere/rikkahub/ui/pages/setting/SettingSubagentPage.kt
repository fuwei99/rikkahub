package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.agent.AgentApprovalMode
import me.rerere.rikkahub.data.ai.agent.AgentReportMode
import me.rerere.rikkahub.data.ai.subagent.SubagentTemplate
import me.rerere.rikkahub.data.ai.subagent.SubagentTemplateManager
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingSubagentPage(
    templateManager: SubagentTemplateManager = koinInject(),
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var refreshKey by remember { mutableStateOf(0) }
    val templates = remember(refreshKey) { templateManager.listTemplates(includeDisabled = true) }
    var expandedId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("子代理") },
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
                start = 16.dp + innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp + innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("子代理模板", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "这里控制 agent 工具可用的模板。每个子代理会在一个真实对话里干活，你可以随时点开围观、插话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                // 人类总闸（收敛设计 §7.4）：模板可声明高危权限，但默认被降级，
                // 只有这里显式打开才生效——模板文件可以被复制甚至由 AI 生成，人需要总闸。
                ListItem(
                    headlineContent = { Text("允许模板自行授予高危权限") },
                    supportingContent = {
                        Text(
                            if (settings.subagentMasterGate) {
                                "已开：模板声明完全生效（自动审批 / 打断权 / 非常规通知通道），你为此负责"
                            } else {
                                "已关（推荐）：模板里的「自动审批」「打断权」「非常规通知通道」一律降为保守值，派活时会提示降级项"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = settings.subagentMasterGate,
                            onCheckedChange = { checked ->
                                vm.updateSettings(settings.copy(subagentMasterGate = checked))
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            }
            items(templates, key = { it.id }) { template ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListItem(
                        headlineContent = { Text(template.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(template.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            Switch(
                                checked = template.enabled,
                                onCheckedChange = { enabled ->
                                    templateManager.setTemplateEnabled(template.id, enabled)
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
                        TemplateAdvancedEditor(
                            template = template,
                            onUpdate = { transform ->
                                templateManager.updateTemplate(template.id, transform = transform)
                                refreshKey++
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「对话即 Agent」扩展字段编辑（方案 2026-08-07 §4.6）。
 *
 * 只暴露真正影响行为、且用户能理解的几项；工具白名单等留给模板 json 手改
 * （字符串列表放在设置页里编辑体验很差，且写错会直接让子 agent 没工具可用）。
 */
@Composable
private fun TemplateAdvancedEditor(
    template: SubagentTemplate,
    onUpdate: ((SubagentTemplate) -> SubagentTemplate) -> Unit,
) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LabeledChipRow(
                label = "工具审批",
                hint = "危险工具（shell / 写文件 / patch / 闹钟 / 通知）无论选什么都强制真人确认",
                options = listOf(
                    AgentApprovalMode.AUTO to "自动放行",
                    AgentApprovalMode.PARENT to "上层 agent 审批",
                    AgentApprovalMode.USER to "只由我审批",
                ),
                selected = AgentApprovalMode.normalize(template.approvalMode),
                onSelect = { value -> onUpdate { it.copy(approvalMode = value) } },
            )

            LabeledChipRow(
                label = "回报方式",
                hint = "自动 = 跑完自动把摘要回报给派活方；手动 = 只有它自己调 agent_report 才回报",
                options = listOf(
                    AgentReportMode.AUTO to "自动回报",
                    AgentReportMode.MANUAL to "手动回报",
                ),
                selected = AgentReportMode.normalize(template.reportMode),
                onSelect = { value -> onUpdate { it.copy(reportMode = value) } },
            )

            LabeledChipRow(
                label = "可见性",
                hint = "对话 = 落成真实对话可围观；静默 = 走旧黑盒执行，不建对话（适合纯批处理）",
                options = listOf(
                    "conversation" to "独立对话",
                    "silent" to "静默执行",
                ),
                selected = if (template.visibility == "silent") "silent" else "conversation",
                onSelect = { value -> onUpdate { it.copy(visibility = value) } },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "步数上限",
                    value = template.maxSteps,
                    onValue = { v -> onUpdate { it.copy(maxSteps = v) } },
                    modifier = Modifier.fillMaxWidth(0.5f),
                )
                NumberField(
                    label = "超时(分钟)",
                    value = template.timeoutMinutes,
                    onValue = { v -> onUpdate { it.copy(timeoutMinutes = v) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            NumberField(
                label = "token 预算",
                value = template.maxTotalTokens,
                onValue = { v -> onUpdate { it.copy(maxTotalTokens = v) } },
                modifier = Modifier.fillMaxWidth(),
            )

            ListItem(
                headlineContent = { Text("允许与平级 agent 互发消息") },
                supportingContent = {
                    Text(
                        "开启后它能给 peers 白名单里的 agent 发消息，用于多 agent 协作",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = template.allowPeerMessaging,
                        onCheckedChange = { checked ->
                            onUpdate { it.copy(allowPeerMessaging = checked) }
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            )

            // ---- 声明式权限（收敛设计 §7.2，落地 plan Step 5）----

            ListItem(
                headlineContent = { Text("允许再派子 agent（派生权）") },
                supportingContent = {
                    Text(
                        "开启且预算 >0 时，这个 agent 可以再委派下级（深度上限全局为 2）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = template.canSpawn,
                        onCheckedChange = { checked ->
                            onUpdate {
                                it.copy(
                                    canSpawn = checked,
                                    spawnBudget = if (checked && it.spawnBudget <= 0) 1 else it.spawnBudget,
                                )
                            }
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            )

            if (template.canSpawn) {
                NumberField(
                    label = "派生预算（同时活跃几个）",
                    value = template.spawnBudget.coerceAtLeast(1),
                    onValue = { v -> onUpdate { it.copy(spawnBudget = v) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            LabeledChipRow(
                label = "打断权（下批生效）",
                hint = "能否用紧急消息打断别人的当前轮次；抢占调度下一批接线，这里先声明。总闸关闭时一律按「不可打断」处理",
                options = listOf(
                    "none" to "不可打断",
                    "parent" to "可打断上级",
                    "peers" to "可打断平级",
                    "all" to "全部可打断",
                ),
                selected = template.interruptRight.takeIf { it in listOf("none", "parent", "peers", "all") } ?: "none",
                onSelect = { value -> onUpdate { it.copy(interruptRight = value) } },
            )

            Text(
                text = "工具白名单、模型指定（modelUuid）等仍在模板 json 里编辑：${template.id}.json",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledChipRow(
    label: String,
    hint: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(8)
            text.toIntOrNull()?.takeIf { it > 0 }?.let(onValue)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = modifier,
    )
}
