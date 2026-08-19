package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.rikkahub.data.ai.tools.WORKSPACE_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.PendingUnlock
import me.rerere.rikkahub.data.model.SupervisionSchedule
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.model.ToolFilter
import me.rerere.rikkahub.data.model.isUnlockStale
import me.rerere.rikkahub.data.model.currentSessionEndAt
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** epoch millis → 本地 "HH:mm"（跨天时前缀「次日」）。 */
private fun formatClock(epochMillis: Long, nowMs: Long = System.currentTimeMillis()): String {
    val tz = TimeZone.currentSystemDefault()
    val target = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
    val prefix = when {
        target.date == today -> ""
        else -> "次日 "
    }
    return "$prefix%02d:%02d".format(target.hour, target.minute)
}

/**
 * 时段编辑的保存栏。
 *
 * - [dirty] = 有未保存草稿时高亮提示 + 显示「保存 / 放弃」；
 * - 否则若 [deferUntil] 尚未过期，说明用户上次选了「下一段再说」，这里给个状态回显，
 *   免得他以为总开关没生效。
 */
@Composable
private fun SaveBar(
    dirty: Boolean,
    deferUntil: Long,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val now = System.currentTimeMillis()
    when {
        dirty -> Column {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "⚠️ 时段 / 总开关有未保存的修改，点「保存」后才会生效。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                    Text("放弃更改")
                }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("保存")
                }
            }
        }
        deferUntil > now -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "⏭️ 本段已跳过（${formatClock(deferUntil)} 之前不锁），从下一个监督时段开始生效。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSupervisionPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sup = settings.supervision
    // 用 tick 强制重组，使 isActiveNow 跨时段自动刷新（每 30 秒检查一次）
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val active = @Suppress("UNUSED_EXPRESSION") tick.let { sup.isActiveNow() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(newSup: SupervisionSettings) {
        vm.updateSettings(settings.copy(supervision = newSup))
    }

    // ── 草稿模式（总开关 + 时段）─────────────────────────────────────────────
    // 这两项决定「此刻是否锁」，即时生效会导致时段还没配完就把自己铐上。
    // 所以它们只改本地草稿，点「保存」才落库；且若新配置此刻已命中时段，
    // 会先问一句「是否立即开始监督」，选「否」就跳过本段（写 deferUntil）。
    var draftEnabled by remember { mutableStateOf(sup.enabled) }
    var draftSchedules by remember { mutableStateOf(sup.schedules) }
    var dirty by remember { mutableStateOf(false) }
    // 外部变更（云同步下拉 / 时段结束自动解锁）在无未保存改动时同步进草稿
    LaunchedEffect(sup.enabled, sup.schedules) {
        if (!dirty) {
            draftEnabled = sup.enabled
            draftSchedules = sup.schedules
        }
    }

    /** 待确认的「立即开始监督？」弹窗，值 = 本次时段的结束时刻。 */
    var askStartNow by remember { mutableStateOf<Long?>(null) }

    fun commit(deferUntil: Long) {
        update(
            sup.copy(
                enabled = draftEnabled,
                schedules = draftSchedules,
                deferUntil = deferUntil,
            ),
        )
        dirty = false
        askStartNow = null
    }

    fun onSave() {
        val candidate = sup.copy(enabled = draftEnabled, schedules = draftSchedules)
        val sessionEnd = if (draftEnabled) candidate.currentSessionEndAt() else null
        if (sessionEnd != null) askStartNow = sessionEnd else commit(deferUntil = 0L)
    }

    askStartNow?.let { sessionEnd ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askStartNow = null },
            title = { Text("立即开始监督？") },
            text = {
                Text(
                    "新配置此刻已落在监督时段内（本段 ${formatClock(sessionEnd)} 结束）。\n\n" +
                        "· 立即开始：马上锁定，直到 ${formatClock(sessionEnd)}。\n" +
                        "· 下一段再说：本段跳过不锁，从下一个监督时段开始生效。",
                )
            },
            confirmButton = {
                Button(onClick = { commit(deferUntil = 0L) }) { Text("立即开始") }
            },
            dismissButton = {
                OutlinedButton(onClick = { commit(deferUntil = sessionEnd) }) { Text("下一段再说") }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("专注监督") },
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
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (active) "🔒 监督中" else "🔓 当前未在监督时段",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "监督期内只能使用白名单学习助手，工具 / MCP 受限，且只许加强设置、不许减弱。" +
                                " 这是自律工具（清除应用数据仍可绕过），配合「不做手机控」使用。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("总开关") }) {
                    item(
                        headlineContent = { Text("启用专注监督") },
                        supportingContent = { Text("按下面配置的时间段自动锁定（改完需点保存才生效）") },
                        trailingContent = {
                            Switch(
                                checked = draftEnabled,
                                onCheckedChange = {
                                    draftEnabled = it
                                    dirty = true
                                },
                            )
                        },
                    )
                }
            }

            item {
                SchedulesCard(
                    schedules = draftSchedules,
                    readOnly = active,
                    onChange = {
                        draftSchedules = it
                        dirty = true
                    },
                )
            }

            // ── 保存栏：只有点「保存」草稿才落库（见页面顶部草稿模式说明）───────────
            item {
                SaveBar(
                    dirty = dirty,
                    deferUntil = sup.deferUntil,
                    onSave = { onSave() },
                    onDiscard = {
                        draftEnabled = sup.enabled
                        draftSchedules = sup.schedules
                        dirty = false
                    },
                )
            }

            item {
                AssistantWhitelistCard(
                    settings = settings,
                    whitelist = sup.allowedAssistantIds,
                    readOnly = active,
                    onChange = { update(sup.copy(allowedAssistantIds = it)) },
                )
            }

            item {
                GrantorCard(
                    settings = settings,
                    sup = sup,
                    readOnly = active,
                    onChange = { update(sup.copy(unlockGrantorAssistantId = it)) },
                )
            }

            item {
                UnlockCard(
                    sup = sup,
                    active = active,
                    settings = settings,
                    onChange = ::update,
                )
            }
            item {
                AppealCard(
                    sup = sup,
                    active = active,
                    onChange = ::update,
                )
            }
            item {
                LockedTargetsCard(sup = sup)
            }
            item {
                ToolFilterCard(
                    title = "本地工具",
                    description = "JS 引擎、剪贴板、TTS、日历、子代理、信箱等",
                    filter = sup.localToolFilter,
                    allToolNames = LocalToolOption.ALL_SERIAL_NAMES.toList(),
                    labelOf = { it },
                    readOnly = active,
                    onChange = { update(sup.copy(localToolFilter = it)) },
                )
            }

            item {
                ToolFilterCard(
                    title = "工作区工具",
                    description = "read / write / edit / shell / grep / patch 等文件与命令工具。" +
                        " 学习时段建议黑名单加入 shell / shell_session / write_file / edit_file / apply_patch / codex_patch。",
                    filter = sup.workspaceToolFilter,
                    allToolNames = WORKSPACE_TOOL_NAMES.toList(),
                    labelOf = { it.removePrefix("workspace_") },
                    readOnly = active,
                    onChange = { update(sup.copy(workspaceToolFilter = it)) },
                )
            }

            item {
                McpToolFilterCard(
                    filter = sup.mcpToolFilter,
                    readOnly = active,
                    onChange = { update(sup.copy(mcpToolFilter = it)) },
                )
            }

            item {
                CardGroup(title = { Text("MCP 总闸") }) {
                    item(
                        headlineContent = { Text("锁定 MCP 服务器") },
                        supportingContent = {
                            Text(
                                "监督期禁止新增 / 删除 MCP 服务器、禁止改地址与请求头。" +
                                    "不影响已有服务器的启用开关（那个由下面一项控制）",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = sup.lockMcpServers,
                                onCheckedChange = { update(sup.copy(lockMcpServers = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("锁定 MCP 启用开关") },
                        supportingContent = {
                            Text(
                                "默认关闭。开启后监督期内 MCP 服务器与工具的启用开关「只许关不许开」；" +
                                    "工具级管控建议用上面的 MCP 工具过滤器，不必在这里重复上锁",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = sup.lockMcpToolToggles,
                                onCheckedChange = { update(sup.copy(lockMcpToolToggles = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("锁定 Skill 开关") },
                        supportingContent = {
                            Text(
                                "默认关闭。开启后监督期内不允许改白名单助手的已启用 Skill" +
                                    "（会导致 use_skill 工具整体不注册）",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = sup.lockSkills,
                                onCheckedChange = { update(sup.copy(lockSkills = it)) },
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("定时任务（Schedule Agents）") }) {
                    item(
                        headlineContent = { Text("监督期内运行定时任务") },
                        supportingContent = {
                            Text(
                                if (active) {
                                    "监督期内此开关只能保持开启（定时任务是监督的一部分，查岗等任务照常跑）"
                                } else {
                                    "监督时段内 Schedule Agents（查岗等）是否触发；关闭后监督期内所有定时任务跳过"
                                },
                            )
                        },
                        trailingContent = {
                            // 监督期内 Gate 会回滚「关闭」，直接置灰不可操作（PLAN_SCHEDULE_AGENTS §5.1）
                            Switch(
                                checked = sup.scheduleAgentsEnabledDuringSupervision,
                                enabled = !active,
                                onCheckedChange = {
                                    update(sup.copy(scheduleAgentsEnabledDuringSupervision = it))
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 守门员卡片：指定哪个白名单学习助手拥有「紧急解锁」工具。
 * 只有它能在监督期内发起解锁请求；其他助手（包括其他白名单助手）都不行。
 */
@Composable
private fun GrantorCard(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    sup: SupervisionSettings,
    readOnly: Boolean,
    onChange: (kotlin.uuid.Uuid?) -> Unit,
) {
    val grantor = sup.unlockGrantorAssistantId
    CardGroup(title = { Text("解锁守门员") }) {
        settings.assistants.forEach { a ->
            val inWhitelist = sup.allowedAssistantIds.isEmpty() || a.id in sup.allowedAssistantIds
            if (!inWhitelist) return@forEach
            val selected = a.id == grantor
            val canChange = !readOnly
            item(
                onClick = if (canChange) {
                    { onChange(if (selected) null else a.id) }
                } else null,
                headlineContent = { Text(a.name.ifBlank { "(未命名)" }) },
                supportingContent = {
                    Text(if (selected) "拥有紧急解锁工具" else "点击设为守门员")
                },
                trailingContent = {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = if (canChange) {
                            { if (it) onChange(a.id) else onChange(null) }
                        } else null,
                    )
                },
            )
        }
        item {
            Text(
                "只有被选中的学习助手能在监督期内使用 supervision_admin 的 request_unlock。" +
                    "你需要说服该 AI，AI 判断理由充分后发起解锁请求；你最终在下方确认才会生效。" +
                    "留空 = 监督期完全无法解锁。\n" +
                    "⚠️ 该工具默认关闭：还要去「助手 → 本地工具 → 监督管理工具」把开关打开，" +
                    "否则监督期内连申请解锁的入口都没有（只能等时段结束）。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulesCard(
    schedules: List<SupervisionSchedule>,
    readOnly: Boolean,
    onChange: (List<SupervisionSchedule>) -> Unit,
) {
    CardGroup(title = { Text("监督时段") }) {
        if (schedules.isEmpty()) {
            item {
                Text(
                    "尚未添加时段（开启总开关但无时段不会锁定任何时间）",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        schedules.forEach { s ->
            item {
                ScheduleRow(
                    schedule = s,
                    canDelete = !readOnly,
                    onChange = { next -> onChange(schedules.map { if (it.id == s.id) next else it }) },
                    onDelete = { onChange(schedules.filterNot { it.id == s.id }) },
                )
            }
        }
        item(
            onClick = if (readOnly) null else { {
                onChange(schedules + SupervisionSchedule(daysOfWeek = (1..5).toSet(), startMinute = 22 * 60, endMinute = 7 * 60))
            } },
            headlineContent = { Text("+ 添加时段", color = MaterialTheme.colorScheme.primary) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleRow(
    schedule: SupervisionSchedule,
    canDelete: Boolean,
    onChange: (SupervisionSchedule) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..7).forEach { dow ->
                FilterChip(
                    selected = dow in schedule.daysOfWeek,
                    onClick = {
                        val next = if (dow in schedule.daysOfWeek) {
                            schedule.daysOfWeek - dow
                        } else {
                            schedule.daysOfWeek + dow
                        }
                        onChange(schedule.copy(daysOfWeek = next))
                    },
                    label = { Text(WEEK_LABELS[dow - 1]) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeField(
                label = "开始",
                minuteOfDay = schedule.startMinute,
                onPick = { onChange(schedule.copy(startMinute = it)) },
                modifier = Modifier.weight(1f),
            )
            Text("~")
            TimeField(
                label = "结束",
                minuteOfDay = schedule.endMinute,
                onPick = { onChange(schedule.copy(endMinute = it)) },
                modifier = Modifier.weight(1f),
            )
            if (canDelete) {
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    minuteOfDay: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    OutlinedButton(
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("%02d:%02d".format(h, m), style = MaterialTheme.typography.titleMedium)
        }
    }
    if (showPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(label) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberStepper(
                        value = h, range = 0..23,
                        onChange = { onPick(it * 60 + m) },
                    )
                    Text(":")
                    NumberStepper(
                        value = m, range = 0..59,
                        onChange = { onPick(h * 60 + it) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun NumberStepper(
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }) { Text("-") }
        Text("%02d".format(value), Modifier.padding(horizontal = 12.dp))
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }) { Text("+") }
    }
}

@Composable
private fun AssistantWhitelistCard(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    whitelist: Set<kotlin.uuid.Uuid>,
    readOnly: Boolean,
    onChange: (Set<kotlin.uuid.Uuid>) -> Unit,
) {
    CardGroup(title = { Text("学习助手白名单") }) {
        if (settings.assistants.isEmpty()) {
            item {
                Text(
                    "没有助手可选",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        settings.assistants.forEach { a ->
            val checked = a.id in whitelist
            val canChange = !readOnly || !checked // 监督期只允许取消
            item(
                onClick = if (canChange) {
                    {
                        onChange(if (checked) whitelist - a.id else whitelist + a.id)
                    }
                } else null,
                headlineContent = { Text(a.name.ifBlank { "(未命名)" }) },
                trailingContent = {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = if (canChange) {
                            { onChange(if (it) whitelist + a.id else whitelist - a.id) }
                        } else null,
                    )
                },
            )
        }
        item {
            Text(
                "监督期内只允许打开勾选的助手；其他助手在抽屉中置灰。留空表示不限制（不推荐）。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolFilterCard(
    title: String,
    description: String,
    filter: ToolFilter,
    allToolNames: List<String>,
    labelOf: (String) -> String,
    readOnly: Boolean,
    onChange: (ToolFilter) -> Unit,
) {
    CardGroup(title = { Text(title) }) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = filter.mode == ToolFilter.Mode.BLACKLIST,
                        onClick = {
                            // 监督期不允许白→黑（见 Gate）；UI 层直接禁用
                            if (!readOnly || filter.mode != ToolFilter.Mode.WHITELIST) {
                                onChange(filter.copy(mode = ToolFilter.Mode.BLACKLIST))
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("黑名单") }
                    SegmentedButton(
                        selected = filter.mode == ToolFilter.Mode.WHITELIST,
                        onClick = { onChange(filter.copy(mode = ToolFilter.Mode.WHITELIST)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("白名单") }
                }
            }
        }
        allToolNames.forEach { name ->
            val isInList = name in filter.items
            val canCheck = when (filter.mode) {
                ToolFilter.Mode.BLACKLIST -> !readOnly || !isInList // 黑名单：只许加
                ToolFilter.Mode.WHITELIST -> !readOnly || isInList  // 白名单：只许减
            }
            item(
                onClick = if (canCheck) {
                    {
                        val next = if (isInList) filter.items - name else filter.items + name
                        onChange(filter.copy(items = next))
                    }
                } else null,
                headlineContent = { Text(labelOf(name)) },
                trailingContent = {
                    Checkbox(
                        checked = isInList,
                        onCheckedChange = if (canCheck) {
                            {
                                val next = if (it) filter.items + name else filter.items - name
                                onChange(filter.copy(items = next))
                            }
                        } else null,
                    )
                },
            )
        }
    }
}

@Composable
private fun McpToolFilterCard(
    filter: ToolFilter,
    readOnly: Boolean,
    onChange: (ToolFilter) -> Unit,
) {
    val settings by koinViewModel<SettingVM>().settings.collectAsStateWithLifecycle()
    val allMcpTools = remember(settings.mcpServers) {
        settings.mcpServers
            .filter { it.commonOptions.enable }
            .flatMap { server ->
                server.commonOptions.tools.map { tool ->
                    "${server.id}/${tool.name}" to "${server.commonOptions.name.ifBlank { server.id.toString() }} / ${tool.name}"
                }
            }
    }

    if (allMcpTools.isEmpty()) {
        CardGroup(title = { Text("MCP 工具") }) {
            item {
                Text(
                    "未发现可用的 MCP 工具（请先在「MCP 服务器」中添加并启用）",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    ToolFilterCard(
        title = "MCP 工具",
        description = "按「服务器/工具」过滤。黑名单勾上表示禁用；白名单勾上表示唯一允许。",
        filter = filter,
        allToolNames = allMcpTools.map { it.first },
        labelOf = { key -> allMcpTools.firstOrNull { it.first == key }?.second ?: key },
        readOnly = readOnly,
        onChange = onChange,
    )
}

/**
 * 申诉窗口参数（PLAN_SUPERVISION_ADMIN_TOOL §5.1）。
 *
 * 三个字段的加严方向都是**变小**（与冷却时间的 maxOf 相反）：倒计时越短、
 * 延长次数越少 = 越严。所以监督期内 Gate 只放行调小，这里干脆整组置灰，
 * 免得用户在监督期里怎么点都弹回去，又来一个「改不动」的 bug 报告。
 */
@Composable
private fun AppealCard(
    sup: SupervisionSettings,
    active: Boolean,
    onChange: (SupervisionSettings) -> Unit,
) {
    CardGroup(title = { Text("锁定申诉") }) {
        item(
            headlineContent = { Text("申诉倒计时（秒）") },
            supportingContent = {
                Text("监工要锁对话/路径时先弹窗给你这么多秒。0 = 不给申诉机会，直接锁。")
            },
            trailingContent = {
                if (active) Text("${sup.appealCountdownSeconds} 秒") else {
                    NumberStepper(
                        value = sup.appealCountdownSeconds,
                        range = 0..600,
                        step = 30,
                        onChange = { onChange(sup.copy(appealCountdownSeconds = it)) },
                    )
                }
            },
        )
        item(
            headlineContent = { Text("最多延长次数") },
            supportingContent = {
                Text("「再给一会儿」可点几次（必须先写申诉理由才能点）。0 = 不允许延长。")
            },
            trailingContent = {
                if (active) Text("${sup.appealMaxExtensions} 次") else {
                    NumberStepper(
                        value = sup.appealMaxExtensions,
                        range = 0..5,
                        onChange = { onChange(sup.copy(appealMaxExtensions = it)) },
                    )
                }
            },
        )
        item(
            headlineContent = {
                Text(
                    "每次延长秒数",
                    color = if (sup.appealMaxExtensions == 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            },
            supportingContent = {
                Text(
                    if (sup.appealMaxExtensions == 0) {
                        "延长次数为 0，本项无效。"
                    } else {
                        "每点一次「再给一会儿」追加的秒数。"
                    }
                )
            },
            trailingContent = {
                if (active || sup.appealMaxExtensions == 0) {
                    Text("${sup.appealExtensionSeconds} 秒")
                } else {
                    NumberStepper(
                        value = sup.appealExtensionSeconds,
                        range = 10..600,
                        step = 30,
                        onChange = { onChange(sup.copy(appealExtensionSeconds = it)) },
                    )
                }
            },
        )
        if (active) {
            item {
                Text(
                    "⚠️ 监督期内这三项只能调小（调大会被闸门回滚），所以整组暂时锁死；" +
                        "要放宽请等本时段结束。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 已被监督管理工具锁定的对话 / 路径（§4：可见即非黑箱）。
 *
 * 刻意**不给解锁按钮**：能在这里一键解锁的话这套锁就是纸糊的。
 * 想解只有两条路——等监督时段结束（锁自动失效），或者去找守门员申诉。
 */
@Composable
private fun LockedTargetsCard(sup: SupervisionSettings) {
    if (sup.lockedConversationIds.isEmpty() && sup.lockedWorkspacePaths.isEmpty()) return
    CardGroup(title = { Text("已锁定的对话 / 路径") }) {
        sup.lockedConversationIds.forEach { id ->
            item(
                headlineContent = { Text("对话 ${id.toString().take(8)}…") },
                supportingContent = { Text("监督时段内该对话无法发送消息") },
            )
        }
        sup.lockedWorkspacePaths.forEach { path ->
            item(
                headlineContent = { Text(path) },
                supportingContent = { Text("监督时段内该路径下的文件工具被拒；shell 仅拒绝显式引用它的命令") },
            )
        }
        item {
            Text(
                "这些锁只在监督时段内生效，时段结束自动放行。你不能在此直接解锁，" +
                    "需要向守门员助手申诉，由它调用监督管理工具撤销。",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnlockCard(
    sup: SupervisionSettings,
    active: Boolean,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onChange: (SupervisionSettings) -> Unit,
) {
    CardGroup(title = { Text("紧急解锁") }) {
        // 冷却时间设置（非监督期可改；监督期只许增大）
        item(
            headlineContent = { Text("冷却时间（分钟）") },
            supportingContent = {
                Text(
                    "守门员 AI 发起解锁请求后，需要等冷却结束、且你在下方手动确认才生效。",
                )
            },
            trailingContent = { Text("${sup.cooldownMinutes} 分") },
        )
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0, 5, 15, 30, 60).forEach { v ->
                    FilterChip(
                        selected = sup.cooldownMinutes == v,
                        onClick = { onChange(sup.copy(cooldownMinutes = v)) },
                        label = { Text(if (v == 0) "关闭" else "$v 分") },
                    )
                }
            }
        }

        if (active) {
            item {
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    val now = System.currentTimeMillis()
                    // 过期的已批准解锁不再展示（否则永远显示"本时段已解锁"）
                    val pending = sup.pendingUnlock?.takeUnless { sup.isUnlockStale(now) }
                    val grantorName = settings.assistants
                        .firstOrNull { it.id == sup.unlockGrantorAssistantId }
                        ?.name?.ifBlank { "(未命名)" }
                    when {
                        pending == null -> {
                            Text(
                                buildString {
                                    append("守门员：")
                                    append(grantorName ?: "未设置")
                                    append("。监督期内只有它能在对话中发起解锁请求。")
                                    if (grantorName == null) {
                                        append(" 设置守门员后此功能才会生效。")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        pending.status == PendingUnlock.Status.PENDING &&
                            now < pending.expiresAt -> {
                            val remainMin = ((pending.expiresAt - now) / 60_000).coerceAtLeast(0)
                            Text(
                                "⏳ 守门员已发起解锁请求：${pending.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "冷却中，约 $remainMin 分钟后可确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onChange(sup.copy(pendingUnlock = pending.copy(status = PendingUnlock.Status.CANCELLED)))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("拒绝 / 取消（保持锁定）") }
                        }
                        pending.status == PendingUnlock.Status.PENDING ||
                            pending.status == PendingUnlock.Status.READY -> {
                            Text(
                                "守门员申请解锁：${pending.reason}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    onChange(sup.copy(pendingUnlock = pending.copy(status = PendingUnlock.Status.APPROVED)))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("确认解锁（本时段内生效）") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onChange(sup.copy(pendingUnlock = pending.copy(status = PendingUnlock.Status.CANCELLED)))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("拒绝，保持锁定") }
                        }
                        pending.status == PendingUnlock.Status.APPROVED -> {
                            Text(
                                "✅ 本时段已解锁（守门员：${grantorName ?: "?"}）。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        else -> {
                            Text(
                                "本次解锁请求已取消/拒绝，保持锁定。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
