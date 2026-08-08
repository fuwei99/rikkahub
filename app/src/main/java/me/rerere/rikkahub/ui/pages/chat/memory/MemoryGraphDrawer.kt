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
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Edge
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Graph
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphVisualizer
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Node
import me.rerere.rikkahub.ui.pages.assistant.detail.toVisualGraph
import org.koin.compose.koinInject

/**
 * 对话内记忆图抽屉（右侧滑出，只读）。
 *
 * 展示范围来自 [trace]：传入的记忆节点会被高亮，
 * 图谱本身仍是完整的 scope 全图，方便看到触发节点在整张图里的位置。
 *
 * 多图体系（阶段二 §2.3）：Tab 由本轮启用的图列表（resolver 输出）动态生成，
 * 每个 tab 对应一张单 scope 图；老会话的 trace key（assistant/global 别名）
 * 会按 id 映射到对应内置图。
 */
@Composable
fun MemoryGraphDrawer(
    visible: Boolean,
    graphs: List<MemoryGraphMeta>,
    trace: Map<String, Set<Long>>,
    conversationHasNoTrace: Boolean,
    onDismissRequest: () -> Unit,
) {
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
                        graphs = graphs,
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
    graphs: List<MemoryGraphMeta>,
    trace: Map<String, Set<Long>>,
    conversationHasNoTrace: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tabIndex by remember(graphs) { mutableIntStateOf(0) }
    // 兜底：图列表变化导致当前索引越界时收敛到最后一个
    val safeTabIndex = tabIndex.coerceIn(0, (graphs.size - 1).coerceAtLeast(0))

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

        if (graphs.size > 1) {
            SecondaryScrollableTabRow(
                selectedTabIndex = safeTabIndex,
                containerColor = Color.Transparent,
            ) {
                graphs.forEachIndexed { index, graph ->
                    Tab(
                        selected = safeTabIndex == index,
                        onClick = { tabIndex = index },
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.memory_graph_trace_tab_graph,
                                    graph.name.ifBlank { graph.wireId },
                                    traceForGraph(graph, trace).size,
                                ),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }

        if (graphs.isEmpty()) {
            Text(
                text = stringResource(R.string.memory_graph_trace_no_graphs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val graph = graphs[safeTabIndex]
            // 每个 tab 一个独立 GraphVisualizer 实例，切换时各自的缩放/平移由 remember 自然保留。
            MemoryGraphScopePane(
                scope = graph.id,
                highlightedIds = traceForGraph(graph, trace),
                conversationHasNoTrace = conversationHasNoTrace,
            )
        }
    }
}

/**
 * 该图在本轮的命中节点：优先按 wireId（注入块 `<graph id>` 用的就是它）取，
 * 老会话注入块 key 是 assistant/global 别名，按内置图 id 兜底映射。
 */
private fun traceForGraph(graph: MemoryGraphMeta, trace: Map<String, Set<Long>>): Set<Long> {
    trace[graph.wireId]?.let { if (it.isNotEmpty()) return it }
    return when (graph.id) {
        MemoryGraphRepository.GLOBAL_SCOPE -> trace[TRACE_SCOPE_GLOBAL].orEmpty()
        else -> trace[TRACE_SCOPE_ASSISTANT].orEmpty()
    }
}

@Composable
internal fun MemoryGraphScopePane(
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
                    // 聊天框溯源：命中（注入）绿色描边；常驻池未命中=待选池蓝色描边；
                    // gated 锁池=不在待选池，不描边且半透明
                    candidateNodeIds = g.nodes.filter {
                        it.metadata["match_eligibility"] != "gated" && it.id !in highlighted
                    }.map { it.id }.toSet(),
                    dimmedNodeIds = g.nodes.filter {
                        it.metadata["match_eligibility"] == "gated"
                    }.map { it.id }.toSet(),
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
