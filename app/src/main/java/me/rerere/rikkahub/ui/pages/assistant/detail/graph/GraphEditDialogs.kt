package me.rerere.rikkahub.ui.pages.assistant.detail.graph

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import java.util.Locale

/**
 * 记忆图节点详情弹窗：只读展示 + 编辑/删除入口（对齐 Operit 的 MemoryInfoDialog）。
 *
 * 之前这里只有一个「确认」按钮，导致图谱完全只读；现在补上编辑与删除动作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphNodeInfoDialog(
    node: Node,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStartLink: () -> Unit,
) {
    val scrollState = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = node.metadata["title"]?.ifBlank { node.label } ?: node.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = node.metadata["content"].orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val importance = node.metadata["importance"]
                val credibility = node.metadata["credibility"]
                if (importance != null || credibility != null) {
                    HorizontalDivider()
                    if (importance != null) {
                        Text(
                            text = stringResource(R.string.memory_graph_node_importance) + ": $importance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (credibility != null) {
                        Text(
                            text = stringResource(R.string.memory_graph_node_credibility) + ": $credibility",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onStartLink) {
                    Text(stringResource(R.string.memory_graph_link_from_here))
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
            }
        },
    )
}

/** 节点新建/编辑弹窗。node 为 null 时是新建。 */
@Composable
fun GraphNodeEditDialog(
    node: MemoryGraphNode?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, importance: Float, credibility: Float, folderPath: String) -> Unit,
) {
    var title by remember { mutableStateOf(node?.title.orEmpty()) }
    var content by remember { mutableStateOf(node?.content.orEmpty()) }
    var folderPath by remember { mutableStateOf(node?.folderPath.orEmpty()) }
    var importance by remember { mutableFloatStateOf(node?.importance ?: 0.5f) }
    var credibility by remember { mutableFloatStateOf(node?.credibility ?: 0.5f) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                stringResource(
                    if (node == null) R.string.memory_graph_node_create
                    else R.string.memory_graph_node_edit
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.memory_graph_node_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.memory_graph_node_content)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 180.dp),
                )
                OutlinedTextField(
                    value = folderPath,
                    onValueChange = { folderPath = it },
                    label = { Text(stringResource(R.string.memory_graph_node_folder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.memory_graph_node_importance) +
                        ": " + String.format(Locale.US, "%.2f", importance),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(value = importance, onValueChange = { importance = it }, valueRange = 0f..1f)
                Text(
                    text = stringResource(R.string.memory_graph_node_credibility) +
                        ": " + String.format(Locale.US, "%.2f", credibility),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(value = credibility, onValueChange = { credibility = it }, valueRange = 0f..1f)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, content, importance, credibility, folderPath) },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.memory_graph_save)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 边详情弹窗：展示两端节点 + 编辑/删除入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphEdgeInfoDialog(
    edge: Edge,
    graph: Graph,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sourceLabel = graph.nodes.find { it.id == edge.sourceId }?.label ?: edge.sourceId
    val targetLabel = graph.nodes.find { it.id == edge.targetId }?.label ?: edge.targetId
    AlertDialog(
        onDismissRequest = onDismiss,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$sourceLabel  →  $targetLabel", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.memory_graph_link_weight) +
                        ": " + String.format(Locale.US, "%.2f", edge.weight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = edge.metadata["description"].orEmpty().ifEmpty {
                        stringResource(R.string.memory_graph_edge_no_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
            }
        },
    )
}

/**
 * 建边 / 改边共用弹窗。
 * [sourceLabel]/[targetLabel] 非空时是新建（展示两端标题），否则是编辑已有边。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphLinkEditDialog(
    initialType: String,
    initialWeight: Float,
    initialDescription: String,
    sourceLabel: String? = null,
    targetLabel: String? = null,
    onDismiss: () -> Unit,
    onSave: (type: String, weight: Float, description: String) -> Unit,
) {
    var type by remember { mutableStateOf(initialType) }
    var weight by remember { mutableFloatStateOf(initialWeight) }
    var description by remember { mutableStateOf(initialDescription) }
    var expanded by remember { mutableStateOf(false) }
    val isCreate = sourceLabel != null && targetLabel != null

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                stringResource(
                    if (isCreate) R.string.memory_graph_link_create
                    else R.string.memory_graph_link_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isCreate) {
                    Text(
                        text = "$sourceLabel  →  $targetLabel",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HorizontalDivider()
                }
                // 关系类型：给出 Repository 里的推荐枚举，同时允许自由输入。
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text(stringResource(R.string.memory_graph_link_type)) },
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        MemoryGraphRepository.LINK_TYPES.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate) },
                                onClick = {
                                    type = candidate
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.memory_graph_link_weight) +
                        ": " + String.format(Locale.US, "%.2f", weight),
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(value = weight, onValueChange = { weight = it }, valueRange = 0f..1f)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.memory_graph_link_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(type.trim().ifEmpty { "related" }, weight, description) },
            ) { Text(stringResource(R.string.memory_graph_save)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 批量删除确认。 */
@Composable
fun GraphBatchDeleteDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_delete)) },
        text = { Text(stringResource(R.string.memory_graph_delete_nodes_confirm, count)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.confirm_delete)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
