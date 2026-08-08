package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.CommunicationSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 通信设置（多 Agent 通信内核期三/期四，2026-08-08）。
 *
 * 攒批窗口 / 抢占冷却 / 上限等数字全部在这里可配，**避免硬编码**
 * （用户拍板「搞个设置好放这些数字」，收敛设计 §5.3 护栏）。
 */
@Composable
fun SettingCommunicationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val comm = settings.communication
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(fn: (CommunicationSettings) -> CommunicationSettings) {
        vm.updateSettings(settings.copy(communication = fn(comm)))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("通信设置") },
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
                    "多 Agent 之间的通信节奏参数：攒批合并窗口、电话并线窗口、抢占冷却与上限。改完即时生效，随设置同步到各端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                CardGroup(title = { Text("邮件攒批") }) {
                    sliderItem(
                        title = "合并窗口（秒）",
                        desc = "await/join 等第一个结果到达后，窗口内到的其他结果合并一起批返回；0 = 关闭合并，首封即返回",
                        value = comm.mailBatchWindowSeconds,
                        range = 0f..30f,
                        suffix = " 秒",
                        onChange = { v -> update { it.copy(mailBatchWindowSeconds = v) } },
                    )
                    sliderItem(
                        title = "默认等待超时（秒）",
                        desc = "await 工具没给 timeout_seconds 时的等待上限",
                        value = comm.defaultAwaitTimeoutSeconds,
                        range = 10f..600f,
                        suffix = " 秒",
                        onChange = { v -> update { it.copy(defaultAwaitTimeoutSeconds = v) } },
                    )
                    sliderItem(
                        title = "未读上限（封）",
                        desc = "单对话未读达到上限后，新信合并进最后一封，防单个 agent 疯狂回报撑爆收件箱",
                        value = comm.maxUnreadPerTarget,
                        range = 5f..100f,
                        suffix = " 封",
                        onChange = { v -> update { it.copy(maxUnreadPerTarget = v) } },
                    )
                }
            }
            item {
                CardGroup(title = { Text("电话（抢占）") }) {
                    sliderItem(
                        title = "并线窗口（秒）",
                        desc = "同一目标一次抢占的合并窗口：窗口内到达的其他 CALL 合并进同一轮（会议电话），不降级不排队",
                        value = comm.callMergeWindowSeconds,
                        range = 0f..30f,
                        suffix = " 秒",
                        onChange = { v -> update { it.copy(callMergeWindowSeconds = v) } },
                    )
                    sliderItem(
                        title = "抢占冷却（秒）",
                        desc = "同一目标两次抢占的最小间隔，防 A↔B 互掐乒乓",
                        value = comm.preemptCooldownSeconds,
                        range = 0f..600f,
                        suffix = " 秒",
                        onChange = { v -> update { it.copy(preemptCooldownSeconds = v) } },
                    )
                    sliderItem(
                        title = "单轮抢占上限（次）",
                        desc = "每轮生成内最多被抢占几次，防某个 agent 反复掐目标",
                        value = comm.maxPreemptsPerRound,
                        range = 1f..10f,
                        suffix = " 次",
                        onChange = { v -> update { it.copy(maxPreemptsPerRound = v) } },
                    )
                }
            }
        }
    }
}

/** 数字滑块条目（秒/次数，Slider + 当前值 + 说明），风格对齐偏好设置页 */
private fun LazyListScope.sliderItem(
    title: String,
    desc: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    item {
        ListItem(
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$value$suffix")
                }
            },
            supportingContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { onChange(it.toInt()) },
                        valueRange = range,
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
        )
        Text(
            desc,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
