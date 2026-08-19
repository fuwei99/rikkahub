package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.provider.Settings

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

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.FocusLockSettings
import me.rerere.rikkahub.data.model.FocusLockTask
import me.rerere.rikkahub.data.model.FocusLockTaskMode
import me.rerere.rikkahub.data.model.isActiveAt
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.util.Locale

private val FOCUS_WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable
fun SettingFocusLockPage(settingsStore: SettingsStore = koinInject()) {
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val focus = settings.focusLock
    val context = LocalContext.current
    var editorTask by remember { mutableStateOf<FocusLockTask?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var packageDraft by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            tick++
        }
    }
    val active = tick.let { focus.isActiveAt() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    fun update(next: FocusLockSettings) {
        scope.launch {
            settingsStore.update(settings.copy(focusLock = next))
        }
    }

    if (showEditor) {
        FocusLockTaskEditorDialog(
            initial = editorTask,
            onDismiss = { showEditor = false },
            onSave = { task ->
                val nextTasks = if (editorTask == null) focus.tasks + task
                else focus.tasks.map { if (it.id == task.id) task else it }
                update(focus.copy(tasks = nextTasks))
                showEditor = false
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("锁机设置") },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { editorTask = null; showEditor = true }) { Text("新建任务") }
                },
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
                            if (active) "🔒 物理锁机生效中" else "🔓 当前没有锁机任务生效",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "这是独立于 AI 专注监督的设备锁机规则。需要先在 Android 系统设置里启用 RikkaHub 无障碍服务。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("打开无障碍设置") }
                    }
                }
            }
            item {
                CardGroup(title = { Text("总开关") }) {
                    item(
                        headlineContent = { Text("启用物理锁机") },
                        supportingContent = { Text("命中下面任意一个任务时，前台打开未允许的应用会被退回桌面") },
                        trailingContent = {
                            Switch(
                                checked = focus.enabled,
                                onCheckedChange = { update(focus.copy(enabled = it)) },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text("锁机任务") }) {
                    if (focus.tasks.isEmpty()) {
                        item { Text("还没有任务，点击右上角「新建任务」创建一个番茄锁机任务。", Modifier.padding(16.dp)) }
                    }
                    focus.tasks.forEach { task ->
                        item {
                            ListItem(
                                headlineContent = { Text(task.name) },
                                supportingContent = {
                                    Text(taskSummary(task), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = task.enabled,
                                        onCheckedChange = { enabled ->
                                            update(focus.copy(tasks = focus.tasks.map { if (it.id == task.id) it.copy(enabled = enabled) else it }))
                                        },
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { editorTask = task; showEditor = true }) { Text("编辑") }
                                TextButton(onClick = { update(focus.copy(tasks = focus.tasks.filterNot { it.id == task.id })) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
            item {
                CardGroup(title = { Text("违规规则") }) {
                    item(
                        headlineContent = { Text("违规时退回桌面") },
                        supportingContent = { Text("无障碍服务捕获到非白名单应用后执行返回桌面") },
                        trailingContent = {
                            Switch(
                                checked = focus.returnHomeOnViolation,
                                onCheckedChange = { update(focus.copy(returnHomeOnViolation = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("允许桌面和系统界面") },
                        supportingContent = { Text("允许启动器、SystemUI 等基础系统包，不等于允许其他应用") },
                        trailingContent = {
                            Switch(
                                checked = focus.allowLauncherAndSystemUi,
                                onCheckedChange = { update(focus.copy(allowLauncherAndSystemUi = it)) },
                            )
                        },
                    )
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("额外允许的包名", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = packageDraft,
                                    onValueChange = { packageDraft = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("例如 com.android.chrome") },
                                )
                                Button(onClick = {
                                    val pkg = packageDraft.trim()
                                    if (pkg.isNotEmpty()) {
                                        update(focus.copy(additionalAllowedPackages = focus.additionalAllowedPackages + pkg))
                                        packageDraft = ""
                                    }
                                }) { Text("添加") }
                            }
                            focus.additionalAllowedPackages.sorted().forEach { pkg ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { update(focus.copy(additionalAllowedPackages = focus.additionalAllowedPackages - pkg)) }) { Text("移除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun taskSummary(task: FocusLockTask): String {
    val days = task.daysOfWeek.sorted().joinToString("") { FOCUS_WEEK_LABELS[it - 1] }
    val mode = if (task.mode == FocusLockTaskMode.POMODORO) {
        "番茄 ${task.workMinutes} 分钟 / 休息 ${task.breakMinutes} 分钟"
    } else "固定窗口"
    val cycles = if (task.cycles == 0) "循环至结束" else "${task.cycles} 轮"
    return "$days · ${formatMinutes(task.startMinute)}-${formatMinutes(task.endMinute)} · $mode · $cycles"
}

private fun formatMinutes(value: Int): String =
    "%02d:%02d".format(Locale.ROOT, value / 60, value % 60)

@Composable
private fun FocusLockTaskEditorDialog(
    initial: FocusLockTask?,
    onDismiss: () -> Unit,
    onSave: (FocusLockTask) -> Unit,
) {
    val base = initial ?: FocusLockTask()
    var name by remember(initial) { mutableStateOf(base.name) }
    var start by remember(initial) { mutableStateOf(formatMinutes(base.startMinute)) }
    var end by remember(initial) { mutableStateOf(formatMinutes(base.endMinute)) }
    var work by remember(initial) { mutableStateOf(base.workMinutes.toString()) }
    var rest by remember(initial) { mutableStateOf(base.breakMinutes.toString()) }
    var cycles by remember(initial) { mutableStateOf(base.cycles.toString()) }
    var mode by remember(initial) { mutableStateOf(base.mode) }
    var days by remember(initial) { mutableStateOf(base.daysOfWeek) }
    var lockDuringBreak by remember(initial) { mutableStateOf(base.lockDuringBreak) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建锁机任务" else "编辑锁机任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("任务名称") }, singleLine = true)
                Text("执行日", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FOCUS_WEEK_LABELS.forEachIndexed { index, label ->
                        FilterChip(
                            selected = index + 1 in days,
                            onClick = { days = if (index + 1 in days) days - (index + 1) else days + (index + 1) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("开始 HH:mm") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("结束 HH:mm") }, singleLine = true)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == FocusLockTaskMode.POMODORO,
                        onClick = { mode = FocusLockTaskMode.POMODORO },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("番茄") }
                    SegmentedButton(
                        selected = mode == FocusLockTaskMode.FIXED_WINDOW,
                        onClick = { mode = FocusLockTaskMode.FIXED_WINDOW },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("固定") }
                }
                if (mode == FocusLockTaskMode.POMODORO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberTextField(work, { work = it }, "工作分钟", Modifier.weight(1f))
                        NumberTextField(rest, { rest = it }, "休息分钟", Modifier.weight(1f))
                    }
                    NumberTextField(cycles, { cycles = it }, "循环轮数（0 = 到窗口结束）", Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("休息期间也保持锁机", Modifier.weight(1f))
                        Switch(checked = lockDuringBreak, onCheckedChange = { lockDuringBreak = it })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val startMinute = parseClock(start)
                val endMinute = parseClock(end)
                when {
                    name.isBlank() -> error = "任务名称不能为空"
                    days.isEmpty() -> error = "至少选择一天"
                    startMinute == null || endMinute == null -> error = "时间必须是 HH:mm，例如 08:30"
                    startMinute == endMinute -> error = "开始和结束时间不能相同"
                    work.toIntOrNull()?.let { it < 1 } == true -> error = "工作分钟必须大于 0"
                    rest.toIntOrNull()?.let { it < 0 } == true -> error = "休息分钟不能小于 0"
                    cycles.toIntOrNull()?.let { it < 0 } == true -> error = "循环轮数不能小于 0"
                    else -> onSave(
                        base.copy(
                            name = name.trim(),
                            daysOfWeek = days,
                            startMinute = startMinute!!,
                            endMinute = endMinute!!,
                            mode = mode,
                            workMinutes = work.toIntOrNull() ?: base.workMinutes,
                            breakMinutes = rest.toIntOrNull() ?: base.breakMinutes,
                            cycles = cycles.toIntOrNull() ?: 0,
                            lockDuringBreak = lockDuringBreak,
                        ),
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 4) onValueChange(it.filter(Char::isDigit)) },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun parseClock(raw: String): Int? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
