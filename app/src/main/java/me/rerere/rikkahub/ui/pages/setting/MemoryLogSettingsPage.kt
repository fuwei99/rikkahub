package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel

/**
 * 记忆日志设置（与「记忆检索设置」同级的独立配置块）。
 *
 * 记忆链路（记忆图语义检索、抽取器、自动提炼等）共用的文件调试日志：
 * - 总开关：排查完成可关闭，release 不再写盘；
 * - 清理策略不硬编码：超过 N 小时清除、超过 N 条滚动、轮转保留多份备份。
 * 配置写入 Settings.memoryLog，App 启动订阅后实时同步给 MemoryGraphDebugLog。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryLogSettingsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val memoryLog = settings.memoryLog

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_log_settings_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 32.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_log_group_title)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_log_enable)) },
                        supportingContent = { Text(stringResource(R.string.memory_log_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = memoryLog.enabled,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(memoryLog = memoryLog.copy(enabled = it)))
                                }
                            )
                        },
                    )
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_log_max_age_label),
                            desc = stringResource(R.string.memory_log_max_age_desc),
                            value = memoryLog.maxAgeHours,
                            onChange = {
                                vm.updateSettings(settings.copy(memoryLog = memoryLog.copy(maxAgeHours = it).sanitized()))
                            },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_log_max_lines_label),
                            desc = stringResource(R.string.memory_log_max_lines_desc),
                            value = memoryLog.maxLines,
                            onChange = {
                                vm.updateSettings(settings.copy(memoryLog = memoryLog.copy(maxLines = it).sanitized()))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 整数参数输入：保留用户正在输入的中间态（删除后为空串不回弹），
 * 空串不提交，输入完整数字才回调 onChange；失焦时同步为外部收口后的合法值。
 */
@Composable
private fun IntTuningField(label: String, desc: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() }.take(6)
                text = filtered
                filtered.toIntOrNull()?.let(onChange)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onFocusChanged { state ->
                    if (!state.isFocused) text = value.toString()
                },
            singleLine = true,
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
