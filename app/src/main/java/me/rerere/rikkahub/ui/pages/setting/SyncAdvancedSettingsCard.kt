package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig

@Composable
fun SyncAdvancedSettingsCard(
    config: SyncAdvancedConfig,
    configPath: String,
    onChange: ((SyncAdvancedConfig) -> SyncAdvancedConfig) -> Unit,
    onReset: () -> Unit,
    r2PresignTtlSeconds: Long,
    onR2PresignTtlChange: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("云同步高级设置", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "本地 JSON：$configPath，可由 Workspace/Agent 挂载后直接修改",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onReset) { Text("重置") }
            }

            SyncOptionRow(
                title = "前台拉取远端变化",
                value = intervalLabel(config.foregroundPullIntervalMs),
                options = listOf(0L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L),
                selected = config.foregroundPullIntervalMs,
                label = ::intervalLabel,
                onSelect = { value -> onChange { it.copy(foregroundPullIntervalMs = value) } },
            )
            SyncOptionRow(
                title = "本地改动上传延迟",
                value = intervalLabel(config.outboxFlushDebounceMs),
                options = listOf(0L, 1_000L, 3_000L, 5_000L, 10_000L, 30_000L),
                selected = config.outboxFlushDebounceMs,
                label = ::intervalLabel,
                onSelect = { value -> onChange { it.copy(outboxFlushDebounceMs = value) } },
            )
            SyncOptionRow(
                title = "连续失败暂停阈值",
                value = "${config.circuitBreakerFailureThreshold} 次",
                options = listOf(3, 5, 10, 20),
                selected = config.circuitBreakerFailureThreshold,
                label = { "$it 次" },
                onSelect = { value -> onChange { it.copy(circuitBreakerFailureThreshold = value) } },
            )
            SyncOptionRow(
                title = "失败后暂停自动同步",
                value = durationLabel(config.circuitBreakerCooldownMs),
                options = listOf(10 * 60_000L, 30 * 60_000L, 60 * 60_000L, 3 * 60 * 60_000L),
                selected = config.circuitBreakerCooldownMs,
                label = ::durationLabel,
                onSelect = { value -> onChange { it.copy(circuitBreakerCooldownMs = value) } },
            )
            SyncOptionRow(
                title = "媒体后台上传批量",
                value = "${config.mediaUploadBatchLimit}",
                options = listOf(1, 2, 4, 8, 16),
                selected = config.mediaUploadBatchLimit,
                label = { it.toString() },
                onSelect = { value -> onChange { it.copy(mediaUploadBatchLimit = value) } },
            )
            SyncOptionRow(
                title = "媒体上传最大重试次数",
                value = "${config.mediaUploadMaxRetries} 次",
                options = listOf(3, 5, 8, 12),
                selected = config.mediaUploadMaxRetries,
                label = { "$it 次" },
                onSelect = { value -> onChange { it.copy(mediaUploadMaxRetries = value) } },
            )
            SyncOptionRow(
                title = "媒体上传最大重试间隔",
                value = "${config.mediaUploadMaxBackoffMinutes} 分钟",
                options = listOf(15, 30, 60, 360),
                selected = config.mediaUploadMaxBackoffMinutes,
                label = { if (it >= 60) "${it / 60} 小时" else "$it 分钟" },
                onSelect = { value -> onChange { it.copy(mediaUploadMaxBackoffMinutes = value) } },
            )
            SyncOptionRow(
                title = "R2 临时链接有效期（同步）",
                value = durationLabel(r2PresignTtlSeconds * 1_000L),
                options = listOf(
                    15 * 60L,
                    60 * 60L,
                    6 * 60 * 60L,
                    24 * 60 * 60L,
                    7 * 24 * 60 * 60L,
                    30 * 24 * 60 * 60L,
                    90 * 24 * 60 * 60L,
                ),
                selected = r2PresignTtlSeconds,
                label = { durationLabel(it * 1_000L) },
                onSelect = onR2PresignTtlChange,
            )
        }
    }
}

@Composable
private fun <T> SyncOptionRow(
    title: String,
    value: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
    }
}

private fun intervalLabel(ms: Long): String = when (ms) {
    0L -> "关闭/立即"
    in 1 until 1_000L -> "${ms}ms"
    else -> "${ms / 1_000}s"
}

private fun durationLabel(ms: Long): String = when {
    ms < 60_000L -> "${ms / 1_000}s"
    ms < 60 * 60_000L -> "${ms / 60_000L} 分钟"
    ms < 24 * 60 * 60_000L -> "${ms / (60 * 60_000L)} 小时"
    else -> "${ms / (24 * 60 * 60_000L)} 天"
}
