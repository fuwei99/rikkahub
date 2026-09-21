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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import me.rerere.rikkahub.data.sync.backend.StorageBackendConfig
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.text.DateFormat
import java.util.Date

/**
 * 数据库同步 · Supabase 面板（Step H）。
 *
 * 与 [CloudSyncD1Tab] 并列成一个 tab：凭证、开关、连通性自检各自独立 ——
 * 换后端不动另一边的 key。
 *
 * 凭据只存本机（device-local，随 settings 序列化但不参与上云），绝不编译进 APK。
 */
@Composable
fun CloudSyncSupabaseTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pendingCount by vm.syncOutboxCount.collectAsStateWithLifecycle()
    val lastSyncedAt by vm.syncLastSyncedAt.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var verifiedConfigKey by remember { mutableStateOf<String?>(null) }
    var deviceLabel by remember { mutableStateOf(SyncLocalPrefs.deviceLabel(context)) }

    // 草稿态：还没保存过就先用一个默认实例承载输入，首次编辑时才落进 Settings.backends
    val saved = settings.backends.filterIsInstance<StorageBackendConfig.Supabase>().firstOrNull()
    val cfg = saved ?: StorageBackendConfig.Supabase()
    val currentConfigKey = listOf(cfg.projectUrl, cfg.serviceKey).joinToString("\n")

    val okMsg = stringResource(R.string.cloud_sync_test_success)
    val doneMsg = stringResource(R.string.cloud_sync_done)
    val failTemplate = stringResource(R.string.cloud_sync_failed)
    val enableRequiresTestMsg = stringResource(R.string.cloud_sync_enable_requires_test)

    fun save(next: StorageBackendConfig.Supabase) {
        val list = settings.backends.toMutableList()
        val index = list.indexOfFirst { it is StorageBackendConfig.Supabase }
        if (index >= 0) list[index] = next else list += next
        vm.updateBackends(list)
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cloud_sync_enable_supabase),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (cfg.isConfigured) R.string.cloud_sync_status_configured
                        else R.string.cloud_sync_status_not_configured
                    ) + " · " + stringResource(R.string.cloud_sync_pending_uploads, pendingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = cfg.enabled,
                onCheckedChange = { checked ->
                    if (checked && verifiedConfigKey != currentConfigKey) {
                        toaster.show(failTemplate.format(enableRequiresTestMsg), type = ToastType.Error)
                    } else {
                        save(cfg.copy(enabled = checked))
                    }
                },
            )
        }

        OutlinedTextField(
            value = cfg.projectUrl,
            onValueChange = { save(cfg.copy(projectUrl = it.trim())) },
            label = { Text(stringResource(R.string.cloud_sync_supabase_project_url)) },
            placeholder = { Text("https://xxxx.supabase.co") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = cfg.serviceKey,
            onValueChange = { save(cfg.copy(serviceKey = it.trim())) },
            label = { Text(stringResource(R.string.cloud_sync_supabase_service_key)) },
            supportingText = { Text(stringResource(R.string.cloud_sync_supabase_service_key_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = cfg.schema,
            onValueChange = { save(cfg.copy(schema = it.trim())) },
            label = { Text(stringResource(R.string.cloud_sync_supabase_schema)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
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

        if (cfg.enabled) {
            Text(
                text = stringResource(R.string.cloud_sync_supabase_write_path_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                    runCatching { vm.testStorageBackend(cfg) }
                        .onSuccess {
                            verifiedConfigKey = currentConfigKey
                            toaster.show(okMsg, type = ToastType.Success)
                        }
                        .onFailure {
                            toaster.show(failTemplate.format(it.message ?: it.toString()), type = ToastType.Error)
                        }
                    busy = false
                }
            },
            enabled = cfg.isConfigured && !busy,
        ) {
            Text(stringResource(R.string.cloud_sync_test))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { runTask(doneMsg) { vm.cloudPullNow() } },
                enabled = cfg.isConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_pull_now))
            }
            Button(
                onClick = { runTask(doneMsg) { vm.cloudPushNow() } },
                enabled = cfg.isConfigured && !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_push_now))
            }
        }

        OutlinedButton(
            onClick = { runTask(doneMsg) { vm.cloudSyncNow() } },
            enabled = cfg.isConfigured && !busy,
        ) {
            Text(stringResource(R.string.cloud_sync_now))
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
    }
}
