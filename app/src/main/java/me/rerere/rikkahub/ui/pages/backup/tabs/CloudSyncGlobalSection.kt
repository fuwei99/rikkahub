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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.backend.StorageBackendConfig
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import org.koin.compose.koinInject

/**
 * 数据库同步 · **全局设置区**（多后端 · Step I-5）。
 *
 * ## 为什么单独拆出来
 *
 * 这几项（设备名 / 自动同步 / 跨端即时信令 / 同步加速代理）作用于**整个同步系统**，
 * 不是某个渠道的属性。多后端之前只有一个后端，把它们塞在 D1 面板里看不出问题；
 * 渠道列表页上线之后就明显了 —— 放在「D1（旧配置）」那张卡里，看着像「只对旧 D1 生效」。
 *
 * 内容**从 `CloudSyncD1Tab` 原样搬出，一个字符没改**（只补了它依赖的那几个局部状态）。
 * 搬迁方是单向的：搬完就从原处删掉，绝不两处并存 —— 两个地方都能改同一份设备名
 * 或同一份代理密钥，是这套迁移里最不该出现的东西。
 *
 * ## 位置
 *
 * 由 `CloudSyncDatabasePage` 挂在**渠道列表页底部**，渠道卡下方。
 */
@Composable
fun CloudSyncGlobalSection(vm: BackupVM) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val syncAdvancedConfigStore: SyncAdvancedConfigStore = koinInject()
    val syncAdvancedConfig by syncAdvancedConfigStore.configFlow.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var deviceLabel by remember { mutableStateOf(SyncLocalPrefs.deviceLabel(context)) }
    val failTemplate = stringResource(R.string.cloud_sync_failed)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        // ---- T7 跨端即时信令 ----
        // 放在自动同步开关下面：它是自动同步的加速通道，关掉只是退回轮询，不影响功能。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "跨端即时信令",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "开启后另一台设备的改动秒级到达，无需等轮询；关闭则回退为定时拉取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = syncAdvancedConfig.notifyEnabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        syncAdvancedConfigStore.update { it.copy(notifyEnabled = checked) }
                    }
                },
            )
        }

        // 本地 draft 暂存：每敲一个字符就落盘 JSON 并重连 WebSocket 既费 IO
        // 又会拿半截 URL 去连，因此只在失焦时提交一次。
        var notifyUrlDraft by remember(syncAdvancedConfig.notifyWorkerUrl) {
            mutableStateOf(syncAdvancedConfig.notifyWorkerUrl)
        }
        OutlinedTextField(
            value = notifyUrlDraft,
            onValueChange = { notifyUrlDraft = it },
            enabled = syncAdvancedConfig.notifyEnabled,
            label = { Text("信令服务地址") },
            placeholder = { Text("https://your-worker.example.com") },
            supportingText = {
                Text("留空即关闭。该地址只接收房间哈希与变更 id，不经手 D1 凭证和会话内容")
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused) {
                        val cleaned = notifyUrlDraft.trim().trimEnd('/')
                        if (cleaned != syncAdvancedConfig.notifyWorkerUrl) {
                            scope.launch {
                                syncAdvancedConfigStore.update { it.copy(notifyWorkerUrl = cleaned) }
                            }
                        }
                    }
                },
            singleLine = true,
        )

        // ---- Sync Proxy Worker（D1 批量 SQL 代理）----
        // 与信令 Worker 并列摆放：两者都是"可关的加速通道"，关掉只是变慢不会坏。
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "同步加速代理",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "把整轮同步的几十条查询打包成一次请求交给 Worker 执行，" +
                        "耗时从 20~30 秒降到 1 秒内；关闭则退回逐条直连 Cloudflare",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = syncAdvancedConfig.syncProxyEnabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        syncAdvancedConfigStore.update { it.copy(syncProxyEnabled = checked) }
                    }
                },
            )
        }

        // 同 notifyUrl：只在失焦时提交，避免每敲一个字符就落盘
        var proxyUrlDraft by remember(syncAdvancedConfig.syncProxyUrl) {
            mutableStateOf(syncAdvancedConfig.syncProxyUrl)
        }
        OutlinedTextField(
            value = proxyUrlDraft,
            onValueChange = { proxyUrlDraft = it },
            enabled = syncAdvancedConfig.syncProxyEnabled,
            label = { Text("代理服务地址") },
            placeholder = { Text("https://sync-proxy.example.com") },
            supportingText = { Text("留空即关闭。Worker 用 D1 绑定访问数据库，不接触你的 API Token") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused) {
                        val cleaned = proxyUrlDraft.trim().trimEnd('/')
                        if (cleaned != syncAdvancedConfig.syncProxyUrl) {
                            scope.launch {
                                syncAdvancedConfigStore.update { it.copy(syncProxyUrl = cleaned) }
                            }
                        }
                    }
                },
            singleLine = true,
        )

        var proxySecretDraft by remember(syncAdvancedConfig.syncProxySecret) {
            mutableStateOf(syncAdvancedConfig.syncProxySecret)
        }
        OutlinedTextField(
            value = proxySecretDraft,
            onValueChange = { proxySecretDraft = it },
            enabled = syncAdvancedConfig.syncProxyEnabled,
            label = { Text("代理访问密钥") },
            supportingText = { Text("需与 Worker 的 SYNC_SECRET 一致；留空则不启用代理") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (!state.isFocused) {
                        val cleaned = proxySecretDraft.trim()
                        if (cleaned != syncAdvancedConfig.syncProxySecret) {
                            scope.launch {
                                syncAdvancedConfigStore.update { it.copy(syncProxySecret = cleaned) }
                            }
                        }
                    }
                },
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "代理故障时自动直连",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "开启后代理不可用会静默退回直连（只慢不出错）；" +
                        "关闭则直接报错，便于确认代理是否真的在工作",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = syncAdvancedConfig.syncProxyFallbackToRest,
                enabled = syncAdvancedConfig.syncProxyEnabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        syncAdvancedConfigStore.update { it.copy(syncProxyFallbackToRest = checked) }
                    }
                },
            )
        }

        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                scope.launch {
                    runCatching { vm.testSyncProxy() }
                        .onSuccess { toaster.show("代理可用，往返 $it", type = ToastType.Success) }
                        .onFailure {
                            toaster.show(
                                failTemplate.format(it.message ?: it.toString()),
                                type = ToastType.Error,
                            )
                        }
                    busy = false
                }
            },
            enabled = syncAdvancedConfig.syncProxyEnabled && !busy,
        ) {
            Text("测试代理连通性")
        }
    }
}
