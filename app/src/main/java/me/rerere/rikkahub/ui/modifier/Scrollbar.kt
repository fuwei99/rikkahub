package me.rerere.rikkahub.ui.modifier

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 给 `verticalScroll` 的容器画一条极简滚动条。
 *
 * 为什么要自己画：Compose 没有内置 scrollbar，而「内容被裁掉了却看不出还能滑」是纯粹的
 * 可用性陷阱 —— 用户会以为 UI 坏了（工具卡片折叠失效那个 bug 就是这么被发现的）。
 *
 * 用法上必须放在 `verticalScroll` **之前**（即包在滚动之外），这样 thumb 画在视口坐标系里
 * 而不是跟着内容一起滚走：
 * ```
 * Modifier.heightIn(max = 220.dp).verticalScrollbar(state).verticalScroll(state)
 * ```
 * 内容没超出（`maxValue == 0`）时不画，避免短内容右侧多出一根没用的杠。
 */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    width: Dp = 3.dp,
    color: Color = Color.Gray.copy(alpha = 0.45f),
    minThumbHeight: Dp = 16.dp,
): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    // 0 = 没有可滚动距离；Int.MAX_VALUE = 尚未测量完成
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val viewport = size.height
    val total = viewport + max
    val thumbHeight = (viewport * viewport / total).coerceAtLeast(minThumbHeight.toPx())
        .coerceAtMost(viewport)
    val travel = viewport - thumbHeight
    val progress = state.value.toFloat() / max.toFloat()
    val thumbWidth = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - thumbWidth, travel * progress),
        size = Size(thumbWidth, thumbHeight),
        cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f),
    )
}
