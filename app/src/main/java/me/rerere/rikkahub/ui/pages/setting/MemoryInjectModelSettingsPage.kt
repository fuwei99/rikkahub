package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_INJECT_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 注入模型设置页（方案 2026-08-06「LLM 注入选择器替代向量检索」）。
 *
 * 记忆图很小，没必要养一套 embedding + HNSW：把整份节点目录发给一个免费的轻量 LLM，
 * 让它直接回「本轮注入哪些 id」。本页配置这个选择器用的模型、思考强度、提示词与召回参数。
 * 开关关掉后链路完全回到旧的关键词 + 语义向量召回。
 */
@Composable
fun MemoryInjectModelSettingsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val inject = settings.memoryInject

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_inject_settings_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_inject_enable)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_inject_enable)) },
                        supportingContent = { Text(stringResource(R.string.memory_inject_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = inject.enabled,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(memoryInject = inject.copy(enabled = it).sanitized())
                                    )
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_inject_fallback)) },
                        supportingContent = { Text(stringResource(R.string.memory_inject_fallback_desc)) },
                        trailingContent = {
                            Switch(
                                checked = inject.fallbackToKeywordOnFailure,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(
                                            memoryInject = inject.copy(fallbackToKeywordOnFailure = it).sanitized()
                                        )
                                    )
                                },
                            )
                        },
                    )
                }
            }
            item {
                MemoryInjectModelItem(settings = settings, vm = vm)
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.assistant_page_thinking_budget)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_inject_thinking_desc)) },
                        trailingContent = {
                            ReasoningButton(
                                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.memoryInjectThinkingBudget),
                                onUpdateReasoningLevel = {
                                    vm.updateSettings(settings.copy(memoryInjectThinkingBudget = it.budgetTokens))
                                },
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_inject_tuning_title)) }) {
                    item {
                        MemoryInjectIntField(
                            label = stringResource(R.string.memory_inject_max_candidates_label),
                            desc = stringResource(R.string.memory_inject_max_candidates_desc),
                            value = inject.maxCandidateNodes,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(memoryInject = inject.copy(maxCandidateNodes = it).sanitized())
                                )
                            },
                        )
                    }
                    item {
                        MemoryInjectIntField(
                            label = stringResource(R.string.memory_inject_catalog_clip_label),
                            desc = stringResource(R.string.memory_inject_catalog_clip_desc),
                            value = inject.candidateContentMaxChars,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(
                                        memoryInject = inject.copy(candidateContentMaxChars = it).sanitized()
                                    )
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_inject_include_links)) },
                        supportingContent = { Text(stringResource(R.string.memory_inject_include_links_desc)) },
                        trailingContent = {
                            Switch(
                                checked = inject.includeLinks,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(memoryInject = inject.copy(includeLinks = it).sanitized())
                                    )
                                },
                            )
                        },
                    )
                    item {
                        MemoryInjectIntField(
                            label = stringResource(R.string.memory_inject_recent_turns_label),
                            desc = stringResource(R.string.memory_inject_recent_turns_desc),
                            value = inject.recentTurns,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(memoryInject = inject.copy(recentTurns = it).sanitized())
                                )
                            },
                        )
                    }
                    item {
                        MemoryInjectIntField(
                            label = stringResource(R.string.memory_inject_context_clip_label),
                            desc = stringResource(R.string.memory_inject_context_clip_desc),
                            value = inject.contextMaxChars,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(memoryInject = inject.copy(contextMaxChars = it).sanitized())
                                )
                            },
                        )
                    }
                    item {
                        MemoryInjectIntField(
                            label = stringResource(R.string.memory_inject_max_select_label),
                            desc = stringResource(R.string.memory_inject_max_select_desc),
                            value = inject.maxSelectNodes,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(memoryInject = inject.copy(maxSelectNodes = it).sanitized())
                                )
                            },
                        )
                    }
                    item {
                        MemoryInjectIntField(
                            label = stringResource(R.string.memory_inject_timeout_label),
                            desc = stringResource(R.string.memory_inject_timeout_desc),
                            value = inject.timeoutSeconds,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(memoryInject = inject.copy(timeoutSeconds = it).sanitized())
                                )
                            },
                        )
                    }
                }
            }
            item {
                MemoryInjectPromptItem(settings = settings, vm = vm)
            }
        }
    }
}

@Composable
private fun MemoryInjectModelItem(settings: Settings, vm: SettingVM) {
    val title = stringResource(R.string.memory_inject_model_title)
    val state = rememberModelListState(
        modelId = settings.memoryInjectModelId,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    Column {
        CardGroup(title = { Text(title) }) {
            item(
                onClick = { state.open() },
                headlineContent = { Text(title) },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = state.currentModel?.displayName
                                ?: stringResource(R.string.model_list_select_model),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
            )
        }
        Text(
            text = stringResource(R.string.memory_inject_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    ModelListSheet(state = state, onSelect = { vm.updateSettings(settings.copy(memoryInjectModelId = it.id)) })
}

@Composable
private fun MemoryInjectPromptItem(settings: Settings, vm: SettingVM) {
    var showEditor by remember { mutableStateOf(false) }

    CardGroup(title = { Text(stringResource(R.string.memory_inject_prompt_title)) }) {
        item(
            onClick = { showEditor = true },
            headlineContent = { Text(stringResource(R.string.memory_inject_prompt_title)) },
            trailingContent = {
                Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }

    if (showEditor) {
        ModalBottomSheet(onDismissRequest = { showEditor = false }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_inject_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.memory_inject_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.memoryInjectPrompt,
                    onValueChange = { vm.updateSettings(settings.copy(memoryInjectPrompt = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                )
                TextButton(
                    onClick = {
                        vm.updateSettings(settings.copy(memoryInjectPrompt = DEFAULT_MEMORY_INJECT_PROMPT))
                    },
                ) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}

/**
 * 整数输入：与记忆检索设置页同款「中间态不回弹」策略——
 * 编辑中不把 sanitized() 结果写回输入框，失焦时才同步，避免删字重输被收口值打断。
 */
@Composable
private fun MemoryInjectIntField(label: String, desc: String, value: Int, onChange: (Int) -> Unit) {
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
                .onFocusChanged { state -> if (!state.isFocused) text = value.toString() },
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
