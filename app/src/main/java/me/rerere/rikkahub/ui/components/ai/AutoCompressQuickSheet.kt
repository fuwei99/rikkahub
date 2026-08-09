package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
import me.rerere.rikkahub.data.ai.prompts.AutoCompressSetting
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.data.ai.prompts.mergeOverride
import me.rerere.rikkahub.data.ai.prompts.normalizedAgainst
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import kotlin.uuid.Uuid

/**
 * 对话级自动压缩配置面板（聊天输入面板 →「自动压缩」）。
 *
 * 分工：**助手设置只提供默认值**，这里改的是 [AutoCompressOverride] —— 只影响当前这一个对话。
 * - 每一项（总开关 / token 阈值与保留 / 条数阈值与保留 / 模板）都能单独覆盖，未覆盖的项跟随助手；
 * - 被覆盖的项标注「本对话」，可单项撤销；底部「全部恢复助手默认」把 override 整个清空；
 * - override 清空后置 null（而非留一堆 null 字段），保持会话 JSON 干净、云同步无噪声。
 */
@Composable
fun AutoCompressQuickSheet(
    assistantSetting: AutoCompressSetting,
    override: AutoCompressOverride?,
    templates: List<CompressTemplate>,
    onOverrideChange: (AutoCompressOverride?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // 生效值 = 助手默认 + 本对话覆盖，UI 上展示的一律是生效值
    val effective = assistantSetting.mergeOverride(override)

    fun update(block: (AutoCompressOverride) -> AutoCompressOverride) {
        val next = block(override ?: AutoCompressOverride())
        // 与助手默认相同的项自动回落成「继承」，不留死覆盖
        onOverrideChange(next.normalizedAgainst(assistantSetting))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("自动压缩（本对话）", style = MaterialTheme.typography.titleLarge)
            Text(
                "这里改的只对当前对话生效；助手设置里的那份是新对话的默认值。达到阈值时自动插入总结，原始消息保留，删掉总结即可恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— 总开关 ——
            OverrideListItem(
                title = "自动压缩",
                supporting = if (effective.enabled) {
                    "已开：达到下面任一阈值就自动总结"
                } else {
                    "已关：只能手动点「压缩历史」"
                },
                overridden = override?.enabled != null,
                inheritedText = if (assistantSetting.enabled) "助手默认：开" else "助手默认：关",
                onResetOverride = { update { it.copy(enabled = null) } },
                trailing = {
                    Switch(
                        checked = effective.enabled,
                        onCheckedChange = { checked -> update { it.copy(enabled = checked) } },
                    )
                },
            )

            AnimatedVisibility(visible = effective.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "两类限制可单独开也可同时开：满足任意一个即触发，保留量取更保守的那个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // —— token 限制 ——
                    OverrideListItem(
                        title = "按 token 触发",
                        supporting = null,
                        overridden = override?.tokenLimitEnabled != null,
                        inheritedText = if (assistantSetting.tokenLimitEnabled) "助手默认：开" else "助手默认：关",
                        onResetOverride = { update { it.copy(tokenLimitEnabled = null) } },
                        trailing = {
                            Switch(
                                checked = effective.tokenLimitEnabled,
                                onCheckedChange = { c -> update { it.copy(tokenLimitEnabled = c) } },
                            )
                        },
                    )
                    AnimatedVisibility(visible = effective.tokenLimitEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedNumberInput(
                                value = effective.tokenThreshold,
                                onValueChange = { v -> update { it.copy(tokenThreshold = v) } },
                                label = "触发阈值（token，助手默认 ${assistantSetting.tokenThreshold}）",
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedNumberInput(
                                value = effective.tokenKeep,
                                onValueChange = { v -> update { it.copy(tokenKeep = v) } },
                                label = "压缩后保留（token，助手默认 ${assistantSetting.tokenKeep}）",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // —— 条数限制 ——
                    OverrideListItem(
                        title = "按消息条数触发",
                        supporting = null,
                        overridden = override?.countLimitEnabled != null,
                        inheritedText = if (assistantSetting.countLimitEnabled) "助手默认：开" else "助手默认：关",
                        onResetOverride = { update { it.copy(countLimitEnabled = null) } },
                        trailing = {
                            Switch(
                                checked = effective.countLimitEnabled,
                                onCheckedChange = { c -> update { it.copy(countLimitEnabled = c) } },
                            )
                        },
                    )
                    AnimatedVisibility(visible = effective.countLimitEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedNumberInput(
                                value = effective.countThreshold,
                                onValueChange = { v -> update { it.copy(countThreshold = v) } },
                                label = "触发阈值（条，助手默认 ${assistantSetting.countThreshold}）",
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedNumberInput(
                                value = effective.countKeep,
                                onValueChange = { v -> update { it.copy(countKeep = v) } },
                                label = "压缩后保留（条，助手默认 ${assistantSetting.countKeep}）",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // —— 模板 ——
                    var tplExpanded by remember { mutableStateOf(false) }
                    val effectiveTpl = templates.firstOrNull { it.id == effective.templateId }
                    OverrideListItem(
                        title = "使用的压缩模板",
                        supporting = effectiveTpl?.let { "${it.name} · ${it.scene}" }
                            ?: "跟随助手默认模板",
                        overridden = override?.templateId != null,
                        inheritedText = templates.firstOrNull { it.id == assistantSetting.templateId }
                            ?.let { "助手默认：${it.name}" } ?: "助手默认：跟随助手默认模板",
                        onResetOverride = { update { it.copy(templateId = null) } },
                        trailing = {
                            Box {
                                OutlinedButton(onClick = { tplExpanded = true }) {
                                    Text("更换")
                                }
                                DropdownMenu(
                                    expanded = tplExpanded,
                                    onDismissRequest = { tplExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("跟随助手默认模板") },
                                        onClick = {
                                            tplExpanded = false
                                            update { it.copy(templateId = null) }
                                        },
                                    )
                                    templates.forEach { t ->
                                        DropdownMenuItem(
                                            text = { Text("${t.name} · ${t.scene}") },
                                            onClick = {
                                                tplExpanded = false
                                                update { it.copy(templateId = t.id) }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.fillMaxWidth())

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                TextButton(
                    onClick = { onOverrideChange(null) },
                    enabled = override != null,
                ) {
                    Text("全部恢复助手默认")
                }
                TextButton(onClick = onDismiss) {
                    Text("完成")
                }
            }
        }
    }
}

/**
 * 带「本对话已覆盖」标记的设置行：标注哪些项脱离了助手默认，并给单项撤销入口。
 */
@Composable
private fun OverrideListItem(
    title: String,
    supporting: String?,
    overridden: Boolean,
    inheritedText: String,
    onResetOverride: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (overridden) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "本对话已覆盖（$inheritedText）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = onResetOverride) {
                            Text("跟随助手", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        trailingContent = { trailing() },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
