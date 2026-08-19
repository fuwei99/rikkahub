package me.rerere.rikkahub.ui.pages.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.ui.components.richtext.MarkdownRenderTrace
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Markdown 渲染取证报告。
 *
 * 与旧版的根本区别：**日志只写渲染后的实测数据**。
 * - 公式尺寸取自运行时 [MarkdownRenderTrace.LatexLayout]（真实 Canvas 布局），不再另起一套 regex + 重新测量。
 * - 占位框取自真实 TextLayoutResult.placeholderRects。
 * - 附带同一帧的截图 screenshot.png，并额外输出带网格与 xy 轴刻度的 screenshot-grid.png，
 *   截图坐标系与日志坐标系完全一致（都以采集图层左上角为原点），可以直接按坐标在图上量。
 */
object MarkdownRenderReport {

    private const val GRID_MARGIN = 64
    private const val MINOR_STEP = 20
    private const val MAJOR_STEP = 100

    data class Result(val folder: File, val summary: String)

    fun save(
        context: Context,
        markdown: String,
        density: Density,
        fontSize: TextUnit,
        screenshot: ImageBitmap?,
    ): Result {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val folder = File(AppPaths.filesDir(context), "logs/markdown-render/$timestamp")
        folder.mkdirs()
        File(folder, "input.md").writeText(markdown)

        val (textLayouts, latexLayouts) = MarkdownRenderTrace.snapshot()

        var shotFile: File? = null
        var gridFile: File? = null
        var shotWidth = 0
        var shotHeight = 0
        screenshot?.let { image ->
            val bitmap = image.asAndroidBitmap()
            shotWidth = bitmap.width
            shotHeight = bitmap.height
            shotFile = File(folder, "screenshot.png").also { file ->
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val annotated = annotate(bitmap, textLayouts, latexLayouts)
            gridFile = File(folder, "screenshot-grid.png").also { file ->
                FileOutputStream(file).use { annotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            annotated.recycle()
        }

        val overlaps = detectOverlaps(textLayouts, latexLayouts)

        val trace = buildString {
            appendLine("# Markdown 渲染取证日志（渲染后实测）")
            appendLine()
            appendLine("## Environment")
            appendLine("- timestamp: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())}")
            appendLine("- appVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("- android: ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
            appendLine("- density: ${density.density}")
            appendLine("- fontScale: ${density.fontScale}")
            appendLine("- chatFontSize: $fontSize (= ${with(density) { fontSize.toPx().fmt() }} px)")
            appendLine("- renderingPath: ChatMessage -> MarkdownBlock -> Text(inlineContent)")
            appendLine("- coordinateSpace: 采集图层左上角为原点，与 screenshot.png 像素 1:1")
            appendLine("- screenshot: ${shotFile?.name ?: "none"} (${shotWidth}x${shotHeight})")
            appendLine("- screenshotGrid: ${gridFile?.name ?: "none"} (minor=${MINOR_STEP}px, major=${MAJOR_STEP}px, 轴偏移=${GRID_MARGIN}px)")
            appendLine()

            appendLine("## Overlap Findings")
            if (overlaps.isEmpty()) appendLine("- 未检测到重叠。") else overlaps.forEach { appendLine("- $it") }
            appendLine()

            appendLine("## Text Layouts (实测)")
            if (textLayouts.isEmpty()) appendLine("No TextLayoutResult captured.")
            textLayouts.forEachIndexed { index, layout ->
                appendLine("### Text ${index + 1} (traceId=${layout.id})")
                appendLine("- origin: ${layout.originXPx.fmt()},${layout.originYPx.fmt()}")
                appendLine("- size: ${layout.widthPx}x${layout.heightPx}")
                appendLine("- lineCount: ${layout.lineCount}")
                appendLine("- style.fontSizePx: ${layout.fontSizePx.fmt()}")
                appendLine("- style.lineHeightPx: ${layout.lineHeightPx.fmt()} (specified=${layout.lineHeightSpecified})")
                appendLine("- text: `${layout.text.replace("\n", "\\n").replace("`", "\\`")}`")
                layout.lines.forEach { line ->
                    appendLine(
                        "- line[${line.index}]: top=${line.topPx.fmt()} baseline=${line.baselinePx.fmt()} " +
                            "bottom=${line.bottomPx.fmt()} left=${line.leftPx.fmt()} right=${line.rightPx.fmt()} " +
                            "height=${(line.bottomPx - line.topPx).fmt()}"
                    )
                }
                layout.placeholders.forEach { box ->
                    appendLine("- placeholder[${box.order}].id: `${box.id.replace("`", "\\`")}`")
                    appendLine("- placeholder[${box.order}].span: ${box.spanStart}..${box.spanEnd}")
                    appendLine(
                        "- placeholder[${box.order}].box: ${box.leftPx.fmt()},${box.topPx.fmt()} - " +
                            "${box.rightPx.fmt()},${box.bottomPx.fmt()} (${(box.rightPx - box.leftPx).fmt()}x${(box.bottomPx - box.topPx).fmt()})"
                    )
                    appendLine("- placeholder[${box.order}].line: ${box.lineIndex} top=${box.lineTopPx.fmt()} baseline=${box.lineBaselinePx.fmt()} bottom=${box.lineBottomPx.fmt()}")
                    if (box.nextChar != null) {
                        appendLine(
                            "- placeholder[${box.order}].nextChar: `${box.nextChar}` box=${box.nextLeftPx?.fmt()},${box.nextTopPx?.fmt()} - " +
                                "${box.nextRightPx?.fmt()},${box.nextBottomPx?.fmt()}"
                        )
                        val gap = (box.nextLeftPx ?: 0f) - box.rightPx
                        appendLine("- placeholder[${box.order}].gapToNextCharPx: ${gap.fmt()}")
                    } else {
                        appendLine("- placeholder[${box.order}].nextChar: none")
                    }
                }
            }
            appendLine()

            appendLine("## Latex Layouts (实测绘制)")
            if (latexLayouts.isEmpty()) appendLine("No Latex layout captured.")
            latexLayouts.forEachIndexed { index, layout ->
                appendLine("### Latex ${index + 1} (traceId=${layout.id})")
                appendLine("- latex: `${layout.latex.replace("`", "\\`")}`")
                appendLine("- fontSizePx: ${layout.fontSizePx.fmt()}")
                appendLine("- measured(实际布局): ${layout.measuredWidthPx}x${layout.measuredHeightPx}")
                appendLine("- requested(测量器): ${layout.requestedWidthPx.fmt()}x${layout.requestedHeightPx.fmt()} baseline=${layout.requestedBaselinePx.fmt()}")
                appendLine("- content(去 padding): ${layout.contentWidthPx.fmt()}x${layout.contentHeightPx.fmt()}")
                appendLine("- drawBox: ${layout.leftPx.fmt()},${layout.topPx.fmt()} - ${layout.rightPx.fmt()},${layout.bottomPx.fmt()}")
                appendLine("- delta(measured-requested): dW=${(layout.measuredWidthPx - layout.requestedWidthPx).fmt()} dH=${(layout.measuredHeightPx - layout.requestedHeightPx).fmt()}")
                val box = matchPlaceholder(layout, textLayouts)
                if (box == null) {
                    appendLine("- placeholderMatch: none")
                } else {
                    appendLine("- placeholderMatch: order=${box.order} box=${box.leftPx.fmt()},${box.topPx.fmt()} - ${box.rightPx.fmt()},${box.bottomPx.fmt()}")
                    appendLine("- overflowLeftPx: ${(box.leftPx - layout.leftPx).fmt()}")
                    appendLine("- overflowRightPx: ${(layout.rightPx - box.rightPx).fmt()}")
                    appendLine("- overflowTopPx: ${(box.topPx - layout.topPx).fmt()}")
                    appendLine("- overflowBottomPx: ${(layout.bottomPx - box.bottomPx).fmt()}")
                }
            }
        }
        File(folder, "render-trace.md").writeText(trace)

        val summary = buildString {
            append(folder.name)
            append("：text=${textLayouts.size} latex=${latexLayouts.size}")
            append(" 截图=${if (shotFile != null) "有" else "无"}")
            append(" 重叠=${overlaps.size}")
        }
        return Result(folder, summary)
    }

    /** 公式绘制框与占位框按几何中心就近匹配（同一帧、同坐标系，稳定可靠）。 */
    private fun matchPlaceholder(
        latex: MarkdownRenderTrace.LatexLayout,
        texts: List<MarkdownRenderTrace.TextLayout>,
    ): MarkdownRenderTrace.PlaceholderBox? {
        val cx = (latex.leftPx + latex.rightPx) / 2f
        val cy = (latex.topPx + latex.bottomPx) / 2f
        return texts.flatMap { it.placeholders }.minByOrNull { box ->
            val bx = (box.leftPx + box.rightPx) / 2f
            val by = (box.topPx + box.bottomPx) / 2f
            (bx - cx) * (bx - cx) + (by - cy) * (by - cy)
        }
    }

    /** 只报告真正的几何重叠：公式绘制框 vs 后继字符框 / 相邻行文字行盒。 */
    private fun detectOverlaps(
        texts: List<MarkdownRenderTrace.TextLayout>,
        latexLayouts: List<MarkdownRenderTrace.LatexLayout>,
    ): List<String> = buildList {
        latexLayouts.forEach { latex ->
            val box = matchPlaceholder(latex, texts) ?: return@forEach
            if (box.nextLeftPx != null && latex.rightPx > box.nextLeftPx + 0.5f) {
                add(
                    "水平重叠：公式 `${latex.latex}` 右边界 ${latex.rightPx.fmt()} 越过后继字符 `${box.nextChar}` 左边界 " +
                        "${box.nextLeftPx.fmt()}，重叠 ${(latex.rightPx - box.nextLeftPx).fmt()}px"
                )
            }
            if (latex.rightPx > box.rightPx + 0.5f) {
                add("公式 `${latex.latex}` 绘制宽度超出占位框右侧 ${(latex.rightPx - box.rightPx).fmt()}px")
            }
            texts.flatMap { it.lines }.forEach { line ->
                if (line.index == box.lineIndex) return@forEach
                val overlap = minOf(latex.bottomPx, line.bottomPx) - max(latex.topPx, line.topPx)
                if (overlap > 0.5f && line.index == box.lineIndex + 1) {
                    add(
                        "垂直重叠：公式 `${latex.latex}` 底部 ${latex.bottomPx.fmt()} 压入下一行(line ${line.index} " +
                            "top=${line.topPx.fmt()})，重叠 ${overlap.fmt()}px"
                    )
                }
            }
        }
    }

    /** 画网格 + xy 轴刻度数字。原图整体右下平移 GRID_MARGIN，坐标 = 图上坐标 - GRID_MARGIN。 */
    private fun annotate(
        source: Bitmap,
        texts: List<MarkdownRenderTrace.TextLayout>,
        latexLayouts: List<MarkdownRenderTrace.LatexLayout>,
    ): Bitmap {
        val out = Bitmap.createBitmap(
            source.width + GRID_MARGIN + 16,
            source.height + GRID_MARGIN + 16,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        canvas.drawColor(AndroidColor.WHITE)
        canvas.drawBitmap(source, GRID_MARGIN.toFloat(), GRID_MARGIN.toFloat(), null)

        val minor = Paint().apply {
            color = AndroidColor.argb(40, 0, 128, 255)
            strokeWidth = 1f
        }
        val major = Paint().apply {
            color = AndroidColor.argb(110, 0, 96, 220)
            strokeWidth = 1.6f
        }
        val axis = Paint().apply {
            color = AndroidColor.argb(220, 20, 20, 20)
            strokeWidth = 2f
        }
        val label = Paint().apply {
            color = AndroidColor.rgb(20, 20, 20)
            textSize = 20f
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val placeholderPaint = Paint().apply {
            color = AndroidColor.rgb(0, 160, 0)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val latexPaint = Paint().apply {
            color = AndroidColor.rgb(220, 30, 30)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val baselinePaint = Paint().apply {
            color = AndroidColor.argb(160, 255, 140, 0)
            strokeWidth = 1.5f
        }

        val left = GRID_MARGIN.toFloat()
        val top = GRID_MARGIN.toFloat()
        val right = left + source.width
        val bottom = top + source.height

        var x = 0
        while (x <= source.width) {
            val px = left + x
            val isMajor = x % MAJOR_STEP == 0
            canvas.drawLine(px, top, px, bottom, if (isMajor) major else minor)
            if (isMajor) {
                canvas.drawLine(px, top - 8f, px, top, axis)
                canvas.save()
                canvas.rotate(-90f, px, top - 12f)
                canvas.drawText(x.toString(), px + 4f, top - 12f, label)
                canvas.restore()
            }
            x += MINOR_STEP
        }
        var y = 0
        while (y <= source.height) {
            val py = top + y
            val isMajor = y % MAJOR_STEP == 0
            canvas.drawLine(left, py, right, py, if (isMajor) major else minor)
            if (isMajor) {
                canvas.drawLine(left - 8f, py, left, py, axis)
                canvas.drawText(y.toString(), 4f, py + 7f, label)
            }
            y += MINOR_STEP
        }
        canvas.drawLine(left, top, right, top, axis)
        canvas.drawLine(left, top, left, bottom, axis)
        canvas.drawText("x→ / y↓ 单位: px (原点=采集图层左上角)", left + 4f, bottom + 26f, label)

        texts.forEach { layout ->
            layout.lines.forEach { line ->
                canvas.drawLine(
                    left + line.leftPx, top + line.baselinePx,
                    left + line.rightPx, top + line.baselinePx, baselinePaint,
                )
            }
            layout.placeholders.forEach { box ->
                canvas.drawRect(
                    left + box.leftPx, top + box.topPx,
                    left + box.rightPx, top + box.bottomPx, placeholderPaint,
                )
                canvas.drawText("P${box.order}", left + box.leftPx + 2f, top + box.topPx - 3f, label)
            }
        }
        latexLayouts.forEach { layout ->
            canvas.drawRect(
                left + layout.leftPx, top + layout.topPx,
                left + layout.rightPx, top + layout.bottomPx, latexPaint,
            )
        }
        canvas.drawText(
            "绿=占位框 红=公式实际绘制框 橙=文字基线",
            left + 4f, bottom + 48f, label,
        )
        return out
    }

    private fun Float.fmt(): String =
        if (this.isNaN()) "NaN" else "%.2f".format(Locale.US, this)
}
