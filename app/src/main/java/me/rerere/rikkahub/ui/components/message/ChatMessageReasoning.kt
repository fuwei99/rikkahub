package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.COT_STREAM_TAIL_CHARS
import me.rerere.rikkahub.utils.exceedsCotRenderBudget
import me.rerere.rikkahub.utils.extractThinkingTitle
import me.rerere.rikkahub.utils.takeTailOnLineBoundary
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

/**
 * [rememberReasoningState] 的返回值。
 *
 * @param state 卡片的展开/时长状态
 * @param loading 是否还在流式生成
 * @param overBudget 体量是否超出渲染预算 —— 为 true 时上层**必须整块折叠、一个字符都不渲染**
 */
private class ReasoningRenderState(
    val state: ReasoningState,
    val loading: Boolean,
    val overBudget: Boolean,
)

@Composable
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): ReasoningRenderState {
    val settings = LocalSettings.current
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()
    // 体量闸门：超限就整块折叠不渲染（宁可看不到，也不能卡死 / 闪退）
    val overBudget = remember(reasoning.reasoning) { reasoning.reasoning.exceedsCotRenderBudget() }

    val state = remember(reasoning.createdAt) {
        ReasoningState(
            scrollState = scrollState,
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    // key 里**不能**再放 reasoning.reasoning：那会让本效果每来一个 chunk 就被取消重起一次，
    // 而它内部又要 animateScrollTo —— 等于每个 chunk 重启一条滚动动画，永远收敛不了。
    LaunchedEffect(loading) {
        if (!loading) {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
            }
        } else if (!overBudget && !state.expandState.expanded && settings.displaySetting.showThinkingContent) {
            state.expandState = ReasoningCardState.Preview
        }
    }

    // 超预算：无条件锁死折叠
    LaunchedEffect(overBudget) {
        if (overBudget) state.expandState = ReasoningCardState.Collapsed
    }

    // 流式贴底跟随：只跟随「可滚动上限」的变化，无动画、不重启任何东西
    LaunchedEffect(loading, overBudget) {
        if (!loading || overBudget) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    return ReasoningRenderState(state, loading, overBudget)
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
    loading: Boolean,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val reasoningTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily,
    )

    // 只影响「怎么画」的一步变形（原文与存储一个字节都不动）：
    // 流式预览只取尾部窗口 —— 卡片固定 100dp 高且自动贴底，渲染全篇纯浪费。
    // 2026-09-23：碎片行归一化（把短行压成同一行）已从渲染路径移除 —— 正常思维链的换行
    // **一个都不许动**；病态场景（高速短行把块数顶爆）的成本改由尾部窗口 + 体量闸门去压。
    val renderText = remember(reasoning.reasoning, loading, isPreview) {
        if (loading && isPreview) {
            reasoning.reasoning.takeTailOnLineBoundary(COT_STREAM_TAIL_CHARS)
        } else {
            reasoning.reasoning
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to Color.Black,
                                    (1 - fadeHeight / size.height) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        val reasoningContent = @Composable {
            MarkdownBlock(
                content = renderText.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
                ),
                style = reasoningTextStyle,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 流式生成期间不启用 SelectionContainer，避免 selectable 列表并发修改导致的
        // ConcurrentModificationException（详见 ChatMessage.kt 文本块同样处理）。
        if (loading) {
            reasoningContent()
        } else {
            SelectionContainer {
                reasoningContent()
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val render = rememberReasoningState(reasoning)
    val state = render.state
    val loading = render.loading
    val overBudget = render.overBudget
    // 超预算时连标题都别去扫：那也是一次 O(行数) 的全盘扫描
    val thinkingTitle = remember(reasoning.reasoning, overBudget) {
        if (overBudget) null else reasoning.reasoning.extractThinkingTitle()
    }
    val showThinkingTitle = loading && thinkingTitle != null
    val chatFontFamily = LocalTextStyle.current.fontFamily

    val reasoningContent: @Composable () -> Unit = {
        ReasoningContent(
            reasoning = reasoning,
            assistant = assistant,
            expandState = state.expandState,
            scrollState = state.scrollState,
            fadeHeight = fadeHeight,
            loading = loading,
        )
    }

    ControlledChainOfThoughtStep(
        expanded = !overBudget && state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { if (!overBudget) state.onExpandedChange(it, loading) },
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            if (overBudget) {
                // 体量超预算：整块折叠、不给展开（展开 = 当场渲染十几万个块 = 闪退）。
                // 原文没丢，仍在消息里（导出/复制/落库都不受影响）。
                Text(
                    text = "思维链过大，已折叠不渲染（${reasoning.reasoning.length / 1024} KB）",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else if (showThinkingTitle) {
                ReasoningTitle(title = thinkingTitle!!)
            } else {
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            if (showThinkingTitle && state.duration > 0.seconds) {
                Text(
                    text = state.duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        // 超预算：contentVisible=false + content=null，这一整块根本不会进组合树
        contentVisible = !overBudget && state.expandState != ReasoningCardState.Collapsed,
        content = if (overBudget) null else reasoningContent,
    )
}


@Composable
private fun ReasoningTitle(title: String) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
