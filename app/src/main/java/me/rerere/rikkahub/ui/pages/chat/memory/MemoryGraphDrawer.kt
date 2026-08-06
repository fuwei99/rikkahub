package me.rerere.rikkahub.ui.pages.chat.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Edge
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Graph
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphVisualizer
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Node
import me.rerere.rikkahub.ui.pages.assistant.detail.toVisualGraph
import org.koin.compose.koinInject

private enum class MemoryGraphTab { Assistant, Global }

/**
 * 对话内记忆图抽屉（右侧滑出，只读）。
 *
 * 展示范围来自 [trace]：传入的记忆节点会被高亮，
 * 图谱本身仍是完整的 scope 全图，方便看到触发节点在整张图里的位置。
 *
 * 助手图 / 全局图分选项卡，两个 scope 各自是一张独立的单 scope 图，节点 id 天然唯一。
 */
@Composable
fun MemoryGraphDrawer(
    visible: Boolean,
    assistantScope: String,
    showAssistantTab: Boolean,
    showGlobalTab: Boolean,
    trace: Map<String, Set<Long>>,
    conversationHasNoTrace: Boolean,
    onDismissRequest: () -> Unit,
) {
    // 两个 scope 参考开关都关时按钮仍在（enableMemoryGraph 为真），此时退化为只看助手图，
    // 避免点了按钮什么都不发生。
    val assistantTab = showAssistantTab || !showGlobalTab
    val globalTab = showGlobalTab

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(260)) { it },
            exit = slideOutHorizontally(tween(260)) { it },
        ) {
            BoxWithConstraints {
                val drawerWidth = minOf(maxWidth * 0.85f, 480.dp)
                Surface(
                    modifier = Modifier
                        .width(drawerWidth)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    MemoryGraphDrawerContent(
                        modifier = Modifier.systemBarsPadding(),
                        assistantScope = assistantScope,
                        showAssistantTab = assistantTab,
                        showGlobalTab = globalTab,
                        trace = trace,
                        conversationHasNoTrace = conversationHasNoTrace,
                        onDismissRequest = onDismissRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryGraphDrawerContent(
    assistantScope: String,
    showAssistantTab: Boolean,
    showGlobalTab: Boolean,
    trace: Map<String, Set<Long>>,
    conversationHasNoTrace: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bothTabs = showAssistantTab && showGlobalTab
    var tab by remember(showAssistantTab, showGlobalTab) {
        mutableStateOf(if (showAssistantTab) MemoryGraphTab.Assistant else MemoryGraphTab.Global)
    }
    val assistantTraceCount = trace[TRACE_SCOPE_ASSISTANT]?.size ?: 0
    val globalTraceCount = trace[TRACE_SCOPE_GLOBAL]?.size ?: 0

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.memory_graph_trace_drawer_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismissRequest) {
                Icon(
                    HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.cancel),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (bothTabs) {
            SecondaryTabRow(
                selectedTabIndex = if (tab == MemoryGraphTab.Assistant) 0 else 1,
                containerColor = Color.Transparent,
            ) {
                Tab(
                    selected = tab == MemoryGraphTab.Assistant,
                    onClick = { tab = MemoryGraphTab.Assistant },
                    text = {
                        Text(
                            text = stringResource(
                                R.string.memory_graph_trace_tab_assistant,
                                assistantTraceCount,
                            ),
                            maxLines = 1,
                        )
                    },
                )
                Tab(
                    selected = tab == MemoryGraphTab.Global,
                    onClick = { tab = MemoryGraphTab.Global },
                    text = {
                        Text(
                            text = stringResource(
                                R.string.memory_graph_trace_tab_global,
                                globalTraceCount,
                            ),
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        // 每个 tab 一个独立 GraphVisualizer 实例，切换时各自的缩放/平移由 remember 自然保留。
        when (tab) {
            MemoryGraphTab.Assistant -> MemoryGraphScopePane(
                scope = assistantScope,
                highlightedIds = trace[TRACE_SCOPE_ASSISTANT].orEmpty(),
                conversationHasNoTrace = conversationHasNoTrace,
            )

            MemoryGraphTab.Global -> MemoryGraphScopePane(
                scope = MemoryGraphRepository.GLOBAL_SCOPE,
                highlightedIds = trace[TRACE_SCOPE_GLOBAL].orEmpty(),
                conversationHasNoTrace = conversationHasNoTrace,
            )
        }
    }
}

@Composable
private fun MemoryGraphScopePane(
    scope: String,
    highlightedIds: Set<Long>,
    conversationHasNoTrace: Boolean,
) {
    val graphRepo: MemoryGraphRepository = koinInject()
    val graph by produceState<Graph?>(initialValue = null, scope) {
        value = withContext(Dispatchers.IO) {
            runCatching { graphRepo.getGraph(scope).toVisualGraph(scope) }.getOrNull()
                ?: Graph(nodes = emptyList(), edges = emptyList())
        }
    }
    var selectedNode by remember { mutableStateOf<Node?>(null) }
    var selectedEdge by remember { mutableStateOf<Edge?>(null) }
    val highlighted = remember(highlightedIds) { highlightedIds.map { it.toString() }.toSet() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (highlighted.isEmpty()) {
            Text(
                text = stringResource(
                    if (conversationHasNoTrace) {
                        R.string.memory_graph_trace_empty_conversation
                    } else {
                        R.string.memory_graph_trace_none_in_scope
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.memory_graph_trace_hint, highlighted.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val g = graph
            when {
                g == null -> Text(
                    text = stringResource(R.string.memory_graph_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                g.nodes.isEmpty() -> Text(
                    text = stringResource(R.string.memory_graph_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )

                else -> GraphVisualizer(
                    graph = g,
                    modifier = Modifier.fillMaxSize(),
                    highlightedNodeIds = highlighted,
                    onNodeClick = { selectedNode = it },
                    onEdgeClick = { selectedEdge = it },
                    onNodesSelected = {},
                )
            }
        }
    }

    selectedNode?.let { node ->
        AlertDialog(
            onDismissRequest = { selectedNode = null },
            title = {
                Text(
                    text = node.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    text = node.metadata["content"].orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedNode = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    selectedEdge?.let { edge ->
        AlertDialog(
            onDismissRequest = { selectedEdge = null },
            title = {
                Text(
                    text = stringResource(
                        R.string.memory_graph_edge_title,
                        edge.label ?: stringResource(R.string.memory_graph_edge_default_label),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    text = edge.metadata["description"].orEmpty().ifEmpty {
                        stringResource(R.string.memory_graph_edge_no_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedEdge = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }
}
