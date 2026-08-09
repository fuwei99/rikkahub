package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.prompts.AutoCompressSetting
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_TEMPLATES
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 助手压缩设置页（方案 2026-08-08 §5.1）：
 * 默认压缩模板绑定 + 自动压缩总开关 + token 限制 + 条数限制。
 */
@Composable
fun AssistantCompressPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val templates = settings.compressTemplates.ifEmpty { DEFAULT_COMPRESS_TEMPLATES }
    val auto = assistant.autoCompress

    fun updateAuto(block: (AutoCompressSetting) -> AutoCompressSetting) {
        vm.update(assistant.copy(autoCompress = block(auto)))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("压缩设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 默认模板绑定
            item {
                var expanded by remember { mutableStateOf(false) }
                val bound = templates.firstOrNull { it.id == assistant.defaultCompressTemplateId }
                ListItem(
                    headlineContent = { Text("默认压缩模板") },
                    supportingContent = {
                        Column {
                            Text(
                                bound?.let { "${it.name} · ${it.scene}" } ?: "跟随全局默认模板",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Box {
                                OutlinedButton(onClick = { expanded = true }) {
                                    Text("更换")
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("跟随全局默认") },
                                        onClick = {
                                            expanded = false
                                            vm.update(assistant.copy(defaultCompressTemplateId = null))
                                        },
                                    )
                                    templates.forEach { t ->
                                        DropdownMenuItem(
                                            text = { Text("${t.name} · ${t.scene}") },
                                            onClick = {
                                                expanded = false
                                                vm.update(assistant.copy(defaultCompressTemplateId = t.id))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                )
            }

            // 自动压缩总开关
            item {
                ListItem(
                    headlineContent = { Text("自动压缩") },
                    supportingContent = {
                        Text(
                            if (auto.enabled) {
                                "已开：达到下面任一阈值就自动插入总结（原始消息保留，删总结可恢复）"
                            } else {
                                "已关：只能手动压缩。开启后会自动消耗压缩模型的 token"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = auto.enabled,
                            onCheckedChange = { checked -> updateAuto { it.copy(enabled = checked) } },
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                )
            }

            item {
                AnimatedVisibility(visible = auto.enabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "两类限制可单独开也可同时开；同时开时满足任意一个即触发，保留量取更保守的那个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // token 限制
                        ListItem(
                            headlineContent = { Text("按 token 触发") },
                            trailingContent = {
                                Switch(
                                    checked = auto.tokenLimitEnabled,
                                    onCheckedChange = { c -> updateAuto { it.copy(tokenLimitEnabled = c) } },
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                        )
                        AnimatedVisibility(visible = auto.tokenLimitEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedNumberInput(
                                    value = auto.tokenThreshold,
                                    onValueChange = { v -> updateAuto { it.copy(tokenThreshold = v) } },
                                    label = "触发阈值（token）",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedNumberInput(
                                    value = auto.tokenKeep,
                                    onValueChange = { v -> updateAuto { it.copy(tokenKeep = v) } },
                                    label = "压缩后保留（token）",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        // 条数限制
                        ListItem(
                            headlineContent = { Text("按消息条数触发") },
                            trailingContent = {
                                Switch(
                                    checked = auto.countLimitEnabled,
                                    onCheckedChange = { c -> updateAuto { it.copy(countLimitEnabled = c) } },
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                        )
                        AnimatedVisibility(visible = auto.countLimitEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedNumberInput(
                                    value = auto.countThreshold,
                                    onValueChange = { v -> updateAuto { it.copy(countThreshold = v) } },
                                    label = "触发阈值（条）",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedNumberInput(
                                    value = auto.countKeep,
                                    onValueChange = { v -> updateAuto { it.copy(countKeep = v) } },
                                    label = "压缩后保留（条）",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        // 自动压缩专用模板
                        var autoTplExpanded by remember { mutableStateOf(false) }
                        val autoTpl = templates.firstOrNull { it.id == auto.templateId }
                        ListItem(
                            headlineContent = { Text("自动压缩使用的模板") },
                            supportingContent = {
                                Column {
                                    Text(
                                        autoTpl?.let { "${it.name} · ${it.scene}" }
                                            ?: "跟随本助手默认模板",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Box {
                                        OutlinedButton(onClick = { autoTplExpanded = true }) {
                                            Text("更换")
                                        }
                                        DropdownMenu(
                                            expanded = autoTplExpanded,
                                            onDismissRequest = { autoTplExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("跟随助手默认模板") },
                                                onClick = {
                                                    autoTplExpanded = false
                                                    updateAuto { it.copy(templateId = null) }
                                                },
                                            )
                                            templates.forEach { t ->
                                                DropdownMenuItem(
                                                    text = { Text("${t.name} · ${t.scene}") },
                                                    onClick = {
                                                        autoTplExpanded = false
                                                        updateAuto { it.copy(templateId = t.id) }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                        )
                    }
                }
            }
        }
    }
}
