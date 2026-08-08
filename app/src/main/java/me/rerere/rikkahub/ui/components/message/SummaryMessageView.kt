package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.components.ai.CompressContextDialog
import kotlin.uuid.Uuid

/**
 * 总结消息视图（方案 2026-08-08 §6.3）：
 *
 * ```text
 * [被遮住的历史 —— 由 ChatList 在 loaded=true 时渲染到本组件上方]
 * ─────────────── [加载历史消息] ───────────────   ← 分界线（居中按钮 + 统计）
 * [📄 总结卡片：标题 + 正文（可编辑）]
 * ```
 *
 * - 分界线上方居中「加载历史消息」，点击后由上层把覆盖区原始消息渲染进来（可上滑浏览）；
 * - 分界线下方是总结卡片：标题、正文（默认折叠可展开）、编辑 / 重新生成 / 删除；
 * - 同一分界点多条总结（历史版本）显示版本切换。
 */
@Composable
fun SummaryMessageView(
    summaryNode: MessageNode,
    templates: List<CompressTemplate>,
    defaultTemplateId: Uuid?,
    loaded: Boolean,
    onToggleLoaded: () -> Unit,
    onEditSummary: (message: UIMessage, newTitle: String, newContent: String) -> Unit,
    onRegenerate: (boundaryMessageId: Uuid, templateId: Uuid, prompt: String, targetTokens: Int) -> Job,
    onDelete: (message: UIMessage) -> Unit,
    onSelectVersion: (nodeId: Uuid, index: Int) -> Unit,
) {
    val summary = summaryNode.currentMessage
    val meta = summary.summaryMeta ?: return
    var expanded by remember(summary.id) { mutableStateOf(false) }
    var showEditDialog by remember(summary.id) { mutableStateOf(false) }
    var showCompressDialog by remember(summary.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 分界线：居中「加载历史消息」按钮 + 统计
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleLoaded) {
                Text(
                    text = stringResource(
                        if (loaded) R.string.summary_collapse_history
                        else R.string.summary_load_history
                    ),
                )
            }
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        Text(
            text = stringResource(
                R.string.summary_stats,
                meta.summarizedCount,
                meta.summarizedTokens ?: 0L,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // 总结卡片（分界线下方）
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 标题行 + 版本切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "📄 ${meta.title.ifBlank { stringResource(R.string.summary_default_title) }}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (summaryNode.messages.size > 1) {
                        val current = summaryNode.selectIndex + 1
                        val total = summaryNode.messages.size
                        TextButton(
                            onClick = {
                                onSelectVersion(
                                    summaryNode.id,
                                    (summaryNode.selectIndex - 1).coerceAtLeast(0),
                                )
                            },
                            enabled = summaryNode.selectIndex > 0,
                        ) {
                            Text("‹")
                        }
                        Text(
                            text = stringResource(R.string.summary_version, current, total),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                onSelectVersion(
                                    summaryNode.id,
                                    (summaryNode.selectIndex + 1).coerceAtMost(summaryNode.messages.lastIndex),
                                )
                            },
                            enabled = summaryNode.selectIndex < summaryNode.messages.lastIndex,
                        ) {
                            Text("›")
                        }
                    }
                }

                // 正文：默认折叠，点击展开
                Surface(
                    onClick = { expanded = !expanded },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (expanded) summary.toText() else summary.toText().lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                // 操作行：重新生成 / 编辑 / 删除
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    TextButton(onClick = { showCompressDialog = true }) {
                        Text(stringResource(R.string.summary_regenerate))
                    }
                    TextButton(onClick = { showEditDialog = true }) {
                        Text(stringResource(R.string.edit))
                    }
                    TextButton(onClick = { onDelete(summary) }) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        SummaryEditDialog(
            initialTitle = meta.title,
            initialContent = summary.toText(),
            onDismiss = { showEditDialog = false },
            onConfirm = { newTitle, newContent ->
                showEditDialog = false
                onEditSummary(summary, newTitle, newContent)
            },
        )
    }

    if (showCompressDialog) {
        CompressContextDialog(
            templates = templates,
            defaultTemplateId = defaultTemplateId,
            boundaryHint = stringResource(R.string.summary_regenerate_hint),
            onDismiss = { showCompressDialog = false },
            onConfirm = { templateId, prompt, tokens ->
                showCompressDialog = false
                onRegenerate(meta.boundaryMessageId, templateId, prompt, tokens)
            },
        )
    }
}

@Composable
private fun SummaryEditDialog(
    initialTitle: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var content by remember { mutableStateOf(initialContent) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.summary_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.summary_edit_label_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.summary_edit_label_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, content) }) {
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
