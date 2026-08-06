package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import me.rerere.common.android.AiWireLog
import me.rerere.common.android.Logging
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel

/**
 * 日志设置（原「请求日志」入口升级而来）。
 *
 * 统一收口全部日志相关配置，避免散落在各处：
 * - **请求日志**：实际发给 LLM 的 header / payload / response 完整落盘
 *   （`<filesDir>/logs/ai_wire.log`），用于排查 payload 层面问题；
 * - **记忆图日志**：记忆链路专项调试日志（自记忆图设置页迁入）；
 * - **查看请求记录**：跳转原有的内存日志列表页；
 * - **清空日志**：一键清掉全部日志文件与内存记录。
 *
 * 日志文件均为设备本地，不随 D1 同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSettingsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current

    val requestLog = settings.requestLog
    val memoryLog = settings.memoryLog

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.log_settings_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 请求日志 ----
            item {
                CardGroup(title = { Text(stringResource(R.string.log_settings_request_group)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.log_settings_request_enable)) },
                        supportingContent = { Text(stringResource(R.string.log_settings_request_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = requestLog.enabled,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(requestLog = requestLog.copy(enabled = it)))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.log_settings_request_response_body)) },
                        supportingContent = { Text(stringResource(R.string.log_settings_request_response_body_desc)) },
                        trailingContent = {
                            Switch(
                                checked = requestLog.includeResponseBody,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(requestLog = requestLog.copy(includeResponseBody = it))
                                    )
                                }
                            )
                        },
                    )
                    item {
                        IntTuningField(
                            label = stringResource(R.string.log_settings_request_max_age_label),
                            desc = stringResource(R.string.log_settings_request_max_age_desc),
                            value = requestLog.maxAgeHours,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(requestLog = requestLog.copy(maxAgeHours = it).sanitized())
                                )
                            },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.log_settings_request_max_body_label),
                            desc = stringResource(R.string.log_settings_request_max_body_desc),
                            value = requestLog.maxBodyChars,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(requestLog = requestLog.copy(maxBodyChars = it).sanitized())
                                )
                            },
                        )
                    }
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        headlineContent = { Text(stringResource(R.string.log_settings_view_records)) },
                        supportingContent = { Text(stringResource(R.string.log_settings_view_records_desc)) },
                        trailingContent = { NavArrow() },
                    )
                }
            }

            // ---- 记忆图日志（自记忆图设置页迁入）----
            item {
                CardGroup(title = { Text(stringResource(R.string.log_settings_memory_group)) }) {
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
                                vm.updateSettings(
                                    settings.copy(memoryLog = memoryLog.copy(maxAgeHours = it).sanitized())
                                )
                            },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_log_max_lines_label),
                            desc = stringResource(R.string.memory_log_max_lines_desc),
                            value = memoryLog.maxLines,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(memoryLog = memoryLog.copy(maxLines = it).sanitized())
                                )
                            },
                        )
                    }
                }
            }

            // ---- 清空日志 ----
            item {
                val clearedMsg = stringResource(R.string.log_settings_cleared)
                Button(
                    onClick = {
                        AiWireLog.clear()
                        Logging.clear()
                        toaster.show(clearedMsg)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Delete01, null)
                    Text(
                        text = stringResource(R.string.log_settings_clear),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavArrow() {
    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
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
                val filtered = input.filter { it.isDigit() }.take(7)
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
