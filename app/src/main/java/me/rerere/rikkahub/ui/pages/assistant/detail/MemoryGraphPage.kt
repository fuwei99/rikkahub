package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Edge
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Graph
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphVisualizer
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Node
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private enum class GraphTab {
    Assistant,
    Global,
}

/**
 * 记忆图谱（P4 可视化，提前实现）：
 * 力导向布局展示 scope 内记忆节点与链接边；「当前助手 / 全局」分开两个视图。
 * 点节点看完整内容，点边看关系类型与描述。只读视图，编辑仍走记忆列表页。
 */
@Composable
fun MemoryGraphPage(id: String) {
    val memoryRepo: MemoryRepository = koinInject()
    var tab by remember { mutableStateOf(GraphTab.Assistant) }
    val scopeId = when (tab) {
        GraphTab.Assistant -> id
        GraphTab.Global -> MemoryRepository.GLOBAL_MEMORY_ID
    }
    val graph by produceState<Graph?>(initialValue = null, scopeId) {
        value = memoryRepo.getMemoryGraph(scopeId)
    }
    var selectedNode by remember { mutableStateOf<Node?>(null) }
    var selectedEdge by remember { mutableStateOf<Edge?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_graph_title)) },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = tab == GraphTab.Assistant,
                    onClick = { tab = GraphTab.Assistant },
                    label = { Text(stringResource(R.string.memory_graph_tab_assistant)) },
                )
                FilterChip(
                    selected = tab == GraphTab.Global,
                    onClick = { tab = GraphTab.Global },
                    label = { Text(stringResource(R.string.memory_graph_tab_global)) },
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
                    text = stringResource(R.string.memory_graph_edge_title, edge.label ?: "related"),
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
