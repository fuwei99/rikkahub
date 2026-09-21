package me.rerere.rikkahub.ui.pages.cloudsync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.sync.backend.StorageBackendConfig
import me.rerere.rikkahub.data.sync.backend.StorageBackendRouter
import me.rerere.rikkahub.data.sync.backend.epochToDateText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.tabs.CloudSyncD1Tab
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 数据库同步 · 渠道列表（多后端 · Step I-4，2026-09-21）。
 *
 * ## 它解决什么
 *
 * 之前是「一个后端一个写死的 tab」：D1 一个、Supabase 一个。而方案要求
 * 「Supabase 满了就再挂一个账号，手上有 20 个」—— 多实例是常态，
 * 写死的 tab 根本表达不了，且 `filterIsInstance().firstOrNull()` 只能编辑第一个。
 *
 * 现在改成 R2 同款结构：**本页只列渠道，点进去才是配置页**。
 * 列表刻意只显示三样东西 —— 名字、描述、时间段 —— 因为它回答的是唯一一个问题：
 * **「这段时间的数据存在哪个库」**。凭据、连通性、开关都在详情页。
 *
 * ## 旧配置桥（为什么会有第四张卡）
 *
 * 设备上真正在工的可能还是 legacy `d1Config`（`storage_backends.json` 未必存在，
 * 代码里也没有 `d1Config -> backends` 的迁移）。若只列 `backends`，
 * D1 会从界面上凭空消失、连凭据都没地方改 —— 那是比 UI 难看严重得多的事。
 *
 * 所以这里补一张「旧 D1 配置」卡：**只读路由信息，点进去仍然是原来那个
 * 直接编辑 `d1Config` 的页面**。零迁移、零漂移：不把 d1Config 搬进 backends，
 * 就不会出现「两个地方都能改同一份凭据」的分叉。想正式纳入时再走「添加 D1」。
 */
@Composable
fun CloudSyncDatabasePage(vm: BackupVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val backends = settings.backends
    val d1 = settings.d1Config
    val legacyCoversAll = d1.hasRequiredFields
    val warnings = remember(backends, legacyCoversAll) {
        StorageBackendRouter.validate(backends, hasLegacyFallback = legacyCoversAll)
    }
    val latest = remember(backends) { StorageBackendRouter.latestWriteTarget(backends) }

    val legacyShown = (d1.hasRequiredFields || d1.enabled) &&
        backends.none { it.id == StorageBackendConfig.LEGACY_D1_BACKEND_ID }

    fun add(cfg: StorageBackendConfig) {
        vm.updateBackends(backends + cfg)
        navController.navigate(Screen.CloudSyncBackend(cfg.id))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.cloud_sync_page_database)) },
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

            // 体检：重复 id / 时间重叠 / 时间空档。
            // 空档最要命 —— 那段时间的会话没有后端认领，会静默不同步，必须显式喊出来。
            warnings.forEach { w ->
                Text(
                    text = "\u26a0 $w",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (legacyShown) {
                ChannelCard(
                    title = stringResource(R.string.cloud_sync_legacy_d1_title),
                    description = stringResource(R.string.cloud_sync_legacy_d1_desc),
                    window = windowText(null, null),
                    onClick = { navController.navigate(Screen.CloudSyncLegacyD1) },
                )
            }

            backends.forEach { cfg ->
                ChannelCard(
                    title = cfg.alias.ifBlank { cfg.typeName },
                    description = describe(cfg),
                    window = windowText(cfg.rangeStart, cfg.rangeEnd),
                    onClick = { navController.navigate(Screen.CloudSyncBackend(cfg.id)) },
                )
            }

            if (!legacyShown && backends.isEmpty()) {
                Text(
                    text = stringResource(R.string.cloud_sync_routing_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { add(StorageBackendConfig.D1(alias = "D1")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cloud_sync_routing_add_d1))
                }
                OutlinedButton(
                    onClick = { add(StorageBackendConfig.Supabase(alias = "Supabase")) },
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
}

/**
 * 旧 `d1Config` 的配置页（legacy 桥）。
 *
 * 内容原样复用 [CloudSyncD1Tab]，**一个字符都没改** —— 它仍然直接读写 `d1Config`。
 * 这样做是为了让「旧配置」和「新渠道」在过渡期各管各的，绝不出现两处能改同一份凭据。
 * 等新渠道体系验熟之后，这个页面连同 `d1Config` 一起拆。
 */
@Composable
fun CloudSyncLegacyD1Page(vm: BackupVM = koinViewModel()) {
    CloudSyncD1Tab(vm = vm)
}

/** 渠道卡：**只显示名字 / 描述 / 时间段**，其余全部留给详情页。 */
@Composable
private fun ChannelCard(
    title: String,
    description: String,
    window: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = window,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 描述行：类型 · 是否配齐 · 收不收新推送。不含任何凭据。 */
@Composable
private fun describe(cfg: StorageBackendConfig): String {
    val type = stringResource(
        if (cfg is StorageBackendConfig.D1) R.string.cloud_sync_backend_d1
        else R.string.cloud_sync_backend_supabase
    )
    val status = stringResource(
        if (cfg.isConfigured) R.string.cloud_sync_status_configured
        else R.string.cloud_sync_status_not_configured
    )
    val mode = stringResource(
        if (cfg.enabled) R.string.cloud_sync_routing_enabled
        else R.string.cloud_sync_routing_disabled_but_readable
    )
    return "$type · $status · $mode"
}

/** 时间段：`起 → 止`，两端留空显示成「不限」。 */
@Composable
private fun windowText(start: Long?, end: Long?): String {
    val unbounded = stringResource(R.string.cloud_sync_routing_unbounded)
    return epochToDateText(start).ifBlank { unbounded } + " → " + epochToDateText(end).ifBlank { unbounded }
}
