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
import androidx.compose.runtime.LaunchedEffect
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
 * 多 Token 编辑器：每个 Token 一行，带开/关滑动开关 + 删除按钮 + 可选名称。
 * 顶部提供「批量添加」多行输入框，可一次粘贴多个 Token（空格/逗号/换行分隔）。
 *
 * 本地编辑态（workingTokens）保证「+ 添加 Token」产生的空行在填写前不会因为
 * apiKeyTokens 过滤空串而消失；持久化只写入非空、去重后的 Token。
 * 所有变更走单次 onChange（keys + disabled + names 一起提交），避免分两次
 * onEdit 用旧快照回滚前一次写入。
 *
 * @param providerId 用于状态隔离 + KeyRoulette 重新启用时清除关闭/冷却标记。
 */
@Composable
internal fun TokenPoolSection(
    tokens: List<String>,
    disabledTokens: List<String>,
    tokenNames: Map<String, String>,
    providerId: String,
    onChange: (keys: List<String>, disabled: List<String>, names: Map<String, String>) -> Unit,
) {
    var workingTokens by remember(providerId) { mutableStateOf(tokens) }
    var lastPersisted by remember(providerId) { mutableStateOf(tokens) }
    var batchText by remember(providerId) { mutableStateOf("") }
    var passwordVisible by remember(providerId) { mutableStateOf(false) }
    val context = LocalContext.current

    // 外部（其它端同步 / 自动关闭等）改动了持久化 Token 才回填本地编辑态；
    // 自身写入造成的回环（tokens == lastPersisted）不动正在编辑的列表。
    LaunchedEffect(providerId, tokens) {
        if (tokens != lastPersisted) {
            workingTokens = tokens
            lastPersisted = tokens
        }
    }

    // 单次写入：清理空串/去重后一次性提交 keys + disabled + names。
    fun persist(
        nextTokens: List<String>,
        nextDisabled: List<String> = disabledTokens,
        nextNames: Map<String, String> = tokenNames,
    ) {
        val clean = nextTokens.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        workingTokens = nextTokens
        lastPersisted = clean
        onChange(
            clean,
            nextDisabled.filter { it in clean },
            nextNames.filterKeys { it in clean },
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // —— 批量添加 ——
        OutlinedTextField(
            value = batchText,
            onValueChange = { batchText = it },
            label = { Text("批量添加（每行一个，直接粘贴）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        OutlinedButton(
            onClick = {
                val newKeys = batchText.split(Regex("[\\s,，]+")).filter { it.isNotBlank() }
                if (newKeys.isNotEmpty()) {
                    persist(workingTokens + newKeys)
                    batchText = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(HugeIcons.Add01, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("添加到列表")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("API Tokens (${workingTokens.size})", style = MaterialTheme.typography.titleSmall)
            if (workingTokens.isNotEmpty()) {
                TextButton(
                    onClick = { persist(emptyList()) }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            "多账号额度池，配合上方策略使用；关闭的 Token 不会参与轮换。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        workingTokens.forEachIndexed { index, token ->
            val isDisabled = token in disabledTokens
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = !isDisabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            // 重新启用：清掉 roulette 里的关闭/冷却标记
                            runCatching {
                                KeyRoulette.lru(context).revive(providerId, token)
                            }
                        }
                        persist(
                            workingTokens,
                            if (enable) disabledTokens.filterNot { it == token }
                            else (disabledTokens + token).distinct(),
                        )
                    },
                    enabled = token.isNotBlank(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { raw ->
                        val newTokens = updateTokens(workingTokens, index, raw)
                        // 改 key 时把名称跟着迁过去，避免改名后名称丢失
                        var names = tokenNames
                        val oldKey = workingTokens.getOrNull(index)?.trim()
                        if (oldKey != null && oldKey.isNotBlank() && newTokens.size == workingTokens.size) {
                            val newKey = newTokens[index]
                            if (oldKey != newKey) {
                                val oldName = tokenNames[oldKey]
                                if (oldName != null) {
                                    names = if (newKey.isBlank()) names - oldKey
                                    else (names - oldKey) + (newKey to oldName)
                                }
                            }
                        }
                        persist(newTokens, disabledTokens, names)
                    },
                    label = {
                        Text(tokenNames[token]?.takeIf { it.isNotBlank() } ?: "Token ${index + 1}")
                    },
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
                        persist(workingTokens.filterIndexed { i, _ -> i != index })
                    }
                ) {
                    Icon(HugeIcons.Delete01, "删除", tint = MaterialTheme.colorScheme.error)
                }
            }

            OutlinedTextField(
                value = tokenNames[token] ?: "",
                onValueChange = { name ->
                    val names = tokenNames.toMutableMap()
                    if (name.isBlank()) names.remove(token) else names[token] = name
                    persist(workingTokens, disabledTokens, names)
                },
                label = { Text("名称（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = token.isNotBlank() && !isDisabled,
            )
        }

        OutlinedButton(
            onClick = { persist(workingTokens + "") },
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
    // 本地 raw 字符串承接输入，避免 onValueChange 一解析失败就把输入打回（清空/删不掉字符）
    var countRaw by remember { mutableStateOf(retryCount.toString()) }
    var intervalRaw by remember { mutableStateOf(retryIntervalSec.toString()) }

    // 外部（切换 provider / 重置）变化时同步回显
    LaunchedEffect(retryCount) {
        if (countRaw.trim().toIntOrNull() != retryCount) countRaw = retryCount.toString()
    }
    LaunchedEffect(retryIntervalSec) {
        if (intervalRaw.trim().toIntOrNull() != retryIntervalSec) intervalRaw = retryIntervalSec.toString()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("失败重试", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val countErr = countRaw.isBlank() || countRaw.trim().toIntOrNull()?.let { it !in 1..10 } != false
            val intervalErr = intervalRaw.isBlank() || intervalRaw.trim().toIntOrNull()?.let { it !in 0..60 } != false
            OutlinedTextField(
                value = countRaw,
                onValueChange = { raw ->
                    countRaw = raw
                    val v = raw.trim().toIntOrNull()
                    if (v != null && v in 1..10) onRetryCountChange(v)
                },
                label = { Text("重试次数") },
                isError = countErr,
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = intervalRaw,
                onValueChange = { raw ->
                    intervalRaw = raw
                    val v = raw.trim().toIntOrNull()
                    if (v != null && v in 0..60) onRetryIntervalSecChange(v)
                },
                label = { Text("间隔（秒）") },
                isError = intervalErr,
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Text(
            "请求失败后自动重试，最多 ${retryCount.coerceIn(1, 10)} 次、每次间隔 ${retryIntervalSec.coerceIn(0, 60)} 秒；合法范围 1~10 / 0~60。输入中允许临时留空或非法字符，但只有合法数字会被保存。",
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
    // 本地 raw 字符串：用户可以慢慢敲、可以留空、可以有非法片段，不会被打回
    var raw by remember { mutableStateOf(closeOnCodes.joinToString(",")) }
    LaunchedEffect(closeOnCodes) {
        val parsedNow = raw.split(Regex("[,\\s，]+"))
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 100..599 }.distinct()
        if (parsedNow != closeOnCodes) raw = closeOnCodes.joinToString(",")
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("报错即关闭码", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = raw,
            onValueChange = { input ->
                raw = input
                val parsed = input.split(Regex("[,\\s，]+"))
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 100..599 }
                    .distinct()
                onChange(parsed)
            },
            label = { Text("报错码（逗号分隔，如 401,403,422）") },
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
