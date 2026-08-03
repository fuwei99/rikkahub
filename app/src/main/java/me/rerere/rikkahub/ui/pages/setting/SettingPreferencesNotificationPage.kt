package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationItem
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private val SwipeCorner = 20.dp
private val SwipeInnerCorner = 4.dp

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    val context = LocalContext.current
    var scheduledItems by remember { mutableStateOf(ScheduledNotificationManager.getItems(context)) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_updates_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_updates_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUpdates,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showUpdates = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                    }
                                )
                            },
                        )
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text("AI 定时提醒管理") },
                        supportingContent = { Text("查看、关闭或删除由 AI 代理或本地创建的定时/周期系统提醒。") },
                    )
                    if (scheduledItems.isEmpty()) {
                        item(
                            headlineContent = { Text("暂无定时提醒") },
                            supportingContent = { Text("AI 可调用 send_notification 工具为你设置定时或周期性推送提醒。") },
                        )
                    }
                }
            }
            if (scheduledItems.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        scheduledItems.forEachIndexed { index, scheduledItem ->
                            key(scheduledItem.id) {
                                SwipeableScheduledNotificationItem(
                                    item = scheduledItem,
                                    isFirst = index == 0,
                                    isLast = index == scheduledItems.lastIndex,
                                    onToggle = { checked ->
                                        ScheduledNotificationManager.toggleSchedule(context, scheduledItem.id, checked)
                                        scheduledItems = ScheduledNotificationManager.getItems(context)
                                    },
                                    onDelete = {
                                        ScheduledNotificationManager.removeSchedule(context, scheduledItem.id)
                                        scheduledItems = ScheduledNotificationManager.getItems(context)
                                    },
                                )
                                if (index != scheduledItems.lastIndex) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 定时提醒条目：左右滑动可露出红色「删除」按钮，点击即删除；
 * 滑动方向决定按钮出现在左侧还是右侧。
 */
@Composable
private fun SwipeableScheduledNotificationItem(
    item: ScheduledNotificationItem,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()

    val shape = RoundedCornerShape(
        topStart = if (isFirst) SwipeCorner else SwipeInnerCorner,
        topEnd = if (isFirst) SwipeCorner else SwipeInnerCorner,
        bottomStart = if (isLast) SwipeCorner else SwipeInnerCorner,
        bottomEnd = if (isLast) SwipeCorner else SwipeInnerCorner,
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val revealOnStart = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = if (revealOnStart) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = "删除提醒",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "删除",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape),
            headlineContent = { Text("${item.title} (${item.timeFormatted})") },
            supportingContent = {
                Text("${item.message}${if (item.repeatRule != null) " [重复: ${item.repeatRule}]" else ""}")
            },
            trailingContent = {
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggle,
                )
            },
            colors = CustomColors.listItemColors,
        )
    }
}
