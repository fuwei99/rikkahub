package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class MemoryScope {
    Assistant,
    Global,
}

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val assistantMemories by vm.assistantMemories.collectAsStateWithLifecycle()
    val globalMemories by vm.globalMemories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            assistantMemories = assistantMemories,
            globalMemories = globalMemories,
            onUpdateAssistant = { vm.update(it) },
            onDeleteAssistantMemory = { vm.deleteAssistantMemory(it) },
            onAddAssistantMemory = { vm.addAssistantMemory(it) },
            onUpdateAssistantMemory = { vm.updateAssistantMemory(it) },
            onDeleteGlobalMemory = { vm.deleteGlobalMemory(it) },
            onAddGlobalMemory = { vm.addGlobalMemory(it) },
            onUpdateGlobalMemory = { vm.updateGlobalMemory(it) },
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    assistantMemories: List<AssistantMemory>,
    globalMemories: List<AssistantMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddAssistantMemory: (AssistantMemory) -> Unit,
    onUpdateAssistantMemory: (AssistantMemory) -> Unit,
    onDeleteAssistantMemory: (AssistantMemory) -> Unit,
    onAddGlobalMemory: (AssistantMemory) -> Unit,
    onUpdateGlobalMemory: (AssistantMemory) -> Unit,
    onDeleteGlobalMemory: (AssistantMemory) -> Unit,
) {
    var editingScope by remember { mutableStateOf(MemoryScope.Assistant) }
    val navController = LocalNavController.current
    val memoryDialogState = useEditState<AssistantMemory> { memory ->
        when (editingScope) {
            MemoryScope.Assistant -> {
                if (memory.id == 0) onAddAssistantMemory(memory) else onUpdateAssistantMemory(memory)
            }
            MemoryScope.Global -> {
                if (memory.id == 0) onAddGlobalMemory(memory) else onUpdateGlobalMemory(memory)
            }
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<Pair<MemoryScope, AssistantMemory>?>(null) }

    fun openMemory(scope: MemoryScope, memory: AssistantMemory) {
        editingScope = scope
        memoryDialogState.open(memory)
    }

    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(
                    when (editingScope) {
                        MemoryScope.Assistant -> "助手记忆"
                        MemoryScope.Global -> "全局记忆"
                    }
                )
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 2,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = { memoryDialogState.confirm() }) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDialogState.dismiss() }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_memory_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(enableMemory = it)
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_global_memory_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.useGlobalMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(useGlobalMemory = it)
                            )
                        },
                        enabled = assistant.enableMemory
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_recent_chats_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(enableRecentChatsReference = it)
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_time_reminder_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(enableTimeReminder = it)
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_conversation_id)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_conversation_id_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableConversationIdInjection,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(enableConversationIdInjection = it)
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.memory_graph_enable)) },
                supportingContent = {
                    Text(text = stringResource(R.string.memory_graph_enable_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemoryGraph,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(enableMemoryGraph = it)
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.memory_auto_extract_enable)) },
                supportingContent = {
                    Text(text = stringResource(R.string.memory_auto_extract_enable_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableMemoryAutoExtract,
                        enabled = assistant.enableMemory,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(enableMemoryAutoExtract = it)
                            )
                        }
                    )
                }
            )
            item(
                onClick = {
                    navController.navigate(Screen.AssistantMemoryGraph(assistant.id.toString()))
                },
                headlineContent = { Text(stringResource(R.string.memory_graph_open)) },
                supportingContent = {
                    Text(text = stringResource(R.string.memory_graph_open_desc))
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                    )
                }
            )
            item(
                onClick = {
                    navController.navigate(Screen.GlobalMemoryGraph)
                },
                headlineContent = { Text(stringResource(R.string.memory_graph_open_global)) },
                supportingContent = {
                    Text(text = stringResource(R.string.memory_graph_open_global_desc))
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                    )
                }
            )
            // 记忆图的全局配置（检索引擎/召回参数/抽取模型/提示词）统一在「记忆图设置」，
            // 这里补一个直达入口，避免只能从「默认模型和提示词」深处进入。
            item(
                onClick = {
                    navController.navigate(Screen.MemoryGraphSettings)
                },
                headlineContent = { Text(stringResource(R.string.memory_graph_settings_title)) },
                supportingContent = {
                    Text(text = stringResource(R.string.memory_graph_settings_desc))
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                    )
                }
            )
        }

        MemorySectionCard(
            title = "助手记忆",
            description = "只属于当前助手，不会跨助手共享。",
            memories = assistantMemories,
            onAddMemory = { openMemory(MemoryScope.Assistant, AssistantMemory(0, "")) },
            onEditMemory = { openMemory(MemoryScope.Assistant, it) },
            onDeleteMemory = { pendingDeleteMemory = MemoryScope.Assistant to it },
        )

        MemorySectionCard(
            title = "全局记忆",
            description = "跨助手共享，所有开启全局记忆的助手都可以参考或编辑。",
            memories = globalMemories,
            onAddMemory = { openMemory(MemoryScope.Global, AssistantMemory(0, "")) },
            onEditMemory = { openMemory(MemoryScope.Global, it) },
            onDeleteMemory = { pendingDeleteMemory = MemoryScope.Global to it },
        )
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let { (scope, memory) ->
                when (scope) {
                    MemoryScope.Assistant -> onDeleteAssistantMemory(memory)
                    MemoryScope.Global -> onDeleteGlobalMemory(memory)
                }
            }
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.second?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun MemorySectionCard(
    title: String,
    description: String,
    memories: List<AssistantMemory>,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAddMemory) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }

            if (memories.isEmpty()) {
                Text(
                    text = "暂无记忆",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            memories.fastForEach { memory ->
                key(memory.id) {
                    MemoryItem(
                        memory = memory,
                        onEditMemory = onEditMemory,
                        onDeleteMemory = onDeleteMemory,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "#${memory.id}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { onEditMemory(memory) }) {
                Icon(HugeIcons.PencilEdit01, null)
            }
            IconButton(onClick = { onDeleteMemory(memory) }) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
