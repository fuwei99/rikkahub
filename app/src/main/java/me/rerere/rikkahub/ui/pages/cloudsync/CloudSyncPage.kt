package me.rerere.rikkahub.ui.pages.cloudsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 云同步入口页（Step H，2026-09-21）。
 *
 * 「数据设置 → 云同步」的二级页，只做分流：
 * - 资产同步（R2）：媒体对象存储
 * - 数据库同步（D1 / Supabase）：会话 / settings / bundles 文本数据
 *
 * 两者刻意分开：开关、凭证、端点互不影响 —— 换数据库后端不该动到 R2 的 key。
 */
@Composable
fun CloudSyncPage() {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.cloud_sync_page_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(
                    PaddingValues(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                        start = contentPadding.calculateStartPadding(layoutDirection) + 8.dp,
                        end = contentPadding.calculateEndPadding(layoutDirection) + 8.dp,
                    )
                ),
        ) {
            CardGroup {
                item(
                    onClick = { navController.navigate(Screen.CloudSyncAssets) },
                    leadingContent = { Icon(HugeIcons.ImageUpload, null) },
                    supportingContent = { Text(stringResource(R.string.cloud_sync_page_assets_desc)) },
                    headlineContent = { Text(stringResource(R.string.cloud_sync_page_assets)) },
                )
                item(
                    onClick = { navController.navigate(Screen.CloudSyncDatabase) },
                    leadingContent = { Icon(HugeIcons.Database02, null) },
                    supportingContent = { Text(stringResource(R.string.cloud_sync_page_database_desc)) },
                    headlineContent = { Text(stringResource(R.string.cloud_sync_page_database)) },
                )
            }
        }
    }
}
