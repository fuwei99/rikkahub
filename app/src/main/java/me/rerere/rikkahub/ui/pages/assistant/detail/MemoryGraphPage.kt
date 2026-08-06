package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MemoryGraphData
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Edge
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Graph
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphVisualizer
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Node
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * 助手记忆图（独立页面，只加载 assistant scope）：
 * 从独立图谱仓库（MemoryGraphRepository）取数，绝不读取 legacy 记忆表。
 * 点节点看完整内容，点边看关系类型与描述。只读视图，编辑走记忆列表页/记忆工具。
 */
@Composable
fun AssistantMemoryGraphPage(id: String) {
    MemoryGraphScreen(
        scope = id,
        title = stringResource(R.string.memory_graph_title_assistant),
    )
}

/**
 * 全局记忆图（独立页面，只加载 global scope）：与助手记忆图完全分开。
 */
@Composable
fun GlobalMemoryGraphPage() {
    MemoryGraphScreen(
        scope = MemoryGraphRepository.GLOBAL_SCOPE,
        title = stringResource(R.string.memory_graph_title_global),
    )
}

@Composable
private fun MemoryGraphScreen(scope: String, title: String) {
    val graphRepo: MemoryGraphRepository = koinInject()
    var query by remember { mutableStateOf("") }
    // 非空检索词 → 检索结果 + 一跳邻居子图；空检索词 → 全库图（对齐 Operit getGraphForMemories）
    val graph by produceState<Graph?>(initialValue = null, scope, query) {
        value = withContext(Dispatchers.IO) {
            val data = if (query.isBlank()) {
                graphRepo.getGraph(scope)
            } else {
                val hits = runCatching { graphRepo.searchNodes(query, scope, topK = 15) }
                    .getOrDefault(emptyList())
                graphRepo.getGraphForNodes(scope, hits.map { it.node.id })
            }
            data.toVisualGraph(scope)
        }
    }
    var selectedNode by remember { mutableStateOf<Node?>(null) }
    var selectedEdge by remember { mutableStateOf<Edge?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.memory_graph_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
            )

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
                        onNodeClick = { selectedNode = it },
                        onEdgeClick = { selectedEdge = it },
                        onNodesSelected = {},
                    )
                }
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
                    text = stringResource(R.string.memory_graph_edge_title, edge.label ?: stringResource(R.string.memory_graph_edge_default_label)),
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

internal fun MemoryGraphData.toVisualGraph(scope: String): Graph {
    val isGlobal = scope == MemoryGraphRepository.GLOBAL_SCOPE
    val nodes = nodes.map { n ->
        val firstLine = n.title.ifBlank { n.content.lineSequence().firstOrNull()?.trim().orEmpty() }
        Node(
            id = n.id.toString(),
            label = firstLine.ifEmpty { "#${n.id}" }.let {
                if (it.length > 24) it.take(24) + "…" else it
            },
            color = if (isGlobal) Color(0xFFE8554F) else Color(0xFF4F8EF7),
            metadata = mapOf(
                "nodeId" to n.id.toString(),
                "title" to n.title,
                "content" to n.content,
            ),
        )
    }
    val edges = links.map { l ->
        Edge(
            id = l.id,
            sourceId = l.sourceId.toString(),
            targetId = l.targetId.toString(),
            label = l.type,
            weight = l.weight,
            metadata = mapOf(
                "linkId" to l.id.toString(),
                "type" to l.type,
                "description" to l.description,
            ),
        )
    }
    return Graph(nodes = nodes, edges = edges)
}
