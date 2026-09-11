package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.NetworkSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 网络层设置页（偏好设置子页）。
 *
 * 控制 OkHttp / Ktor HTTP/2 心跳频率、连接池策略与切网自动清池。
 * 所有参数**修改后需重启 App 生效**（OkHttpClient 在 DI 初始化时只构建一次）。
 * 网络参数为设备本地配置，不参与 D1 跨端同步。
 */
@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = settings.networkSettings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun update(fn: (NetworkSettings) -> NetworkSettings) {
        vm.updateSettings(settings.copy(networkSettings = fn(net)))
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("网络层设置") },
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
                    "控制 HTTP/2 心跳探针与连接池策略，用于防治移动网络 NAT 静默断连导致的发消息卡死。" +
                        "修改后需重启 App 生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                CardGroup(title = { Text("HTTP/2 心跳探针") }) {
                    netSliderItem(
                        title = "PING 间隔（秒）",
                        desc = "HTTP/2 PING 帧发送间隔。保活 NAT 映射并在死连接上快速 fail；0 = 关闭心跳（不推荐）",
                        value = net.pingIntervalSeconds,
                        range = 0f..120f,
                        suffix = " 秒",
                        onChange = { v -> update { it.copy(pingIntervalSeconds = v) } },
                    )
                }
            }
            item {
                CardGroup(title = { Text("连接池") }) {
                    netSliderItem(
                        title = "最大空闲连接数",
                        desc = "连接池保留的空闲 TCP 连接上限。多域名并发时可适当调大",
                        value = net.connPoolMaxIdle,
                        range = 1f..20f,
                        suffix = " 条",
                        onChange = { v -> update { it.copy(connPoolMaxIdle = v) } },
                    )
                    netSliderItem(
                        title = "空闲存活时长（秒）",
                        desc = "空闲连接超过此时长即回收。调大可减少 TLS 握手，但增加复用 NAT 已失效僵尸连接的风险",
                        value = net.connPoolKeepAliveSeconds,
                        range = 10f..300f,
                        suffix = " 秒",
                        onChange = { v -> update { it.copy(connPoolKeepAliveSeconds = v) } },
                    )
                }
            }
            item {
                CardGroup(title = { Text("网络切换") }) {
                    item(
                        headlineContent = { Text("切网时自动清池") },
                        supportingContent = {
                            Text(
                                "WiFi↔蜂窝切换或断网恢复时，自动驱逐连接池全部空闲连接。" +
                                    "等于自动化「手动断网重连」的操作",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = net.evictOnNetworkChange,
                                onCheckedChange = { v -> update { it.copy(evictOnNetworkChange = v) } },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 网络设置数字滑块条目，和通信设置页的 sliderItem 同款式样。 */
private fun CardGroupScope.netSliderItem(
    title: String,
    desc: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    item(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$value$suffix")
            }
        },
        supportingContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { onChange(it.toInt()) },
                        valueRange = range,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
