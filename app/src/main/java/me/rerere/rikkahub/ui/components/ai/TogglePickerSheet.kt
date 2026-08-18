package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.modifier.verticalScrollbar

/**
 * 聊天输入栏各类「开关列表」选择器的统一抽屉容器。
 *
 * 为什么存在：本地工具 / 工作区工具 / MCP / 记忆 这四个面板原来用 `BasicAlertDialog` +
 * 裸 `Column` 实现，对话框本身没有高度上限也没有滚动 —— 开关一多就顶满全屏，最后几行
 * 直接被裁掉点不到（2026-08-18 bug）。而项目里搜索 / 思维链 / 图像生成 / 模型 / 扩展面板
 * 全都是 `ModalBottomSheet`，天然带拖拽、上限高度和滚动，正确做法是复用它们那一套。
 *
 * 布局约定：
 * - 标题 + 说明固定在顶部不参与滚动，永远可见；
 * - 内容区 `weight(1f, fill = false)`：内容短就按内容高度收缩（不会像 `fillMaxHeight(0.7f)`
 *   那样给 4 行开关撑出一大片空白），内容长则吃满剩余空间并内部滚动；
 * - 右侧滚动条复用 [verticalScrollbar]，让「还能往下滑」这件事看得见。
 */
@Composable
fun TogglePickerSheet(
    title: String,
    onDismissRequest: () -> Unit,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    // weight 只有在外层给了有界高度时才封顶；再叠一个按窗口高度算的 heightIn 作兜底，
    // 避免哪天 sheet 传下无界约束时又退回「内容无限长、末尾开关点不到」的老 bug。
    val density = LocalDensity.current
    val windowHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxContentHeight = windowHeightDp * 0.7f
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // fill = false：短内容不强行撑高，长内容才封顶到剩余空间
                    .weight(1f, fill = false)
                    .heightIn(max = maxContentHeight)
                    .verticalScrollbar(scrollState)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                content()
            }
        }
    }
}
