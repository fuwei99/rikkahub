package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.text.DateFormat
import java.util.Date

/**
 * 云同步的**状态 + 动作**（多后端 · Step I-5 收束）。
 *
 * ## 为什么从渠道页提到列表页
 *
 * 2026-09-21：这块内容原先是塞在旧 `CloudSyncD1Tab`（单张渠道卡的配置页）里的。
 * 但拆开看，**没有一项是 D1 专属的**：
 *
 * - `pendingCount` —— outbox 全应用只有一根，跟哪个后端无关；
 * - `lastSyncedAt` / 熔断状态 —— `SyncEngine` 级别，一个进程一份；
 * - 推 / 拉 / 双向同步 —— 跑的是整个 `SyncEngine`，按 `createAt` 扇出到各后端；
 * - 全量上推 —— 同理，扫的是本地全库。
 *
 * 唯一真正属于 D1 那张卡的只有「凭据 + 启用 + 测连接」。
 *
 * 埋错的后果是实打实的：在渠道列表页**完全看不到在不在推、还剩几条**，
 * 得先知道要点进「旧 D1」那张卡才看得到进度 —— 而进度恰恰是最该在门口一眼看见的东西。
 * 所以整体上提，且**从原处删除**（同一份状态绝不允许两处渲染，否则两处各有一份
 * 互不同步的 `busy`，还能同时点两下推）。
 */
@Composable
fun CloudSyncActionsSection(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingCount by vm.syncOutboxCount.collectAsStateWithLifecycle()
    val isCircuitBreakerOpen by vm.isSyncCircuitBreakerOpen.collectAsStateWithLifecycle()
    val lastSyncedAt by vm.syncLastSyncedAt.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var showSeedDialog by remember { mutableStateOf(false) }

    val doneMsg = stringResource(R.string.cloud_sync_done)
    val seedDoneTemplate = stringResource(R.string.cloud_sync_seed_done)
    val failTemplate = stringResource(R.string.cloud_sync_failed)

    // **任意一条**渠道配齐就放行 —— 判据不再挂在 `d1Config` 上（那正是 P5 修的 bug 之一：
    // 生效后端换成 Supabase 后，整条链因为「d1Config 未配置」而静默失效）。
    val anyConfigured = settings.d1Config.isConfigured || settings.backends.any { it.isConfigured }

    fun runTask(task: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { task() }
                .onSuccess { toaster.show(doneMsg, type = ToastType.Success) }
                .onFailure {
                    toaster.show(failTemplate.format(it.message ?: it.toString()), type = ToastType.Error)
                }
            busy = false
        }
    }

    fun runSeed(force: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { vm.cloudSeedAndSync(force = force) }
                .onSuccess { count -> toaster.show(seedDoneTemplate.format(count), type = ToastType.Success) }
                .onFailure {
                    toaster.show(failTemplate.format(it.message ?: it.toString()), type = ToastType.Error)
                }
            busy = false
        }
    }

    if (showSeedDialog) {
        AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text(stringResource(R.string.cloud_sync_seed)) },
            text = { Text(stringResource(R.string.cloud_sync_seed_choice_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSeedDialog = false
                        runSeed(force = false)
                    }
                ) { Text(stringResource(R.string.cloud_sync_seed_changed_only)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showSeedDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(
                        onClick = {
                            showSeedDialog = false
                            runSeed(force = true)
                        }
                    ) { Text(stringResource(R.string.cloud_sync_seed_force)) }
                }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isCircuitBreakerOpen) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.cloud_sync_circuit_breaker_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // 进度 + 时间戳：**这一页最该一眼看到的两行**。
        Text(
            text = stringResource(R.string.cloud_sync_pending_uploads, pendingCount),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (lastSyncedAt > 0L) {
                stringResource(
                    R.string.cloud_sync_last_synced,
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastSyncedAt)),
                )
            } else {
                stringResource(R.string.cloud_sync_last_synced_never)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 推与拉彻底分开：两个独立按钮，各自一把锁，一头失败不拖累另一头。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { runTask { vm.cloudPullNow() } },
                enabled = anyConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_pull_now))
            }
            Button(
                onClick = { runTask { vm.cloudPushNow() } },
                enabled = anyConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_push_now))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { runTask { vm.cloudSyncNow() } },
                enabled = anyConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_now))
            }
            OutlinedButton(
                onClick = { showSeedDialog = true },
                enabled = anyConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_seed))
            }
        }

        Text(
            text = stringResource(R.string.cloud_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
