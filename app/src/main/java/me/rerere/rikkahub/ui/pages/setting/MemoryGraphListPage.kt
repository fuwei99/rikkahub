package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Sorting01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.MemoryGraphCreator
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 记忆图管理页（阶段二 §2.2）：
 * 列表（emoji/名称/描述/节点数/内置/AI 建/自动提炼落点单选）+ 新建 + 改名改描述/排序/删除，
 * 顶部「清理 AI 创建的空图」，点条目进对应图的可视化编辑页（Screen.MemoryGraph）。
 */
@Composable
fun MemoryGraphListPage() {
    val registry: MemoryGraphRegistry = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val graphs by registry.listFlow().collectAsState(initial = emptyList())
    var nodeCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(graphs) {
        nodeCounts = runCatching { registry.nodeCounts() }.getOrDefault(emptyMap())
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingGraph by remember { mutableStateOf<MemoryGraphMeta?>(null) }
    var sortingGraph by remember { mutableStateOf<MemoryGraphMeta?>(null) }
    var pendingDelete by remember { mutableStateOf<MemoryGraphMeta?>(null) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var menuForId by remember { mutableStateOf<String?>(null) }

    val createdToastText = stringResource(R.string.memory_graph_list_created)
    val deletedToastText = stringResource(R.string.memory_graph_list_deleted)

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun toastError(e: Throwable) {
        toaster.show(e.message ?: "operation failed", type = ToastType.Error)
    }

    fun cleanupEmptyAiGraphs() {
        scope.launch {
            runCatching {
                val counts = registry.nodeCounts()
                val empty = registry.list().filter { it.createdBy == MemoryGraphCreator.AI && (counts[it.id] ?: 0) == 0 }
                empty.forEach { runCatching { registry.delete(it.id) } }
                empty.size
            }.onSuccess { removed ->
                toaster.show(
                    if (removed > 0) {
                        context.getString(R.string.memory_graph_list_cleanup_done, removed)
                    } else {
                        context.getString(R.string.memory_graph_list_cleanup_none)
                    },
                    type = ToastType.Success,
                )
            }.onFailure(::toastError)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_graph_manage_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showCleanupConfirm = true }) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.memory_graph_list_cleanup_ai),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.memory_graph_list_new),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (graphs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.memory_graph_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(graphs, key = { it.id }) { graph ->
                    val nodeCount = nodeCounts[graph.id] ?: 0
                    val badges = buildList {
                        if (graph.builtin) add(stringResource(R.string.memory_graph_list_builtin))
                        if (graph.createdBy == MemoryGraphCreator.AI) add(stringResource(R.string.memory_graph_list_ai))
                    }
                    ListItem(
                        onClick = { navController.navigate(Screen.MemoryGraph(graph.id)) },
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "${graph.emoji?.let { "$it " }.orEmpty()}${graph.name.ifBlank { graph.wireId }}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                badges.forEach { badge ->
                                    Text(
                                        text = badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Column {
                                if (graph.description.isNotBlank()) {
                                    Text(
                                        text = graph.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.memory_graph_node_count, nodeCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = graph.autoExtractTarget,
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                registry.update(
                                                    id = graph.id,
                                                    autoExtractTarget = !graph.autoExtractTarget,
                                                )
                                            }.onFailure(::toastError)
                                        }
                                    },
                                )
                                Box {
                                    IconButton(onClick = { menuForId = graph.id }) {
                                        Icon(
                                            imageVector = HugeIcons.MoreVertical,
                                            contentDescription = stringResource(R.string.memory_graph_list_more),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuForId == graph.id,
                                        onDismissRequest = { menuForId = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.memory_graph_list_rename)) },
                                            leadingIcon = { Icon(HugeIcons.Edit01, null) },
                                            onClick = {
                                                menuForId = null
                                                editingGraph = graph
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.memory_graph_list_sort_order)) },
                                            leadingIcon = { Icon(HugeIcons.Sorting01, null) },
                                            onClick = {
                                                menuForId = null
                                                sortingGraph = graph
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.memory_graph_list_delete)) },
                                            leadingIcon = { Icon(HugeIcons.Delete01, null) },
                                            enabled = !graph.builtin,
                                            onClick = {
                                                menuForId = null
                                                pendingDelete = graph
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        GraphEditDialog(
            title = stringResource(R.string.memory_graph_list_new),
            confirmText = stringResource(R.string.memory_graph_list_new),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description, emoji ->
                showCreateDialog = false
                scope.launch {
                    runCatching { registry.create(name, description, emoji) }
                        .onSuccess { toaster.show(createdToastText, type = ToastType.Success) }
                        .onFailure(::toastError)
                }
            },
        )
    }

    editingGraph?.let { graph ->
        GraphEditDialog(
            title = stringResource(R.string.memory_graph_list_rename),
            confirmText = stringResource(R.string.common_confirm),
            initialName = graph.name,
            initialDescription = graph.description,
            initialEmoji = graph.emoji.orEmpty(),
            onDismiss = { editingGraph = null },
            onConfirm = { name, description, emoji ->
                editingGraph = null
                scope.launch {
                    runCatching {
                        registry.update(
                            id = graph.id,
                            name = name,
                            description = description,
                            emoji = emoji.ifBlank { null },
                        )
                    }.onFailure(::toastError)
                }
            },
        )
    }

    sortingGraph?.let { graph ->
        SortOrderDialog(
            initial = graph.sortOrder,
            onDismiss = { sortingGraph = null },
            onConfirm = { order ->
                sortingGraph = null
                scope.launch {
                    runCatching { registry.update(id = graph.id, sortOrder = order) }
                        .onFailure(::toastError)
                }
            },
        )
    }

    pendingDelete?.let { graph ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.memory_graph_list_delete_confirm_title),
            confirmText = stringResource(R.string.confirm_delete),
            dismissText = stringResource(R.string.cancel),
            text = {
                Text(
                    text = stringResource(R.string.memory_graph_list_delete_confirm, graph.name.ifBlank { graph.wireId }),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            onConfirm = {
                pendingDelete = null
                scope.launch {
                    runCatching { registry.delete(graph.id) }
                        .onSuccess { toaster.show(deletedToastText, type = ToastType.Success) }
                        .onFailure(::toastError)
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showCleanupConfirm) {
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.memory_graph_list_cleanup_ai),
            confirmText = stringResource(R.string.confirm),
            dismissText = stringResource(R.string.cancel),
            text = {
                Text(
                    text = stringResource(R.string.memory_graph_list_cleanup_ai_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            onConfirm = {
                showCleanupConfirm = false
                cleanupEmptyAiGraphs()
            },
            onDismiss = { showCleanupConfirm = false },
        )
    }
}

/** 新建 / 改名改描述共用：name + description + emoji 三个输入框。 */
@Composable
private fun GraphEditDialog(
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, emoji: String) -> Unit,
    initialName: String = "",
    initialDescription: String = "",
    initialEmoji: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var emoji by remember { mutableStateOf(initialEmoji) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.memory_graph_list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.memory_graph_list_description)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it },
                    label = { Text(stringResource(R.string.memory_graph_list_emoji)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || description.isBlank()) return@TextButton
                    onConfirm(name.trim(), description.trim(), emoji.trim())
                },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** 排序输入：数字越大越先吃注入额度（sortOrder 降序）。 */
@Composable
private fun SortOrderDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var order by remember { mutableStateOf(initial.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_graph_list_sort_order)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = order,
                    onValueChange = { value -> order = value.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.memory_graph_list_sort_order_hint)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.memory_graph_list_sort_order_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = order.toIntOrNull() ?: 0
                    onConfirm(value)
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
