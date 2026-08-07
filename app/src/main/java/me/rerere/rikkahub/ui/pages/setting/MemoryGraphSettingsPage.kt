package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

/**
 * 记忆图设置总入口：把「引擎（检索行为与召回参数）」「模型与提示词」「图谱查看」三块聚合到一页，
 * 默认模型页与助手记忆页都只保留一个跳转，避免同一份配置散落多处。
 */
@Composable
fun MemoryGraphSettingsPage() {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.memory_graph_settings_title)) },
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
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_graph_settings_engine_group)) }) {
                    item(
                        onClick = { navController.navigate(Screen.MemorySearchSettings) },
                        headlineContent = { Text(stringResource(R.string.memory_search_settings_title)) },
                        supportingContent = { Text(stringResource(R.string.memory_search_settings_desc)) },
                        trailingContent = { NavArrow() },
                    )
                    item(
                        // 记忆图日志配置已统一并入「日志设置」页，此处保留入口直达
                        onClick = { navController.navigate(Screen.LogSettings) },
                        headlineContent = { Text(stringResource(R.string.log_settings_title)) },
                        supportingContent = { Text(stringResource(R.string.log_settings_desc)) },
                        trailingContent = { NavArrow() },
                    )
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_graph_settings_model_group)) }) {
                    item(
                        onClick = { navController.navigate(Screen.MemoryModelSettings) },
                        headlineContent = { Text(stringResource(R.string.memory_model_settings_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_model_page_memory_model_desc)) },
                        trailingContent = { NavArrow() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.MemoryInjectModelSettings) },
                        headlineContent = { Text(stringResource(R.string.memory_inject_settings_title)) },
                        supportingContent = { Text(stringResource(R.string.memory_inject_settings_desc)) },
                        trailingContent = { NavArrow() },
                    )
                }
            }
            item {
                CardGroup(title = { Text(stringResource(R.string.memory_graph_settings_graph_group)) }) {
                    item(
                        onClick = { navController.navigate(Screen.MemoryGraphList) },
                        headlineContent = { Text(stringResource(R.string.memory_graph_manage_title)) },
                        supportingContent = { Text(stringResource(R.string.memory_graph_manage_desc)) },
                        trailingContent = { NavArrow() },
                    )
                    item(
                        onClick = { navController.navigate(Screen.GlobalMemoryGraph) },
                        headlineContent = { Text(stringResource(R.string.memory_graph_open_global)) },
                        supportingContent = { Text(stringResource(R.string.memory_graph_open_global_desc)) },
                        trailingContent = { NavArrow() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavArrow() {
    Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
}