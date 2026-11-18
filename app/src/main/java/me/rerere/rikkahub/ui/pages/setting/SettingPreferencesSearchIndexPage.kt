package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.model.MessageSearchSettings
import me.rerere.rikkahub.data.model.SearchIndexSource
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 检索索引设置页（偏好设置子页，2026-11-18 江峰）。
 *
 * 让用户在两种消息检索索引来源间切换：
 * - App 内置 FTS5（吃本地存储，约 710MB）
 * - 外部端口服务（workspace 常驻检索服务，App 只按协议发请求，零本地索引）
 *
 * 防崩约定：选 PORT 后 App 绝不加载/建表/重建 message_fts；删了也不复活。
 */
@Composable
fun SettingPreferencesSearchIndexPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cfg = settings.messageSearch
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(fn: (MessageSearchSettings) -> MessageSearchSettings) {
        vm.updateSettings(settings.copy(messageSearch = fn(cfg)))
    }

    // 输入框本地态：避免每敲一个字就写一次 DataStore。
    var urlInput by remember(cfg.portUrl) { mutableStateOf(cfg.portUrl) }
    var tokenInput by remember(cfg.portToken) { mutableStateOf(cfg.portToken) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("检索索引设置") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp + innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp + innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "选择消息全文检索的索引来源。App 内置索引会占用约 710MB 本地存储；" +
                        "外部端口服务把索引放到 workspace 常驻进程，App 只按协议取结果，零本地索引。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item {
                CardGroup(title = { Text("索引来源") }) {
                    item(
                        headlineContent = { Text("来源选择") },
                        supportingContent = {
                            Text(
                                "内置 FTS：本地 SQLite 全文索引（占存储）。\n" +
                                    "外部端口：workspace 检索服务，App 不建任何本地索引。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SearchIndexSource.entries.forEachIndexed { index, source ->
                                    SegmentedButton(
                                        selected = cfg.source == source,
                                        onClick = { update { it.copy(source = source) } },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = SearchIndexSource.entries.size,
                                        ),
                                    ) {
                                        Text(
                                            when (source) {
                                                SearchIndexSource.FTS -> "内置 FTS"
                                                SearchIndexSource.PORT -> "外部端口"
                                            }
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            // 外部端口模式：填 URL + Token
            if (cfg.source == SearchIndexSource.PORT) {
                item {
                    CardGroup(title = { Text("外部检索服务") }) {
                        item(
                            headlineContent = { Text("服务地址") },
                            supportingContent = {
                                Text(
                                    "完整 URL，如 http://127.0.0.1:8998/search",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        item(
                            headlineContent = {
                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = {
                                        urlInput = it
                                        update { c -> c.copy(portUrl = it) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    singleLine = true,
                                    placeholder = { Text("http://127.0.0.1:8998/search") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                )
                            },
                        )
                        item(
                            headlineContent = { Text("访问 Token") },
                            supportingContent = {
                                Text(
                                    "Bearer Token，留空则不带 Authorization 头",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        item(
                            headlineContent = {
                                OutlinedTextField(
                                    value = tokenInput,
                                    onValueChange = {
                                        tokenInput = it
                                        update { c -> c.copy(portToken = it) }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    singleLine = true,
                                    placeholder = { Text("（可留空）") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                )
                            },
                        )
                    }
                }
            }

            // 内置 FTS 模式：自动重建开关
            if (cfg.source == SearchIndexSource.FTS) {
                item {
                    CardGroup(title = { Text("内置索引") }) {
                        item(
                            headlineContent = { Text("自动重建索引") },
                            supportingContent = {
                                Text(
                                    "开启：本地无索引时自动建表并重建（旧行为）。\n" +
                                        "关闭：没索引就一直不建，搜索返回空，绝不贸然创建。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = cfg.autoRebuildIndex,
                                    onCheckedChange = { v -> update { it.copy(autoRebuildIndex = v) } },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
