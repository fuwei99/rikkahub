package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.TextLayoutResult
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 仅供调试面板使用的 Markdown/LaTeX 布局取证通道。
 * 默认关闭，不影响正式聊天渲染。
 */
object MarkdownRenderTrace {
    data class ReplacementBox(
        val index: Int,
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float,
        val nextLeftPx: Float?,
        val nextTopPx: Float?,
        val nextRightPx: Float?,
        val nextBottomPx: Float?,
        val lineIndex: Int,
        val lineTopPx: Float,
        val lineBottomPx: Float,
        val lineBaselinePx: Float,
    )

    data class TextLayout(
        val text: String,
        val widthPx: Int,
        val heightPx: Int,
        val lineCount: Int,
        val replacementBoxes: List<ReplacementBox>,
    )

    data class LatexLayout(
        val latex: String,
        val requestedWidthPx: Float,
        val requestedHeightPx: Float,
        val measuredWidthPx: Int,
        val measuredHeightPx: Int,
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float,
    )

    private val textLayouts = CopyOnWriteArrayList<TextLayout>()
    private val latexLayouts = CopyOnWriteArrayList<LatexLayout>()

    @Volatile
    var enabled: Boolean = false
        private set

    fun start() {
        textLayouts.clear()
        latexLayouts.clear()
        enabled = true
    }

    fun stop() {
        enabled = false
    }

    fun clear() {
        textLayouts.clear()
        latexLayouts.clear()
    }

    fun recordTextLayout(layout: TextLayout) {
        if (enabled) textLayouts.add(layout)
    }

    fun recordLatexLayout(layout: LatexLayout) {
        if (enabled) latexLayouts.add(layout)
    }

    fun snapshot(): Pair<List<TextLayout>, List<LatexLayout>> =
        textLayouts.toList() to latexLayouts.toList()

        val boxes = buildList {
            text.forEachIndexed { index, character ->
                if (character != '\uFFFC') return@forEachIndexed
                val box = getBoundingBox(index)
                val line = getLineForOffset(index)
                val nextIndex = index + 1
                val nextBox = if (nextIndex < text.length && text[nextIndex] != '\n') {
                    getBoundingBox(nextIndex)
                } else null
                add(
                    ReplacementBox(
                        index = index,
                        leftPx = box.left,
                        topPx = box.top,
                        rightPx = box.right,
                        bottomPx = box.bottom,
                        nextLeftPx = nextBox?.left,
                        nextTopPx = nextBox?.top,
                        nextRightPx = nextBox?.right,
                        nextBottomPx = nextBox?.bottom,
                        lineIndex = line,
                        lineTopPx = getLineTop(line),
                        lineBottomPx = getLineBottom(line),
                        lineBaselinePx = getLineBaseline(line),
                    )
                )
            }
        }
        return TextLayout(
            text = text,
            widthPx = size.width,
            heightPx = size.height,
            lineCount = lineCount,
            replacementBoxes = boxes,
        )
    }

}
