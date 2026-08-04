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
                                text = currentChannel?.let { "${it.name}\n${it.baseUrl}" }
                                    ?: stringResource(R.string.memory_search_channel_empty),
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
                                text = currentModel?.let { "${it.name} (${it.id})" }
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
                                    Text(
                                        provider.baseUrl,
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
                                    Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        model.id,
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
