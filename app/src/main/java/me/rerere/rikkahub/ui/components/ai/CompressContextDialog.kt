package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import kotlin.uuid.Uuid

/**
 * 手动压缩的「保留方式」（2026-08-30）：与自动压缩的双线设计对齐。
 *
 * 此前手动压缩只有 [Count] 一条路，而 token 才是真正想控的东西 ——
 * 「保留最近 5 条」在 agent 会话里毫无意义：5 条可能是 500 token，也可能是 300k。
 */
enum class CompressKeepMode { Count, Token }

/**
 * 压缩/插入总结对话框（方案 2026-08-08 §6.2 重构）：
 * 模板选择（含模型/思考强度）+ 目标字数 + 保留量（条数/token 二选一）+ 附加提示词；
 * 分界点由调用方在 [boundaryHint] 说明。
 * 语义已从「清空上下文」变为「插入总结，原始消息保留可恢复」。
 *
 * [keepRecentDefault] 非 null 时显示保留量设置（整段压缩入口用）：
 * - 按条数：分界点 = 倒数第 N+1 条；
 * - 按 token：从最新消息往回累加到额度为止，切点可落在一条胖消息**内部**
 *   （复用自动压缩那套 part 级游标，2026-08-28 重构）。
 * 从消息长按菜单进来时分界点已由那条消息定死，传 null 隐藏该项。
 */
@Composable
fun CompressContextDialog(
    templates: List<CompressTemplate>,
    defaultTemplateId: Uuid?,
    boundaryHint: String,
    keepRecentDefault: Int? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        templateId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecent: Int,
        keepMode: CompressKeepMode,
    ) -> Job,
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var selectedTokensOption by remember { mutableIntStateOf(2000) }
    var customTokens by remember { mutableIntStateOf(10000) }
    var keepRecent by remember { mutableIntStateOf(keepRecentDefault ?: 0) }
    var keepTokens by remember { mutableIntStateOf(20000) }
    var keepMode by remember { mutableStateOf(CompressKeepMode.Count) }
    var keepModeMenuExpanded by remember { mutableStateOf(false) }
    var templateMenuExpanded by remember { mutableStateOf(false) }
    var selectedTemplateId by remember { mutableStateOf(defaultTemplateId ?: templates.firstOrNull()?.id) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true
    val presetTokenOptions = listOf(500, 1000, 2000, 4000, 8000)
    val selectedTemplate = templates.firstOrNull { it.id == selectedTemplateId } ?: templates.firstOrNull()

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // 模板选择
                    Text(
                        text = stringResource(R.string.chat_page_compress_template),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Box {
                        OutlinedButton(
                            onClick = { templateMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = selectedTemplate?.let { t ->
                                    val effort = t.reasoningEffort?.takeIf { it.isNotBlank() }
                                    val scene = t.scene.takeIf { it.isNotBlank() && it != "custom" }
                                    listOfNotNull(t.name, scene, effort).joinToString(" · ")
                                } ?: stringResource(R.string.chat_page_compress_no_template),
                                maxLines = 1,
                            )
                        }
                        DropdownMenu(
                            expanded = templateMenuExpanded,
                            onDismissRequest = { templateMenuExpanded = false },
                        ) {
                            templates.forEach { t ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(t.name)
                                            val effort = t.reasoningEffort?.takeIf { it.isNotBlank() }
                                            val detail = listOfNotNull(
                                                t.scene.takeIf { it.isNotBlank() && it != "custom" },
                                                effort,
                                            ).joinToString(" · ")
                                            if (detail.isNotBlank()) {
                                                Text(
                                                    text = detail,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedTemplateId = t.id
                                        templateMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // 分界点说明
                    Text(
                        text = boundaryHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Token size selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_target_tokens),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        presetTokenOptions.forEachIndexed { index, tokens ->
                            SegmentedButton(
                                selected = selectedTokensOption == tokens,
                                onClick = { selectedTokensOption = tokens },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = presetTokenOptions.size + 1
                                )
                            ) {
                                Text("$tokens")
                            }
                        }
                        SegmentedButton(
                            selected = selectedTokensOption == -1,
                            onClick = { selectedTokensOption = -1 },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = presetTokenOptions.size,
                                count = presetTokenOptions.size + 1
                            )
                        ) {
                            Text(stringResource(R.string.chat_page_compress_custom_tokens))
                        }
                    }

                    if (selectedTokensOption == -1) {
                        OutlinedNumberInput(
                            value = customTokens,
                            onValueChange = { customTokens = it },
                            label = stringResource(R.string.chat_page_compress_target_tokens),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 保留量（仅整段压缩入口显示；这些内容不进总结、继续原样参与上下文）。
                    // 2026-08-30：条数 / token 二选一 —— 「保留最近 5 条」在 agent 会话里
                    // 毫无意义，5 条可能是 500 token 也可能是 300k，token 才是真正想控的量。
                    if (keepRecentDefault != null) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_keep_mode),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Box {
                            OutlinedButton(
                                onClick = { keepModeMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(
                                        when (keepMode) {
                                            CompressKeepMode.Count -> R.string.chat_page_compress_keep_mode_count
                                            CompressKeepMode.Token -> R.string.chat_page_compress_keep_mode_token
                                        }
                                    ),
                                    maxLines = 1,
                                )
                            }
                            DropdownMenu(
                                expanded = keepModeMenuExpanded,
                                onDismissRequest = { keepModeMenuExpanded = false },
                            ) {
                                CompressKeepMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when (mode) {
                                                        CompressKeepMode.Count ->
                                                            R.string.chat_page_compress_keep_mode_count
                                                        CompressKeepMode.Token ->
                                                            R.string.chat_page_compress_keep_mode_token
                                                    }
                                                )
                                            )
                                        },
                                        onClick = {
                                            keepMode = mode
                                            keepModeMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }

                        when (keepMode) {
                            CompressKeepMode.Count -> {
                                OutlinedNumberInput(
                                    value = keepRecent,
                                    onValueChange = { keepRecent = it.coerceAtLeast(0) },
                                    label = stringResource(R.string.chat_page_compress_keep_recent),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(R.string.chat_page_compress_keep_recent_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            CompressKeepMode.Token -> {
                                OutlinedNumberInput(
                                    value = keepTokens,
                                    onValueChange = { keepTokens = it.coerceAtLeast(0) },
                                    label = stringResource(R.string.chat_page_compress_keep_tokens),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = stringResource(R.string.chat_page_compress_keep_tokens_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // 语义说明（替代旧「重置所有消息」警告）
                    Text(
                        text = stringResource(R.string.chat_page_compress_keep_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    val templateId = selectedTemplateId ?: templates.firstOrNull()?.id ?: return@TextButton
                    val targetTokens = if (selectedTokensOption == -1) {
                        customTokens.coerceAtLeast(100)
                    } else {
                        selectedTokensOption
                    }
                    currentJob = onConfirm(
                        templateId,
                        additionalPrompt,
                        targetTokens,
                        when (keepMode) {
                            CompressKeepMode.Count -> keepRecent
                            CompressKeepMode.Token -> keepTokens
                        }.coerceAtLeast(0),
                        keepMode,
                    )
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
