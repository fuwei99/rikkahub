package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.isSpecified
import java.util.concurrent.atomic.AtomicLong

/**
 * 运行时布局取证：只在调试面板打开期间采集。
 *
 * 设计约束（这几条是上一版日志踩过的坑，别再改回去）：
 * 1. **所有坐标统一到 root 坐标系**，再统一减去截图图层原点 [captureOrigin]，
 *    因此日志里的坐标可以直接和截图像素对齐。
 * 2. **同一个实例只保留最后一帧**（按 instanceId 去重），
 *    避免上一版 9 个公式写出 700 条重复记录、且混入首帧未测量的脏数据。
 * 3. 行内公式占位框**必须**取 [TextLayoutResult.placeholderRects]。
 *    上一版靠扫描 '\uFFFC' 判定，而 appendInlineContent 写入的替换文本是 "[Latex]"，
 *    U+FFFC 不会出现在 annotatedString 里，导致占位框坐标一条都没记下来。
 */
object MarkdownRenderTrace {
    private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

    /** 行内占位框（公式）与其后继字符、所在行的实测坐标。 */
    data class PlaceholderBox(
        val order: Int,
        val id: String,
        val spanStart: Int,
        val spanEnd: Int,
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float,
        val nextChar: String?,
        val nextLeftPx: Float?,
        val nextTopPx: Float?,
        val nextRightPx: Float?,
        val nextBottomPx: Float?,
        val lineIndex: Int,
        val lineTopPx: Float,
        val lineBottomPx: Float,
        val lineBaselinePx: Float,
    )

    data class LineMetrics(
        val index: Int,
        val topPx: Float,
        val bottomPx: Float,
        val baselinePx: Float,
        val leftPx: Float,
        val rightPx: Float,
    )

    data class TextLayout(
        val id: Long,
        val text: String,
        val widthPx: Int,
        val heightPx: Int,
        val lineCount: Int,
        val fontSizePx: Float,
        val lineHeightPx: Float,
        val lineHeightSpecified: Boolean,
        val originXPx: Float,
        val originYPx: Float,
        val placeholders: List<PlaceholderBox>,
        val lines: List<LineMetrics>,
    )

    data class LatexLayout(
        val id: Long,
        val latex: String,
        val fontSizePx: Float,
        val requestedWidthPx: Float,
        val requestedHeightPx: Float,
        val requestedBaselinePx: Float,
        val contentWidthPx: Float,
        val contentHeightPx: Float,
        val measuredWidthPx: Int,
        val measuredHeightPx: Int,
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float,
    )

    private val idSeq = AtomicLong(0L)
    private val textLayouts = LinkedHashMap<Long, TextLayout>()
    private val textOrigins = LinkedHashMap<Long, Offset>()
    private val latexLayouts = LinkedHashMap<Long, LatexLayout>()

    /** 截图图层在 root 坐标系中的原点；日志坐标会减掉它，从而与截图像素一一对应。 */
    @Volatile
    var captureOrigin: Offset = Offset.Zero

    @Volatile
    var enabled: Boolean = false
        private set

    fun newId(): Long = idSeq.incrementAndGet()

    fun start() {
        clear()
        enabled = true
    }

    fun stop() {
        enabled = false
    }

    @Synchronized
    fun clear() {
        textLayouts.clear()
        textOrigins.clear()
        latexLayouts.clear()
    }

    @Synchronized
    fun recordTextLayout(layout: TextLayout) {
        if (!enabled) return
        textLayouts[layout.id] = layout
    }

    @Synchronized
    fun recordTextOrigin(id: Long, origin: Offset) {
        if (!enabled) return
        textOrigins[id] = origin
    }

    @Synchronized
    fun recordLatexLayout(layout: LatexLayout) {
        if (!enabled) return
        latexLayouts[layout.id] = layout
    }

    /**
     * 快照。所有坐标已换算成「截图坐标系」（root 坐标 - captureOrigin），
     * Text 内部坐标额外叠加了该 Text 自身原点，因此公式占位框与公式实际绘制框可以直接相减。
     */
    @Synchronized
    fun snapshot(): Pair<List<TextLayout>, List<LatexLayout>> {
        val origin = captureOrigin
        val texts = textLayouts.values.map { layout ->
            val self = textOrigins[layout.id] ?: Offset.Zero
            val dx = self.x - origin.x
            val dy = self.y - origin.y
            layout.copy(
                originXPx = dx,
                originYPx = dy,
                placeholders = layout.placeholders.map { box ->
                    box.copy(
                        leftPx = box.leftPx + dx,
                        topPx = box.topPx + dy,
                        rightPx = box.rightPx + dx,
                        bottomPx = box.bottomPx + dy,
                        nextLeftPx = box.nextLeftPx?.plus(dx),
                        nextTopPx = box.nextTopPx?.plus(dy),
                        nextRightPx = box.nextRightPx?.plus(dx),
                        nextBottomPx = box.nextBottomPx?.plus(dy),
                        lineTopPx = box.lineTopPx + dy,
                        lineBottomPx = box.lineBottomPx + dy,
                        lineBaselinePx = box.lineBaselinePx + dy,
                    )
                },
                lines = layout.lines.map { line ->
                    line.copy(
                        topPx = line.topPx + dy,
                        bottomPx = line.bottomPx + dy,
                        baselinePx = line.baselinePx + dy,
                        leftPx = line.leftPx + dx,
                        rightPx = line.rightPx + dx,
                    )
                },
            )
        }
        val latex = latexLayouts.values.map { layout ->
            layout.copy(
                leftPx = layout.leftPx - origin.x,
                topPx = layout.topPx - origin.y,
                rightPx = layout.rightPx - origin.x,
                bottomPx = layout.bottomPx - origin.y,
            )
        }
        return texts to latex
    }

    /**
     * 从真实的 [TextLayoutResult] 提取占位框。
     * 占位框顺序与 annotatedString 中 inlineContent 注解顺序一致，据此把框映射回公式 id。
     */
    fun TextLayoutResult.toMarkdownTrace(
        id: Long,
        annotatedString: AnnotatedString,
    ): TextLayout {
        val text = annotatedString.text
        val annotations = annotatedString
            .getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
            .sortedBy { it.start }
        val rects = placeholderRects
        val boxes = rects.mapIndexedNotNull { index, rect ->
            if (rect == null) return@mapIndexedNotNull null
            val annotation = annotations.getOrNull(index)
            val spanStart = annotation?.start ?: -1
            val spanEnd = annotation?.end ?: -1
            val line = if (spanStart in text.indices) getLineForOffset(spanStart) else 0
            val nextIndex = spanEnd
            val hasNext = nextIndex in text.indices && text[nextIndex] != '\n'
            val nextBox = if (hasNext) runCatching { getBoundingBox(nextIndex) }.getOrNull() else null
            PlaceholderBox(
                order = index,
                id = annotation?.item ?: "?",
                spanStart = spanStart,
                spanEnd = spanEnd,
                leftPx = rect.left,
                topPx = rect.top,
                rightPx = rect.right,
                bottomPx = rect.bottom,
                nextChar = if (hasNext) text[nextIndex].toString() else null,
                nextLeftPx = nextBox?.left,
                nextTopPx = nextBox?.top,
                nextRightPx = nextBox?.right,
                nextBottomPx = nextBox?.bottom,
                lineIndex = line,
                lineTopPx = getLineTop(line),
                lineBottomPx = getLineBottom(line),
                lineBaselinePx = getLineBaseline(line),
            )
        }
        val lines = (0 until lineCount).map { index ->
            LineMetrics(
                index = index,
                topPx = getLineTop(index),
                bottomPx = getLineBottom(index),
                baselinePx = getLineBaseline(index),
                leftPx = getLineLeft(index),
                rightPx = getLineRight(index),
            )
        }
        val style = layoutInput.style
        val fontSizePx = with(layoutInput.density) {
            if (style.fontSize.isSpecified) style.fontSize.toPx() else -1f
        }
        val lineHeightPx = with(layoutInput.density) {
            if (style.lineHeight.isSpecified) style.lineHeight.toPx() else -1f
        }
        return TextLayout(
            id = id,
            text = text,
            widthPx = size.width,
            heightPx = size.height,
            lineCount = lineCount,
            fontSizePx = fontSizePx,
            lineHeightPx = lineHeightPx,
            lineHeightSpecified = style.lineHeight.isSpecified,
            originXPx = 0f,
            originYPx = 0f,
            placeholders = boxes,
            lines = lines,
        )
    }
}
