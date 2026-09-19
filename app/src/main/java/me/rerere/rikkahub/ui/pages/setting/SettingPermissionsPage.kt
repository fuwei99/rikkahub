package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 权限总控页（2026-09-19）。
 *
 * 把 Rikkahub 用得上的权限全收在一页，每行显示当前状态，点一下直接跳去授权。
 * 省得每次都在系统设置的七八个页面里翻。
 *
 * 三种点击行为：
 * - 运行时权限（相机/录音/定位/通知）→ 直接弹系统授权框
 * - 特殊/厂商权限 → 跳对应设置页；跳不动就兜底到应用详情页
 * - 无需授权项（剪贴板）→ 不可点，只作说明
 *
 * 状态每次 `ON_RESUME` 重算 —— 从系统设置页返回时会自动刷新。
 */
@Composable
fun SettingPermissionsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 每次回到前台自增，用来强制重算权限状态
    var refreshKey by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

    val entries = remember(refreshKey) { AppPermissionCatalog.build(context) }
    val grouped = remember(entries) { entries.groupBy { it.group } }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("权限管理") },
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
                    "点任意一行直接跳去授权。厂商私有权限（后台弹出界面、锁屏显示、链式启动等）" +
                        "系统没有公开查询接口，状态只能标成「需手动确认」，跳过去自己看一眼。\n" +
                        "从系统设置返回时会自动刷新状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            grouped.forEach { (group, list) ->
                item(key = "group_$group") {
                    CardGroup(title = { Text(group) }) {
                        list.forEach { entry ->
                            val click: (() -> Unit)? = entry.action.toClick(
                                context = context,
                                requestRuntime = { perm -> runtimeLauncher.launch(perm) },
                            )
                            item(
                                onClick = click,
                                leadingContent = { StatusDot(entry.status) },
                                headlineContent = { Text(entry.title) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            entry.desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            entry.statusText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = entry.status.dotColor(),
                                        )
                                    }
                                },
                                trailingContent = {
                                    if (click != null) {
                                        Text(
                                            "去授权",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 状态圆点 */
@Composable
private fun StatusDot(status: PermStatus) {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(10.dp)
            .background(status.dotColor(), CircleShape),
    )
}

private fun PermStatus.dotColor(): Color = when (this) {
    PermStatus.GRANTED -> Color(0xFF34C759)
    PermStatus.DENIED -> Color(0xFFFF3B30)
    PermStatus.UNKNOWN -> Color(0xFFFF9500)
    PermStatus.NOT_APPLICABLE -> Color(0xFF8E8E93)
}

/**
 * 把 [PermAction] 翻译成 `item` 的 onClick。
 *
 * 跳转失败（厂商组件不存在、被 ROM 改名）一律兜底到应用详情页 —— 总比点了没反应强。
 * 返回 null 表示这行不可点。
 */
private fun PermAction.toClick(
    context: Context,
    requestRuntime: (String) -> Unit,
): (() -> Unit)? = when (this) {
    is PermAction.Runtime -> ({ requestRuntime(permission) })
    is PermAction.Jump -> ({
        val intent = build(context)
        val jumped = intent != null && runCatching { context.startActivity(intent) }.isSuccess
        if (!jumped) {
            runCatching { context.startActivity(AppPermissionCatalog.appDetailsIntent(context)) }
        }
        Unit
    })
    PermAction.None -> null
}
