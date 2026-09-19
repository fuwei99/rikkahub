package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.screentime.QuickSyncScheduler
import me.rerere.rikkahub.data.screentime.ScreenTimeCollectWorker
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 快速同步设置页（偏好设置子页，2026-09-19）。
 *
 * 设备本地的高频小数据同步通道，**当前只支持屏幕时间**。
 * 与「云同步」彻底分开：不走 D1、不共享开关、不共享端点与密钥。
 *
 * 页面只负责读写 [SyncAdvancedConfig] 里 `quickSync*` 那组字段；
 * 调度语义由 [QuickSyncScheduler] 解释，本页只做实时预览。
 */
@Composable
fun SettingQuickSyncPage() {
    val store: SyncAdvancedConfigStore = koinInject()
    val cfg by store.configFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var status by remember { mutableStateOf("") }

    fun update(fn: (SyncAdvancedConfig) -> SyncAdvancedConfig) {
        scope.launch { store.update(fn) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("快速同步") },
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
                Text(
                    "高频小数据跨设备同步。当前只支持屏幕时间：走独立 Worker + R2，" +
                        "不占用 D1 写入额度，也不受云同步的配额熔断影响。\n" +
                        "端点与密钥只存在本机，代码里不预置任何默认值。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                CardGroup(title = { Text("启用与端点") }) {
                    item(
                        headlineContent = { Text("启用快速同步") },
                        supportingContent = {
                            Text(
                                "关掉后本机照常采集屏幕时间（本地数据不受影响），只是不再推拉。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = cfg.quickSyncEnabled,
                                onCheckedChange = { v -> update { it.copy(quickSyncEnabled = v) } },
                            )
                        },
                    )
                    commitTextFieldItem(
                        value = cfg.quickSyncUrl,
                        label = "Worker 地址",
                        supporting = "例如 https://screentime.example.com。留空即关闭。",
                        onCommit = { v -> update { it.copy(quickSyncUrl = v) } },
                    )
                    commitTextFieldItem(
                        value = cfg.quickSyncSecret,
                        label = "访问密钥",
                        supporting = "Worker 侧的 Bearer token，需与其 ST_SECRET 一致。留空即关闭。",
                        isSecret = true,
                        onCommit = { v -> update { it.copy(quickSyncSecret = v) } },
                    )
                }
            }

            item {
                CardGroup(title = { Text("调度") }) {
                    item(
                        headlineContent = { Text("调度模式") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = cfg.quickSyncScheduleMode == SyncAdvancedConfig.QUICK_SYNC_MODE_WINDOW,
                                        onClick = {
                                            update {
                                                it.copy(quickSyncScheduleMode = SyncAdvancedConfig.QUICK_SYNC_MODE_WINDOW)
                                            }
                                        },
                                        label = { Text("时间段内定时") },
                                    )
                                    FilterChip(
                                        selected = cfg.quickSyncScheduleMode == SyncAdvancedConfig.QUICK_SYNC_MODE_FIXED,
                                        onClick = {
                                            update {
                                                it.copy(quickSyncScheduleMode = SyncAdvancedConfig.QUICK_SYNC_MODE_FIXED)
                                            }
                                        },
                                        label = { Text("每天固定时刻") },
                                    )
                                }
                                Text(
                                    if (cfg.quickSyncScheduleMode == SyncAdvancedConfig.QUICK_SYNC_MODE_FIXED) {
                                        "按下面的时刻表跑，其余时间不动。"
                                    } else {
                                        "在起止时间内，从起点开始每隔 N 分钟跑一次；窗口外不联网。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )

                    if (cfg.quickSyncScheduleMode == SyncAdvancedConfig.QUICK_SYNC_MODE_WINDOW) {
                        commitTextFieldItem(
                            value = cfg.quickSyncWindowStart,
                            label = "开始时间",
                            supporting = "24 小时制 HH:mm。想避开整十分（比如落在 :09）就直接写 08:09",
                            onCommit = { v -> update { it.copy(quickSyncWindowStart = v) } },
                        )
                        commitTextFieldItem(
                            value = cfg.quickSyncWindowEnd,
                            label = "结束时间",
                            supporting = "24 小时制 HH:mm，须晚于开始时间",
                            onCommit = { v -> update { it.copy(quickSyncWindowEnd = v) } },
                        )
                        sliderItem(
                            title = "间隔",
                            desc = "窗口内每隔这么久跑一次",
                            value = cfg.quickSyncIntervalMinutes,
                            range = 1f..120f,
                            suffix = " 分钟",
                            onChange = { v -> update { it.copy(quickSyncIntervalMinutes = v) } },
                        )
                    } else {
                        commitTextFieldItem(
                            value = cfg.quickSyncFixedTimes,
                            label = "每天时刻表",
                            supporting = "逗号分隔的 HH:mm，例：09:00,12:00,18:00,22:00",
                            onCommit = { v -> update { it.copy(quickSyncFixedTimes = v) } },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text("接下来几次") }) {
                    item(
                        headlineContent = {
                            val preview = remember(
                                cfg.quickSyncScheduleMode,
                                cfg.quickSyncWindowStart,
                                cfg.quickSyncWindowEnd,
                                cfg.quickSyncIntervalMinutes,
                                cfg.quickSyncFixedTimes,
                            ) {
                                QuickSyncScheduler.preview(
                                    now = LocalDateTime.now(),
                                    mode = cfg.quickSyncScheduleMode,
                                    windowStart = cfg.quickSyncWindowStart,
                                    windowEnd = cfg.quickSyncWindowEnd,
                                    intervalMinutes = cfg.quickSyncIntervalMinutes,
                                    fixedTimes = cfg.quickSyncFixedTimes,
                                    count = 5,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (preview.isEmpty()) {
                                    Text(
                                        "当前配置排不出计划，请检查时间格式。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else {
                                    preview.forEach { t ->
                                        Text(t.format(PREVIEW_FORMAT))
                                    }
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(title = { Text("数据范围") }) {
                    sliderItem(
                        title = "推送回溯",
                        desc = "历史日聚合结算后就冻结了，推多了纯属浪费上行",
                        value = cfg.quickSyncPushLookbackDays,
                        range = 1f..30f,
                        suffix = " 天",
                        onChange = { v -> update { it.copy(quickSyncPushLookbackDays = v) } },
                    )
                    sliderItem(
                        title = "拉取回溯",
                        desc = "只需保证对端「此刻」是最新的，历史数据本地已有",
                        value = cfg.quickSyncPullLookbackDays,
                        range = 1f..30f,
                        suffix = " 天",
                        onChange = { v -> update { it.copy(quickSyncPullLookbackDays = v) } },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ScreenTimeCollectWorker.reschedule(context, cfg)
                                status = "已按当前配置重新排期"
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("应用并重排") }
                        OutlinedButton(
                            onClick = {
                                ScreenTimeCollectWorker.runNow(context)
                                status = "已触发一轮同步，稍后看日志"
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("立即同步一次") }
                    }
                    if (status.isNotBlank()) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "「应用并重排」会按上面的配置算下一次触发时刻。调度是自续链，" +
                            "改完不点它的话，要等这一轮跑完才会用上新配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val PREVIEW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 失焦即提交的文本框。
 *
 * 每敲一个字就落盘会把配置文件写烂，所以用本地 draft 承接输入，
 * 焦点离开时才 `onCommit`。
 */
@Composable
private fun CardGroupScope.commitTextFieldItem(
    value: String,
    label: String,
    supporting: String,
    isSecret: Boolean = false,
    onCommit: (String) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    var hadFocus by remember { mutableStateOf(false) }

    item(
        headlineContent = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(label) },
                singleLine = true,
                visualTransformation =
                    if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (hadFocus && !state.isFocused) onCommit(draft)
                        hadFocus = state.isFocused
                    },
            )
        },
        supportingContent = {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** 数字滑块条目，与网络层设置页同款式样 */
@Composable
private fun CardGroupScope.sliderItem(
    title: String,
    desc: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    item(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$value$suffix")
            }
        },
        supportingContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onChange(it.toInt()) },
                    valueRange = range,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
