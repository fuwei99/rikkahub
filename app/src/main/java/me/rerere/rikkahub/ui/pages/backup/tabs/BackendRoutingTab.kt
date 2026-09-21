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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.backend.StorageBackendConfig
import me.rerere.rikkahub.data.sync.backend.StorageBackendRouter
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 数据库同步 · 路由面板（多后端 · Step I-2，2026-09-21）。
 *
 * ## 它解决什么
 *
 * D1 免费版 10 万行/天写额度每天都在爆（实测 92k~110k），根因是按行计费 +
 * `conv_nodes` 一行一节点。天赢拍板走**时间分片多后端**：
 * 老数据原地留在 D1，9 月 16 号之后的新会话进 Supabase，满了再加一个账号。
 *
 * 本面板就是这套规矩的**配置面**：每个后端认领一段时间，会话按「建立时间」
 * 落到对应后端（判据见 [StorageBackendRouter]）。
 *
 * ## 为什么时间段不跟后端列表一起上云不行
 *
 * `backends` 含 `apiToken` / `serviceKey`，在 `SyncFieldRegistry` 里被打成
 * `local()` 整条剔除 —— 于是**时间窗口也被一起剔掉了**，B 设备会不知道
 * 「9 月 16 号之后的数据在哪个库」，永远拉不到那批会话。
 * 修法是拆成两块：`backends`（含密钥，设备本地）+ 路由投影（随设置同步），
 * 投影由 [StorageBackendRouter.routingsOf] 生成，**本面板只负责前者**。
 *
 * ## 只读与启用是两件事
 *
 * 关闭一个后端只表示「不再接收新推送」，它的历史数据照样读得回来 ——
 * 这是「换后端是一次可回退的切换，而不是一次数据搬迁」的前提。
 * 所以 [StorageBackendRouter.readTarget] 刻意不看 enabled，只有
 * [StorageBackendRouter.writeTarget] 才要求 enabled。
 */
@Composable
fun BackendRoutingTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val backends = settings.backends

    val warnings = remember(backends) { StorageBackendRouter.validate(backends) }
    val latest = remember(backends) { StorageBackendRouter.latestWriteTarget(backends) }

    fun save(list: List<StorageBackendConfig>) = vm.updateBackends(list)

    fun patch(id: String, f: (StorageBackendConfig) -> StorageBackendConfig) =
        save(backends.map { b -> if (b.id == id) f(b) else b })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.cloud_sync_routing_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(
                R.string.cloud_sync_routing_settings_goes_to,
                latest?.let { it.alias.ifBlank { it.typeName } }
                    ?: stringResource(R.string.cloud_sync_routing_none),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 体检：重复 id / 时间重叠 / **时间空档**。
        // 空档最要命 —— 那段时间的会话没有后端认领，会静默不同步，必须显式喊出来。
        warnings.forEach { w ->
            Text(
                text = "\u26a0 $w",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (backends.isEmpty()) {
            Text(
                text = stringResource(R.string.cloud_sync_routing_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        backends.forEach { cfg ->
            BackendRouteCard(
                cfg = cfg,
                onAliasChange = { v -> patch(cfg.id) { b -> b.withAlias(v) } },
                onEnabledChange = { v -> patch(cfg.id) { b -> b.withEnabled(v) } },
                onRangeChange = { s, e -> patch(cfg.id) { b -> b.withRange(s, e) } },
                onDelete = { save(backends.filterNot { it.id == cfg.id }) },
            )
            HorizontalDivider()
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { save(backends + StorageBackendConfig.D1(alias = "D1")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_routing_add_d1))
            }
            OutlinedButton(
                onClick = { save(backends + StorageBackendConfig.Supabase(alias = "Supabase")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.cloud_sync_routing_add_supabase))
            }
        }

        Text(
            text = stringResource(R.string.cloud_sync_routing_range_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun BackendRouteCard(
    cfg: StorageBackendConfig,
    onAliasChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRangeChange: (Long?, Long?) -> Unit,
    onDelete: () -> Unit,
) {
    // 草稿文本：日期要允许中间态（"2026-09-1" 解析不了），所以本地先存原串，
    // 能解析才写回 Settings。绑定 cfg.id 做 key，切换/删除后端不会串台。
    var startText by remember(cfg.id) { mutableStateOf(epochToDateText(cfg.rangeStart)) }
    var endText by remember(cfg.id) { mutableStateOf(epochToDateText(cfg.rangeEnd)) }

    val typeLabel = when (cfg) {
        is StorageBackendConfig.D1 -> stringResource(R.string.cloud_sync_backend_d1)
        is StorageBackendConfig.Supabase -> stringResource(R.string.cloud_sync_backend_supabase)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = typeLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        if (cfg.isConfigured) R.string.cloud_sync_status_configured
                        else R.string.cloud_sync_status_not_configured
                    ) + " · " + stringResource(
                        if (cfg.enabled) R.string.cloud_sync_routing_enabled
                        else R.string.cloud_sync_routing_disabled_but_readable
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = cfg.enabled, onCheckedChange = onEnabledChange)
        }

        OutlinedTextField(
            value = cfg.alias,
            onValueChange = onAliasChange,
            label = { Text(stringResource(R.string.cloud_sync_routing_alias)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = startText,
            onValueChange = { t ->
                startText = t
                if (t.isBlank()) onRangeChange(null, cfg.rangeEnd)
                else parseDateToEpoch(t)?.let { ms -> onRangeChange(ms, cfg.rangeEnd) }
            },
            label = { Text(stringResource(R.string.cloud_sync_routing_range_start)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = endText,
            onValueChange = { t ->
                endText = t
                if (t.isBlank()) onRangeChange(cfg.rangeStart, null)
                else parseDateToEpoch(t)?.let { ms -> onRangeChange(cfg.rangeStart, ms) }
            },
            label = { Text(stringResource(R.string.cloud_sync_routing_range_end)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedButton(onClick = onDelete) {
            Text(stringResource(R.string.cloud_sync_routing_delete))
        }
    }
}

/**
 * 把 [StorageBackendConfig.rangeStart] / [StorageBackendConfig.rangeEnd] 写回具体子类。
 *
 * `when` 作用在 sealed 接口上，以后加第三个后端漏补这里会**直接编译报错**，
 * 不会静默走进 else 把时间窗口丢掉。
 */
private fun StorageBackendConfig.withRange(start: Long?, end: Long?): StorageBackendConfig =
    when (this) {
        is StorageBackendConfig.D1 -> copy(rangeStart = start, rangeEnd = end)
        is StorageBackendConfig.Supabase -> copy(rangeStart = start, rangeEnd = end)
    }

private fun StorageBackendConfig.withAlias(alias: String): StorageBackendConfig =
    when (this) {
        is StorageBackendConfig.D1 -> copy(alias = alias)
        is StorageBackendConfig.Supabase -> copy(alias = alias)
    }

private fun StorageBackendConfig.withEnabled(enabled: Boolean): StorageBackendConfig =
    when (this) {
        is StorageBackendConfig.D1 -> copy(enabled = enabled)
        is StorageBackendConfig.Supabase -> copy(enabled = enabled)
    }

/** epoch ms → `yyyy-MM-dd`（本机时区）。null 或非法值一律返空串 = 「不限」。 */
private fun epochToDateText(ms: Long?): String {
    if (ms == null) return ""
    return runCatching {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrDefault("")
}

/** `yyyy-MM-dd` → 当地零点的 epoch ms。解析不了返 null（调用方据此决定是「清空」还是「先不动」）。 */
private fun parseDateToEpoch(text: String): Long? = runCatching {
    LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()
