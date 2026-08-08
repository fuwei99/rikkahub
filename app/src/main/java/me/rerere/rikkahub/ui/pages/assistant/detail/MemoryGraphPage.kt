package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MemoryGraphData
import me.rerere.rikkahub.data.model.MemoryGraphMatchEligibility
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Edge
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Graph
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphBatchDeleteDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphEdgeInfoDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphLinkEditDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphNodeEditDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphNodeInfoDialog
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.GraphVisualizer
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Node
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.util.Locale

/**
 * 助手记忆图（独立页面，只加载 assistant scope）：
 * 从独立图谱仓库（MemoryGraphRepository）取数，绝不读取 legacy 记忆表。
 * 支持节点/边的增删改：点节点看详情并可编辑/删除/建边，点边可改类型权重描述，
 * 框选模式可批量删除节点。
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

/**
 * 任意一张图（阶段二 §2.2）：路由只传 canonical id，标题页内从注册表取（review2 §二.F），
 * 避免本地化文案进导航参数导致返回栈里全是旧标题。
 */
@Composable
fun MemoryGraphPage(id: String) {
    val registry: MemoryGraphRegistry = koinInject()
    var graphName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        graphName = runCatching { registry.get(id)?.name }.getOrNull()
    }
    MemoryGraphScreen(
        scope = id,
        title = graphName ?: stringResource(R.string.memory_graph_title),
    )
}

/** 页面内的编辑弹窗状态。 */
private sealed interface GraphEditState {
    data object None : GraphEditState

    /** 节点详情（只读展示 + 动作入口） */
    data class NodeInfo(val node: Node) : GraphEditState

    /** 节点新建/编辑，node 为 null 表示新建 */
    data class NodeEdit(val node: MemoryGraphNode?) : GraphEditState

    /** 边详情 */
    data class EdgeInfo(val edge: Edge) : GraphEditState

    /** 改边 */
    data class EdgeEdit(val edge: Edge) : GraphEditState

    /** 建边：两个端点都已选好 */
    data class LinkCreate(val source: Node, val target: Node) : GraphEditState

    /** 批量删除确认 */
    data class BatchDelete(val ids: Set<String>) : GraphEditState
}

@Composable
internal fun MemoryGraphScreen(scope: String, title: String) {
    val graphRepo: MemoryGraphRepository = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    // 每次写操作后自增，触发 produceState 重新拉图。
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    // 非空检索词 → 检索结果 + 一跳邻居子图；空检索词 → 全库图（对齐 Operit getGraphForMemories）
    val graph by produceState<Graph?>(initialValue = null, scope, query, refreshKey) {
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

    var editState by remember { mutableStateOf<GraphEditState>(GraphEditState.None) }
    // 连线模式：依次点两个节点建边
    var linkingMode by remember { mutableStateOf(false) }
    var linkingNodeIds by remember { mutableStateOf<List<String>>(emptyList()) }
    // 框选模式：批量选节点后删除
    var boxSelectionMode by remember { mutableStateOf(false) }
    var boxSelectedNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 统一的写操作包装：跑 IO、失败弹 snackbar、成功刷新图。
    fun mutate(block: suspend () -> Unit) {
        coroutineScope.launch {
            busy = true
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            busy = false
            result.onFailure { e ->
                snackbarHostState.showSnackbar(e.message ?: "operation failed")
            }.onSuccess {
                refreshKey += 1
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 框选模式下才出现批量删除
                if (boxSelectionMode && boxSelectedNodeIds.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            editState = GraphEditState.BatchDelete(boxSelectedNodeIds)
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            HugeIcons.Delete02,
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                }
                FloatingActionButton(
                    onClick = {
                        boxSelectionMode = !boxSelectionMode
                        boxSelectedNodeIds = emptySet()
                        if (boxSelectionMode) {
                            linkingMode = false
                            linkingNodeIds = emptyList()
                        }
                    },
                    containerColor = if (boxSelectionMode) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        HugeIcons.TextSelection,
                        contentDescription = stringResource(R.string.memory_graph_box_select_mode),
                    )
                }
                FloatingActionButton(
                    onClick = {
                        linkingMode = !linkingMode
                        linkingNodeIds = emptyList()
                        if (linkingMode) {
                            boxSelectionMode = false
                            boxSelectedNodeIds = emptySet()
                        }
                    },
                    containerColor = if (linkingMode) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        HugeIcons.Link01,
                        contentDescription = stringResource(R.string.memory_graph_link_mode),
                    )
                }
                FloatingActionButton(
                    onClick = { editState = GraphEditState.NodeEdit(null) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        HugeIcons.Add01,
                        contentDescription = stringResource(R.string.memory_graph_node_create),
                    )
                }
            }
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

            // 模式提示条：让用户知道当前点击行为是什么
            val modeHint = when {
                linkingMode -> stringResource(
                    R.string.memory_graph_link_mode_hint,
                    linkingNodeIds.size,
                )

                boxSelectionMode -> stringResource(
                    R.string.memory_graph_box_select_hint,
                    boxSelectedNodeIds.size,
                )

                else -> null
            }
            if (modeHint != null) {
                Text(
                    text = modeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
                        boxSelectedNodeIds = boxSelectedNodeIds,
                        isBoxSelectionMode = boxSelectionMode,
                        linkingNodeIds = linkingNodeIds,
                        selectedEdgeId = (editState as? GraphEditState.EdgeInfo)?.edge?.id,
                        // 设置页视图：常驻池蓝色描边，门控锁池不描边（半透明弱化）
                        candidateNodeIds = g.nodes.filter {
                            it.metadata["match_eligibility"] != "gated"
                        }.map { it.id }.toSet(),
                        dimmedNodeIds = g.nodes.filter {
                            it.metadata["match_eligibility"] == "gated"
                        }.map { it.id }.toSet(),
                        onNodeClick = { node ->
                            when {
                                linkingMode -> {
                                    // 连线模式：攒够两个端点就弹建边框；重复点同一个节点忽略
                                    val ids = linkingNodeIds
                                    if (node.id !in ids) {
                                        val next = ids + node.id
                                        linkingNodeIds = next
                                        if (next.size >= 2) {
                                            val source = g.nodes.find { it.id == next[0] }
                                            val target = g.nodes.find { it.id == next[1] }
                                            if (source != null && target != null) {
                                                editState =
                                                    GraphEditState.LinkCreate(source, target)
                                            }
                                        }
                                    }
                                }

                                boxSelectionMode -> {
                                    boxSelectedNodeIds = if (node.id in boxSelectedNodeIds) {
                                        boxSelectedNodeIds - node.id
                                    } else {
                                        boxSelectedNodeIds + node.id
                                    }
                                }

                                else -> editState = GraphEditState.NodeInfo(node)
                            }
                        },
                        onEdgeClick = { edge ->
                            if (!linkingMode && !boxSelectionMode) {
                                editState = GraphEditState.EdgeInfo(edge)
                            }
                        },
                        onNodesSelected = { ids ->
                            boxSelectedNodeIds = boxSelectedNodeIds + ids
                        },
                    )
                }

                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    when (val state = editState) {
        GraphEditState.None -> Unit

        is GraphEditState.NodeInfo -> GraphNodeInfoDialog(
            node = state.node,
            onDismiss = { editState = GraphEditState.None },
            onEdit = {
                val nodeId = state.node.id.toLongOrNull()
                editState = GraphEditState.None
                if (nodeId != null) {
                    coroutineScope.launch {
                        val model = withContext(Dispatchers.IO) {
                            runCatching { graphRepo.getNode(nodeId) }.getOrNull()
                        }
                        if (model != null) {
                            editState = GraphEditState.NodeEdit(model)
                        } else {
                            snackbarHostState.showSnackbar("node #$nodeId not found")
                        }
                    }
                }
            },
            onDelete = {
                val nodeId = state.node.id.toLongOrNull()
                editState = GraphEditState.None
                if (nodeId != null) mutate { graphRepo.deleteNode(scope, nodeId) }
            },
            onStartLink = {
                // 从该节点开始连线：直接进连线模式并把它设为起点
                linkingMode = true
                boxSelectionMode = false
                boxSelectedNodeIds = emptySet()
                linkingNodeIds = listOf(state.node.id)
                editState = GraphEditState.None
            },
        )

        is GraphEditState.NodeEdit -> GraphNodeEditDialog(
            node = state.node,
            onDismiss = { editState = GraphEditState.None },
            onSave = { title2, content, importance, matchEligibility, folderPath ->
                val existing = state.node
                editState = GraphEditState.None
                mutate {
                    if (existing == null) {
                        graphRepo.createNode(
                            scope = scope,
                            title = title2,
                            content = content,
                            importance = importance,
                            matchEligibility = matchEligibility,
                            folderPath = folderPath.ifBlank { null },
                        )
                    } else {
                        graphRepo.updateNode(
                            scope = scope,
                            id = existing.id,
                            title = title2,
                            content = content,
                            importance = importance,
                            matchEligibility = matchEligibility,
                            folderPath = folderPath.ifBlank { null },
                        )
                    }
                }
            },
        )

        is GraphEditState.EdgeInfo -> {
            // 图还没加载完就不弹（不在组合期改状态，避免重组循环）
            val g = graph
            if (g != null) {
                GraphEdgeInfoDialog(
                    edge = state.edge,
                    graph = g,
                    onDismiss = { editState = GraphEditState.None },
                    onEdit = { editState = GraphEditState.EdgeEdit(state.edge) },
                    onDelete = {
                        val edgeId = state.edge.id
                        editState = GraphEditState.None
                        mutate { graphRepo.deleteLink(scope, edgeId) }
                    },
                )
            }
        }

        is GraphEditState.EdgeEdit -> GraphLinkEditDialog(
            initialType = state.edge.metadata["type"] ?: state.edge.label ?: "related",
            initialWeight = state.edge.weight,
            initialDescription = state.edge.metadata["description"].orEmpty(),
            onDismiss = { editState = GraphEditState.None },
            onSave = { type, weight, description ->
                val edgeId = state.edge.id
                editState = GraphEditState.None
                mutate {
                    graphRepo.updateLink(
                        scope = scope,
                        id = edgeId,
                        type = type,
                        weight = weight,
                        description = description,
                    )
                }
            },
        )

        is GraphEditState.LinkCreate -> GraphLinkEditDialog(
            initialType = "related",
            initialWeight = 0.7f,
            initialDescription = "",
            sourceLabel = state.source.label,
            targetLabel = state.target.label,
            onDismiss = {
                editState = GraphEditState.None
                linkingNodeIds = emptyList()
            },
            onSave = { type, weight, description ->
                val sourceId = state.source.id.toLongOrNull()
                val targetId = state.target.id.toLongOrNull()
                editState = GraphEditState.None
                linkingNodeIds = emptyList()
                linkingMode = false
                if (sourceId != null && targetId != null) {
                    mutate {
                        graphRepo.linkNodes(
                            scope = scope,
                            sourceId = sourceId,
                            targetId = targetId,
                            type = type,
                            weight = weight,
                            description = description,
                        )
                    }
                }
            },
        )

        is GraphEditState.BatchDelete -> GraphBatchDeleteDialog(
            count = state.ids.size,
            onDismiss = { editState = GraphEditState.None },
            onConfirm = {
                val ids = state.ids.mapNotNull { it.toLongOrNull() }
                editState = GraphEditState.None
                boxSelectedNodeIds = emptySet()
                boxSelectionMode = false
                mutate { graphRepo.deleteNodes(scope, ids) }
            },
        )
    }
}

internal fun MemoryGraphData.toVisualGraph(scope: String): Graph {
    val isGlobal = scope == MemoryGraphRepository.GLOBAL_SCOPE
    val nodes = nodes.map { n ->
        val firstLine = n.title.ifBlank { n.content.lineSequence().firstOrNull()?.trim().orEmpty() }
        val gated = n.matchEligibility == MemoryGraphMatchEligibility.GATED
        Node(
            id = n.id.toString(),
            label = (if (gated) "🔒 " else "") + firstLine.ifEmpty { "#${n.id}" }.let {
                if (it.length > 24) it.take(24) + "…" else it
            },
            // gated 节点用暖色区分：常驻池蓝/红，锁池橙
            color = when {
                gated -> Color(0xFFE8A33D)
                isGlobal -> Color(0xFFE8554F)
                else -> Color(0xFF4F8EF7)
            },
            metadata = mapOf(
                "nodeId" to n.id.toString(),
                "title" to n.title,
                "content" to n.content,
                "importance" to String.format(Locale.US, "%.2f", n.importance),
                "match_eligibility" to MemoryGraphMatchEligibility.wire(n.matchEligibility).orEmpty(),
                "folderPath" to n.folderPath.orEmpty(),
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
