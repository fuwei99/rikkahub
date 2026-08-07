package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

/**
 * 云锚点同步（P1）：Cloudflare D1 配置与手动同步入口。
 * D1 为文本唯一事实源；配置只保存在本机（device-local），不参与上云。
 */
@Composable
fun CloudSyncTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingCount by vm.syncOutboxCount.collectAsStateWithLifecycle()
    val isCircuitBreakerOpen by vm.isSyncCircuitBreakerOpen.collectAsStateWithLifecycle()
    val lastSyncedAt by vm.syncLastSyncedAt.collectAsStateWithLifecycle()
    val d1Config = settings.d1Config
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val syncAdvancedConfigStore: SyncAdvancedConfigStore = koinInject()
    val syncAdvancedConfig by syncAdvancedConfigStore.configFlow.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var showSeedDialog by remember { mutableStateOf(false) }
    var verifiedConfigKey by remember { mutableStateOf<String?>(null) }
    var deviceLabel by remember { mutableStateOf(SyncLocalPrefs.deviceLabel(context)) }
    val currentConfigKey = listOf(d1Config.accountId, d1Config.databaseId, d1Config.apiToken).joinToString("\n")

    val okMsg = stringResource(R.string.cloud_sync_test_success)
    val doneMsg = stringResource(R.string.cloud_sync_done)
    val seedDoneTemplate = stringResource(R.string.cloud_sync_seed_done)
    val failTemplate = stringResource(R.string.cloud_sync_failed)
    val enableRequiresTestMsg = stringResource(R.string.cloud_sync_enable_requires_test)

    fun update(newConfig: D1Config) = vm.updateSettings(settings.copy(d1Config = newConfig))

    fun runTask(successMsg: String, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { task() }
                .onSuccess { toaster.show(successMsg, type = ToastType.Success) }
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
                    toaster.show(
                        failTemplate.format(it.message ?: it.toString()),
                        type = ToastType.Error
                    )
                }
            busy = false
        }
    }

    if (showSeedDialog) {
        AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text("全量推送") },
            text = { Text("请选择推送方式。普通模式只会把本地有变化的会话加入队列；强制模式会重新上传全部会话，但会先去重，不会越点越多。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSeedDialog = false
                        runSeed(force = false)
                    }
                ) { Text("仅推送有变化") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showSeedDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(
                        onClick = {
                            showSeedDialog = false
                            runSeed(force = true)
                        }
                    ) { Text("强制重传全部") }
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_sync_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (d1Config.isConfigured) R.string.cloud_sync_status_configured
                        else R.string.cloud_sync_status_not_configured
                    ) + " · " + stringResource(R.string.cloud_sync_pending_uploads, pendingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = d1Config.enabled,
                onCheckedChange = { checked ->
                    if (checked && verifiedConfigKey != currentConfigKey) {
                        toaster.show(failTemplate.format(enableRequiresTestMsg), type = ToastType.Error)
                    } else {
                        update(d1Config.copy(enabled = checked))
                    }
                },
            )
        }

        OutlinedTextField(
            value = d1Config.accountId,
            onValueChange = { update(d1Config.copy(accountId = it.trim())) },
            label = { Text(stringResource(R.string.cloud_sync_account_id)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = d1Config.databaseId,
            onValueChange = { update(d1Config.copy(databaseId = it.trim())) },
            label = { Text(stringResource(R.string.cloud_sync_database_id)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = d1Config.apiToken,
            onValueChange = { update(d1Config.copy(apiToken = it.trim())) },
            label = { Text(stringResource(R.string.cloud_sync_api_token)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        // 设备标识：分叉会话的标题后缀用它，所以建议短且唯一（如 k70 / matepad）
        OutlinedTextField(
            value = deviceLabel,
            onValueChange = {
                deviceLabel = it
                SyncLocalPrefs.setDeviceLabel(context, it)
            },
            label = { Text(stringResource(R.string.cloud_sync_device_label)) },
            supportingText = { Text(stringResource(R.string.cloud_sync_device_label_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        HorizontalDivider()

        // 自动同步开关：关闭后只有下面两个按钮会联网
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_sync_auto_enable),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.cloud_sync_auto_enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = syncAdvancedConfig.autoSyncEnabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        syncAdvancedConfigStore.update { it.copy(autoSyncEnabled = checked) }
                    }
                },
            )
        }

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

        Text(
            text = stringResource(R.string.cloud_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (busy) return@OutlinedButton
                    busy = true
                    scope.launch {
                        runCatching {
                            vm.testCloudSync()
                        }
                            .onSuccess {
                                verifiedConfigKey = currentConfigKey
                                toaster.show(okMsg, type = ToastType.Success)
                            }
                            .onFailure {
                                toaster.show(
                                    failTemplate.format(it.message ?: it.toString()),
                                    type = ToastType.Error
                                )
                            }
                        busy = false
                    }
                },
                enabled = d1Config.hasRequiredFields && !busy,
            ) {
                Text(stringResource(R.string.cloud_sync_test))
            }
        }

        // 推与拉彻底分开：两个独立按钮，各自一把锁，一头失败不拖累另一头
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { runTask(doneMsg) { vm.cloudPullNow() } },
                enabled = d1Config.isConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_pull_now))
            }
            Button(
                onClick = { runTask(doneMsg) { vm.cloudPushNow() } },
                enabled = d1Config.isConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_push_now))
            }
        }

        OutlinedButton(
            onClick = { runTask(doneMsg) { vm.cloudSyncNow() } },
            enabled = d1Config.isConfigured && !busy,
        ) {
            Text(stringResource(R.string.cloud_sync_now))
        }

        OutlinedButton(
            onClick = { showSeedDialog = true },
            enabled = d1Config.isConfigured && !busy,
        ) {
            Text(stringResource(R.string.cloud_sync_seed))
        }

        HorizontalDivider()

        // R2 媒体账户（P3）：删除/换密钥二次确认 + 硬警告
        R2AccountsSection(vm = vm)
    }
}
