package me.rerere.rikkahub.ui.pages.cloudsync

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.backend.StorageBackendConfig
import me.rerere.rikkahub.data.sync.backend.epochToDateText
import me.rerere.rikkahub.data.sync.backend.parseDateToEpoch
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 单个同步渠道的配置页（多后端 · Step I-3，2026-09-21）。
 *
 * ## 为什么要有这一页
 *
 * 之前 D1 / Supabase 各占一个 tab，且都用 `filterIsInstance<...>().firstOrNull()`
 * 定位「要编辑的那一个」—— 于是**第二个同类后端在 UI 上根本编不了**。
 * 而天赢的方案是「Supabase 满了就再挂一个账号，有 20 个」，
 * 多实例是常态而不是例外，所以必须改成「列表 → 点进详情」。
 *
 * ## 与列表页的分工
 *
 * [CloudSyncDatabasePage] 只负责列出渠道（名字 / 描述 / 时间段）；
 * 本页负责**单个渠道**的全部可编辑项：别名、时间段、凭据、启用开关、连通性自检。
 * 编辑即时落盘（`vm.updateBackends`），没有「保存」按钮 —— 与既有设置页一致。
 *
 * ## 关闭 ≠ 删除
 *
 * 关掉只表示「不再接收新推送」，历史数据照样读得回来（见 [StorageBackendRouter.readTarget]）。
 * 启用前必须先测试成功，否则本地能开、云端连不上，是纯粹的静默失败。
 */
@Composable
fun CloudSyncBackendPage(backendId: String, vm: BackupVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backends = settings.backends
    val cfg = backends.firstOrNull { it.id == backendId }

    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val okMsg = stringResource(R.string.cloud_sync_test_success)
    val failTemplate = stringResource(R.string.cloud_sync_failed)
    val enableRequiresTest = stringResource(R.string.cloud_sync_enable_requires_test)

    var busy by remember { mutableStateOf(false) }
    var verifiedKey by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }

    fun save(next: StorageBackendConfig) {
        vm.updateBackends(backends.map { if (it.id == next.id) next else it })
    }

    // 日期草稿态：允许中间态（"2026-09-1" 解析不了），能解析才写回 Settings。
    // key 绑 cfg?.id，切渠道不会串台。
    var startText by remember(cfg?.id) { mutableStateOf(epochToDateText(cfg?.rangeStart)) }
    var endText by remember(cfg?.id) { mutableStateOf(epochToDateText(cfg?.rangeEnd)) }

    val title = cfg?.let { it.alias.ifBlank { it.typeName } }
        ?: stringResource(R.string.cloud_sync_backend_page_title)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (cfg == null) {
                Text(
                    text = stringResource(R.string.cloud_sync_backend_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (cfg is StorageBackendConfig.D1) R.string.cloud_sync_backend_d1
                            else R.string.cloud_sync_backend_supabase
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            if (cfg.isConfigured) R.string.cloud_sync_status_configured
                            else R.string.cloud_sync_status_not_configured
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = cfg.enabled,
                    onCheckedChange = { checked ->
                        if (checked && verifiedKey != credentialKey(cfg)) {
                            toaster.show(enableRequiresTest, type = ToastType.Error)
                        } else {
                            save(withEnabled(cfg, checked))
                        }
                    },
                )
            }

            OutlinedTextField(
                value = cfg.alias,
                onValueChange = { v -> save(withAlias(cfg, v)) },
                label = { Text(stringResource(R.string.cloud_sync_routing_alias)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = startText,
                onValueChange = { t ->
                    startText = t
                    if (t.isBlank()) save(withRange(cfg, null, cfg.rangeEnd))
                    else parseDateToEpoch(t)?.let { ms -> save(withRange(cfg, ms, cfg.rangeEnd)) }
                },
                label = { Text(stringResource(R.string.cloud_sync_routing_range_start)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = endText,
                onValueChange = { t ->
                    endText = t
                    if (t.isBlank()) save(withRange(cfg, cfg.rangeStart, null))
                    else parseDateToEpoch(t)?.let { ms -> save(withRange(cfg, cfg.rangeStart, ms)) }
                },
                label = { Text(stringResource(R.string.cloud_sync_routing_range_end)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text(
                text = stringResource(R.string.cloud_sync_routing_range_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.cloud_sync_backend_credentials),
                style = MaterialTheme.typography.titleSmall,
            )

            when (cfg) {
                is StorageBackendConfig.D1 -> {
                    OutlinedTextField(
                        value = cfg.accountId,
                        onValueChange = { v -> save(cfg.copy(accountId = v.trim())) },
                        label = { Text(stringResource(R.string.cloud_sync_account_id)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.databaseId,
                        onValueChange = { v -> save(cfg.copy(databaseId = v.trim())) },
                        label = { Text(stringResource(R.string.cloud_sync_database_id)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.apiToken,
                        onValueChange = { v -> save(cfg.copy(apiToken = v.trim())) },
                        label = { Text(stringResource(R.string.cloud_sync_api_token)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                is StorageBackendConfig.Supabase -> {
                    OutlinedTextField(
                        value = cfg.projectUrl,
                        onValueChange = { v -> save(cfg.copy(projectUrl = v.trim())) },
                        label = { Text(stringResource(R.string.cloud_sync_supabase_project_url)) },
                        placeholder = { Text("https://<ref>.supabase.co") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cfg.serviceKey,
                        onValueChange = { v -> save(cfg.copy(serviceKey = v.trim())) },
                        label = { Text(stringResource(R.string.cloud_sync_supabase_service_key)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = cfg.schema,
                        onValueChange = { v -> save(cfg.copy(schema = v.trim())) },
                        label = { Text(stringResource(R.string.cloud_sync_supabase_schema)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            Button(
                enabled = cfg.isConfigured && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { vm.testStorageBackend(cfg) }
                            .onSuccess {
                                verifiedKey = credentialKey(cfg)
                                toaster.show(okMsg, type = ToastType.Success)
                            }
                            .onFailure {
                                toaster.show(
                                    failTemplate.format(it.message ?: it.toString()),
                                    type = ToastType.Error,
                                )
                            }
                        busy = false
                    }
                },
            ) {
                Text(
                    text = stringResource(
                        if (busy) R.string.cloud_sync_backend_testing
                        else R.string.cloud_sync_backend_test
                    )
                )
            }

            Text(
                text = stringResource(R.string.cloud_sync_backend_enable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { showDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cloud_sync_backend_delete))
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.cloud_sync_backend_delete)) },
            text = { Text(stringResource(R.string.cloud_sync_backend_delete_warn, title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDelete = false
                        vm.updateBackends(backends.filterNot { it.id == backendId })
                        navController.popBackStack()
                    }
                ) {
                    Text(stringResource(R.string.cloud_sync_backend_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** 凭据指纹：改了任一凭据就要重新测试才能开启。 */
private fun credentialKey(cfg: StorageBackendConfig): String = when (cfg) {
    is StorageBackendConfig.D1 -> listOf(cfg.accountId, cfg.databaseId, cfg.apiToken).joinToString("\n")
    is StorageBackendConfig.Supabase -> listOf(cfg.projectUrl, cfg.serviceKey).joinToString("\n")
}

/** `when` 作用在 sealed 上，加第三个后端漏补这里会直接编译报错。 */
private fun withRange(cfg: StorageBackendConfig, start: Long?, end: Long?): StorageBackendConfig =
    when (cfg) {
        is StorageBackendConfig.D1 -> cfg.copy(rangeStart = start, rangeEnd = end)
        is StorageBackendConfig.Supabase -> cfg.copy(rangeStart = start, rangeEnd = end)
    }

private fun withAlias(cfg: StorageBackendConfig, alias: String): StorageBackendConfig =
    when (cfg) {
        is StorageBackendConfig.D1 -> cfg.copy(alias = alias)
        is StorageBackendConfig.Supabase -> cfg.copy(alias = alias)
    }

private fun withEnabled(cfg: StorageBackendConfig, enabled: Boolean): StorageBackendConfig =
    when (cfg) {
        is StorageBackendConfig.D1 -> cfg.copy(enabled = enabled)
        is StorageBackendConfig.Supabase -> cfg.copy(enabled = enabled)
    }
