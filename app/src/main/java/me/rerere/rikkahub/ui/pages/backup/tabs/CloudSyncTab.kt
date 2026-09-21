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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM

/**
 * 旧 `d1Config` 这张渠道卡的配置页（`Screen.CloudSyncLegacyD1`）。
 *
 * 2026-09-21（Step I-5 收束）：**只留真正属于这张卡的东西** ——
 * 凭据三件套 + 启用开关 + 测连接。
 *
 * 原先挂在这儿的「待上传条数 / 上次同步 / 熔断警告 / 推 / 拉 / 双向同步 / 全量上推」
 * 已整体移到 [CloudSyncActionsSection]（渠道列表页顶部）。理由写在那边的 KDoc 里：
 * 那些是 `SyncEngine` 级的状态与动作，不属于任何单张渠道卡；埋在这里的后果是
 * 在渠道列表页看不到任何推拉进度。**移走即删除，不留两份**。
 *
 * 凭证只存本机（device-local）。
 */
@Composable
fun CloudSyncD1Tab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val d1Config = settings.d1Config
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var verifiedConfigKey by remember { mutableStateOf<String?>(null) }
    val currentConfigKey = listOf(d1Config.accountId, d1Config.databaseId, d1Config.apiToken).joinToString("\n")

    val okMsg = stringResource(R.string.cloud_sync_test_success)
    val failTemplate = stringResource(R.string.cloud_sync_failed)
    val enableRequiresTestMsg = stringResource(R.string.cloud_sync_enable_requires_test)

    fun update(newConfig: D1Config) = vm.updateSettings(settings.copy(d1Config = newConfig))

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
                    text = stringResource(R.string.cloud_sync_enable),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        if (d1Config.isConfigured) R.string.cloud_sync_status_configured
                        else R.string.cloud_sync_status_not_configured
                    ),
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

        // ---- 设备名 / 自动同步 / 跨端即时信令 / 同步加速代理 ----
        //
        // 2026-09-21（Step I-5）：已**整体搬到渠道列表页底部**，见 [CloudSyncGlobalSection]。
        // 从这里删掉而不是复制：这几项作用于整个同步系统，多后端之后挂在某个渠道卡里
        // 会误导；而且两处并存意味着「两种改法改同一份设备名 / 代理密钥」。

        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                    runCatching { vm.testCloudSync() }
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
}
