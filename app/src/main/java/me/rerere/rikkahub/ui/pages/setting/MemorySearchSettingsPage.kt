package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.MemorySearchSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel

/**
 * 记忆检索设置页（记忆图 Phase 2 收尾 UI，MemorySearchSettings 落地入口）：
 * - embedding 渠道选择（复用「向量模型服务」区块 settings.vectorProviders，OpenAI 兼容任意端点）；
 * - 渠道内 EMBEDDING 模型选择 + 输出维度（默认 1024，切渠道/维度自动换索引文件）；
 * - 检索行为开关：semanticSearch（语义路）/ graphExpansion（图传播路）/ fallbackToAllWhenEmpty（空结果全量兜底）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySearchSettingsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showProviderSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }

    val memorySearch = settings.memorySearch
    val vectorProviders = settings.vectorProviders.filter { it.enabled }
    val currentChannel = vectorProviders.firstOrNull { it.id == memorySearch.embeddingChannelId }
    val embeddingModels = currentChannel?.models?.filter { it.type == ModelType.EMBEDDING }.orEmpty()
    val currentModel = embeddingModels.firstOrNull { it.id == memorySearch.embeddingModelId }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_search_settings_title)) },
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 16.dp, 16.dp, 32.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_search_embedding_title)) }) {
                    item(
                        onClick = { showProviderSheet = true },
                        headlineContent = { Text(stringResource(R.string.memory_search_channel_label)) },
                        supportingContent = {
                            Text(
                                text = currentChannel?.let { c ->
                                    if (c is VectorProviderSetting.OpenAI) "${c.name}\n${c.baseUrl}" else c.name
                                } ?: stringResource(R.string.memory_search_channel_empty),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(16.dp)) },
                    )
                    item(
                        onClick = { if (embeddingModels.isNotEmpty()) showModelSheet = true },
                        headlineContent = { Text(stringResource(R.string.memory_search_model_label)) },
                        supportingContent = {
                            Text(
                                text = currentModel?.let { "${it.displayName} (${it.modelId})" }
                                    ?: stringResource(R.string.memory_search_model_empty),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(16.dp)) },
                    )
                    item {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.memory_search_dimension_label),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            OutlinedTextField(
                                value = memorySearch.embeddingDimension.toString(),
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(5)
                                    val value = (digits.toIntOrNull() ?: 64).coerceAtLeast(64)
                                    vm.updateSettings(
                                        settings.copy(
                                            memorySearch = memorySearch.copy(embeddingDimension = value)
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                singleLine = true,
                            )
                            Text(
                                text = stringResource(R.string.memory_search_dimension_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_search_behavior_title)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_search_keyword_enable)) },
                        supportingContent = { Text(stringResource(R.string.memory_search_keyword_desc)) },
                        trailingContent = {
                            Switch(
                                checked = memorySearch.keywordSearch,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(memorySearch = memorySearch.copy(keywordSearch = it))
                                    )
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_search_semantic_enable)) },
                        supportingContent = { Text(stringResource(R.string.memory_search_semantic_desc)) },
                        trailingContent = {
                            Switch(
                                checked = memorySearch.semanticSearch,
                                enabled = currentChannel != null && currentModel != null,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(memorySearch = memorySearch.copy(semanticSearch = it))
                                    )
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_search_graph_enable)) },
                        supportingContent = { Text(stringResource(R.string.memory_search_graph_desc)) },
                        trailingContent = {
                            Switch(
                                checked = memorySearch.graphExpansion,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(memorySearch = memorySearch.copy(graphExpansion = it))
                                    )
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.memory_search_fallback_enable)) },
                        supportingContent = { Text(stringResource(R.string.memory_search_fallback_desc)) },
                        trailingContent = {
                            Switch(
                                checked = memorySearch.fallbackToAllWhenEmpty,
                                onCheckedChange = {
                                    vm.updateSettings(
                                        settings.copy(memorySearch = memorySearch.copy(fallbackToAllWhenEmpty = it))
                                    )
                                }
                            )
                        },
                    )
                }
            }
            item {
                // 召回与注入参数：原先硬编码在 GenerationHandler，现全部可调。
                CardGroup(title = { Text(stringResource(R.string.memory_search_tuning_title)) }) {
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_search_topk_label),
                            desc = stringResource(R.string.memory_search_topk_desc),
                            value = memorySearch.topK,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(topK = it).sanitized())) },
                        )
                    }
                    item {
                        FloatTuningField(
                            label = stringResource(R.string.memory_search_keyword_weight_label),
                            desc = stringResource(R.string.memory_search_keyword_weight_desc),
                            value = memorySearch.keywordWeight,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(keywordWeight = it).sanitized())) },
                        )
                    }
                    item {
                        FloatTuningField(
                            label = stringResource(R.string.memory_search_semantic_weight_label),
                            desc = stringResource(R.string.memory_search_semantic_weight_desc),
                            value = memorySearch.semanticWeight,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(semanticWeight = it).sanitized())) },
                        )
                    }
                    item {
                        FloatTuningField(
                            label = stringResource(R.string.memory_search_min_score_label),
                            desc = stringResource(R.string.memory_search_min_score_desc),
                            value = memorySearch.minScore,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(minScore = it).sanitized())) },
                        )
                    }
                    item {
                        FloatTuningField(
                            label = stringResource(R.string.memory_search_gated_unlock_inject_label),
                            desc = stringResource(R.string.memory_search_gated_unlock_inject_desc),
                            value = memorySearch.gatedUnlockInjectThreshold,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(
                                        memorySearch = memorySearch.copy(gatedUnlockInjectThreshold = it).sanitized()
                                    )
                                )
                            },
                        )
                    }
                    item {
                        FloatTuningField(
                            label = stringResource(R.string.memory_search_gated_unlock_search_label),
                            desc = stringResource(R.string.memory_search_gated_unlock_search_desc),
                            value = memorySearch.gatedUnlockSearchThreshold,
                            onChange = {
                                vm.updateSettings(
                                    settings.copy(
                                        memorySearch = memorySearch.copy(gatedUnlockSearchThreshold = it).sanitized()
                                    )
                                )
                            },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_search_hops_label),
                            desc = stringResource(R.string.memory_search_hops_desc),
                            value = memorySearch.expansionHops,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(expansionHops = it).sanitized())) },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_search_max_nodes_label),
                            desc = stringResource(R.string.memory_search_max_nodes_desc),
                            value = memorySearch.maxInjectNodes,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(maxInjectNodes = it).sanitized())) },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_search_content_clip_label),
                            desc = stringResource(R.string.memory_search_content_clip_desc),
                            value = memorySearch.nodeContentMaxChars,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(nodeContentMaxChars = it).sanitized())) },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_search_query_clip_label),
                            desc = stringResource(R.string.memory_search_query_clip_desc),
                            value = memorySearch.queryMaxChars,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(queryMaxChars = it).sanitized())) },
                        )
                    }
                    item {
                        IntTuningField(
                            label = stringResource(R.string.memory_search_recent_turns_label),
                            desc = stringResource(R.string.memory_search_recent_turns_desc),
                            value = memorySearch.queryRecentTurns,
                            onChange = { vm.updateSettings(settings.copy(memorySearch = memorySearch.copy(queryRecentTurns = it).sanitized())) },
                        )
                    }
                    item {
                        TextButton(
                            onClick = {
                                vm.updateSettings(
                                    settings.copy(
                                        memorySearch = MemorySearchSettings(
                                            embeddingChannelId = memorySearch.embeddingChannelId,
                                            embeddingModelId = memorySearch.embeddingModelId,
                                            embeddingDimension = memorySearch.embeddingDimension,
                                            keywordSearch = memorySearch.keywordSearch,
                                            semanticSearch = memorySearch.semanticSearch,
                                            graphExpansion = memorySearch.graphExpansion,
                                            fallbackToAllWhenEmpty = memorySearch.fallbackToAllWhenEmpty,
                                        )
                                    )
                                )
                            },
                        ) {
                            Text(stringResource(R.string.setting_model_page_reset_to_default))
                        }
                    }
                }
            }
        }
    }

    if (showProviderSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showProviderSheet = false }, sheetState = sheetState) {
            Text(
                text = stringResource(R.string.memory_search_channel_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                vectorProviders.forEach { provider ->
                    item {
                        val selected = provider.id == memorySearch.embeddingChannelId
                        Column {
                            TextButton(
                                onClick = {
                                    vm.updateSettings(
                                        settings.copy(
                                            memorySearch = memorySearch.copy(
                                                embeddingChannelId = provider.id,
                                                embeddingModelId = null,
                                            )
                                        )
                                    )
                                    showProviderSheet = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (provider is VectorProviderSetting.OpenAI) {
                                        Text(
                                            provider.baseUrl,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (selected) {
                                    Text(
                                        text = stringResource(R.string.memory_search_selected),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }, sheetState = sheetState) {
            Text(
                text = stringResource(R.string.memory_search_model_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                embeddingModels.forEach { model ->
                    item {
                        val selected = model.id == memorySearch.embeddingModelId
                        Column {
                            TextButton(
                                onClick = {
                                    vm.updateSettings(
                                        settings.copy(
                                            memorySearch = memorySearch.copy(embeddingModelId = model.id)
                                        )
                                    )
                                    showModelSheet = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        model.modelId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) {
                                    Text(
                                        text = stringResource(R.string.memory_search_selected),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 整数参数输入：保留用户正在输入的中间态（如删除后为空串），
 * 不立即把 0/空 交给 sanitized() 回弹成最小值——否则「删掉 5 再输 2」
 * 会变成 0→1 回弹后拼出 12→coerce 到上限 5（旧实现的老 bug）。
 *
 * 中间态不回写：输入 12 被 sanitized() 收口成 5 时，输入框仍显示 12（不回弹），
 * 失焦时才同步为外部 value（sanitized 后的最终值）。外部 value 变化（恢复默认等）
 * 只在失焦/非编辑状态同步，不会打断正在进行的输入。
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
                // 空串/纯符号不提交，避免 sanitized 把 0 拉回最小值导致误输入
                filtered.toIntOrNull()?.let(onChange)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .onFocusChanged { state ->
                    // 失焦时把显示同步为外部收口后的合法值，编辑中不回写中间态
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

/** 浮点参数输入：保留用户正在输入的中间态（如 "1."），失焦前不强行改写。 */
@Composable
private fun FloatTuningField(label: String, desc: String, value: Float, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() || it == '.' }.take(6)
                text = filtered
                filtered.toFloatOrNull()?.let(onChange)
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
