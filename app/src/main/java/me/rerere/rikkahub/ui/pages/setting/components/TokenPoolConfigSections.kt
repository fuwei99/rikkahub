package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.KeyStrategy
import me.rerere.ai.util.KeyRoulette
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * Token 池配置共享组件（生图渠道 / LLM 渠道通用）：
 * - 轮询策略（轮询 / 随机 / 失败切换）
 * - 多 Token 编辑器（每个 Token 带开/关滑动开关 + 删除按钮）
 * - 失败重试（次数 + 间隔）
 * - 报错即关闭码（命中后关闭而不是删除该 Token）
 */

/** Token 轮询策略选择：轮询 / 随机 / 失败切换。 */
@Composable
internal fun TokenStrategySection(
    strategy: KeyStrategy,
    onEdit: (KeyStrategy) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Token 轮询策略", style = MaterialTheme.typography.titleSmall)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            KeyStrategy.entries.forEachIndexed { index, s ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = KeyStrategy.entries.size
                    ),
                    selected = strategy == s,
                    onClick = { onEdit(s) },
                    label = {
                        Text(
                            when (s) {
                                KeyStrategy.ROUND_ROBIN -> "轮询"
                                KeyStrategy.RANDOM -> "随机"
                                KeyStrategy.FAILOVER -> "失败切换"
                            }
                        )
                    }
                )
            }
        }

        Text(
            when (strategy) {
                KeyStrategy.ROUND_ROBIN -> "按顺序循环使用所有 Token，尽量均衡分摊每个账号的额度。"
                KeyStrategy.RANDOM -> "每次请求随机挑选一个 Token。"
                KeyStrategy.FAILOVER -> "固定使用第一个可用 Token，仅遇到 401/403/422/429 才自动切换到下一个；命中报错码（默认 401/403/422）的 Token 会被自动关闭（保留但禁用，可手动重新启用）。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 多 Token 编辑器：每个 Token 一行，带开/关滑动开关 + 删除按钮，支持批量粘贴。
 *
 * @param providerId 用于 KeyRoulette 状态隔离；重新启用 Token 时清除其关闭/冷却标记。
 */
@Composable
internal fun TokenPoolSection(
    tokens: List<String>,
    disabledTokens: List<String>,
    providerId: String,
    onTokensChange: (List<String>) -> Unit,
    onDisabledChange: (List<String>) -> Unit,
) {
    var passwordVisible by remember(providerId) { mutableStateOf(false) }
    val context = LocalContext.current

    // Token 列表变化时，把已不在列表里的禁用项清掉，避免残留脏数据。
    fun notifyTokensChanged(updated: List<String>) {
        val pruned = disabledTokens.filter { it in updated }
        onTokensChange(updated)
        if (pruned != disabledTokens) onDisabledChange(pruned)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("API Tokens (${tokens.size})", style = MaterialTheme.typography.titleSmall)
            if (tokens.isNotEmpty()) {
                TextButton(
                    onClick = { notifyTokensChanged(emptyList()) }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            "每个 Token 一行，可直接批量粘贴（空格/逗号/换行分隔）。多账号额度池，配合上方策略使用；关闭的 Token 不会参与轮换。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        tokens.forEachIndexed { index, token ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isDisabled = token in disabledTokens
                Switch(
                    checked = !isDisabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            // 重新启用：清掉 roulette 里的关闭/冷却标记
                            runCatching {
                                KeyRoulette.lru(context).revive(providerId, token)
                            }
                            onDisabledChange(disabledTokens.filterNot { it == token })
                        } else {
                            onDisabledChange((disabledTokens + token).distinct())
                        }
                    }
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { raw ->
                        notifyTokensChanged(updateTokens(tokens, index, raw))
                    },
                    label = { Text("Token ${index + 1}") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isDisabled,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) HugeIcons.View else HugeIcons.ViewOff, null)
                        }
                    },
                )
                IconButton(
                    onClick = {
                        notifyTokensChanged(tokens.filterIndexed { i, _ -> i != index })
                    }
                ) {
                    Icon(HugeIcons.Delete01, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        OutlinedButton(
            onClick = { notifyTokensChanged(tokens + "") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("添加 Token")
        }
    }
}

/** 编辑单个 Token；若输入中出现换行/逗号（如批量粘贴），则按分隔符拆分重排整个列表。 */
internal fun updateTokens(tokens: List<String>, index: Int, raw: String): List<String> {
    if (raw.contains('\n') || raw.contains(',') || raw.contains(' ')) {
        val buffer = buildString {
            tokens.take(index).forEach { append(it); append('\n') }
            append(raw)
            tokens.drop(index + 1).forEach { append('\n'); append(it) }
        }
        return buffer.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.distinct()
    }
    return tokens.toMutableList().apply { this[index] = raw }
}

/** 失败重试：次数（默认 3）+ 间隔秒（默认 1）。 */
@Composable
internal fun RetryPolicySection(
    retryCount: Int,
    retryIntervalSec: Int,
    onRetryCountChange: (Int) -> Unit,
    onRetryIntervalSecChange: (Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("失败重试", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = retryCount.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.coerceIn(1, 10)?.let { onRetryCountChange(it) }
                },
                label = { Text("重试次数") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = retryIntervalSec.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.coerceIn(0, 60)?.let { onRetryIntervalSecChange(it) }
                },
                label = { Text("间隔（秒）") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Text(
            "请求失败后自动重试，最多 ${retryCount.coerceAtLeast(1)} 次、每次间隔 ${retryIntervalSec.coerceAtLeast(0)} 秒；重试耗尽后才轮换 Token / 报错。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 报错即关闭码：命中这些报错码的 Token 被自动关闭（禁用）而不是删除。 */
@Composable
internal fun CloseCodesSection(
    closeOnCodes: List<Int>,
    onChange: (List<Int>) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("报错即关闭码", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = closeOnCodes.joinToString(","),
            onValueChange = { raw ->
                val parsed = raw.split(Regex("[,\\s，]+"))
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 100..599 }
                    .distinct()
                onChange(parsed)
            },
            label = { Text("报错码（逗号分隔）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text(
            "命中这些报错码的 Token 会被自动关闭（保留但禁用，可手动重新启用），而不是删除；默认 401/403/422。429 限流始终只冷却不关闭。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
