package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.uuid.Uuid

private enum class ImageEditTool {
    Draw,
    Arrow,
    Text,
    Crop,
}

private enum class CropDragMode {
    New,
    Move,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

private data class ImageStroke(val points: List<Offset>, val color: Color = Color.Red, val width: Float = 8f)
private data class ImageArrow(val start: Offset, val end: Offset, val color: Color = Color.Red, val width: Float = 8f)
private data class ImageTextMark(val text: String, val position: Offset, val color: Color = Color.Red, val size: Float = 48f)
private data class ImageCanvasLayout(val scale: Float, val left: Float, val top: Float, val width: Float, val height: Float)

@Composable
internal fun ImageAttachmentEditorDialog(
    imageUrl: String,
    onDismiss: () -> Unit,
    onSave: (Uri) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var tool by remember { mutableStateOf(ImageEditTool.Draw) }
    var strokes by remember { mutableStateOf(emptyList<ImageStroke>()) }
    var arrows by remember { mutableStateOf(emptyList<ImageArrow>()) }
    var texts by remember { mutableStateOf(emptyList<ImageTextMark>()) }
    var draftStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var draftArrow by remember { mutableStateOf<ImageArrow?>(null) }
    var cropStart by remember { mutableStateOf<Offset?>(null) }
    var cropEnd by remember { mutableStateOf<Offset?>(null) }
    var textPosition by remember { mutableStateOf<Offset?>(null) }
    var textValue by remember { mutableStateOf("") }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(imageUrl) {
        bitmap = withContext(Dispatchers.IO) { loadBitmapForEdit(context, imageUrl) }
    }

    fun renderCurrent(): Bitmap? {
        val base = bitmap ?: return null
        return renderBitmap(base, strokes, arrows, texts)
    }

    fun clearMarks() {
        strokes = emptyList()
        arrows = emptyList()
        texts = emptyList()
        draftStroke = emptyList()
        draftArrow = null
        cropStart = null
        cropEnd = null
    }

    fun applyCrop() {
        val base = bitmap ?: return
        val start = cropStart ?: return
        val end = cropEnd ?: return
        val rendered = renderCurrent() ?: return
        val left = min(start.x, end.x).coerceIn(0f, base.width - 1f)
        val top = min(start.y, end.y).coerceIn(0f, base.height - 1f)
        val right = max(start.x, end.x).coerceIn(left + 1f, base.width.toFloat())
        val bottom = max(start.y, end.y).coerceIn(top + 1f, base.height.toFloat())
        bitmap = Bitmap.createBitmap(rendered, left.roundToInt(), top.roundToInt(), (right - left).roundToInt(), (bottom - top).roundToInt())
        clearMarks()
    }

    fun rotateRight() {
        val rendered = renderCurrent() ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        bitmap = Bitmap.createBitmap(rendered, 0, 0, rendered.width, rendered.height, matrix, true)
        clearMarks()
    }

    fun startCropMode() {
        tool = ImageEditTool.Crop
        val base = bitmap ?: return
        if (cropStart == null || cropEnd == null) {
            cropStart = Offset(base.width * 0.1f, base.height * 0.1f)
            cropEnd = Offset(base.width * 0.9f, base.height * 0.9f)
        }
    }

    fun undo() {
        when {
            draftStroke.isNotEmpty() -> draftStroke = emptyList()
            draftArrow != null -> draftArrow = null
            cropStart != null || cropEnd != null -> {
                cropStart = null
                cropEnd = null
            }
            texts.isNotEmpty() -> texts = texts.dropLast(1)
            arrows.isNotEmpty() -> arrows = arrows.dropLast(1)
            strokes.isNotEmpty() -> strokes = strokes.dropLast(1)
        }
    }

    fun save() {
        val rendered = renderCurrent() ?: return
        val outputFile = File(context.cacheDir, "edited_${Uuid.random()}.png")
        outputFile.outputStream().use { out -> rendered.compress(Bitmap.CompressFormat.PNG, 100, out) }
        onSave(outputFile.toUri())
    }

    val canUndo = draftStroke.isNotEmpty() || draftArrow != null || cropStart != null || cropEnd != null ||
        texts.isNotEmpty() || arrows.isNotEmpty() || strokes.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text(
                        "编辑图片",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::undo, enabled = canUndo) { Text("↶") }
                    TextButton(onClick = {}, enabled = false) { Text("↷") }
                    TextButton(onClick = ::save, enabled = bitmap != null) { Text("完成") }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    val currentBitmap = bitmap
                    if (currentBitmap == null) {
                        Text("正在加载图片...", color = Color.White, modifier = Modifier.padding(16.dp))
                    } else {
                        ImageEditCanvas(
                            bitmap = currentBitmap,
                            tool = tool,
                            strokes = strokes,
                            arrows = arrows,
                            texts = texts,
                            draftStroke = draftStroke,
                            draftArrow = draftArrow,
                            cropStart = cropStart,
                            cropEnd = cropEnd,
                            canvasSize = canvasSize,
                            onCanvasSizeChange = { canvasSize = it },
                            onDrawStart = { point -> draftStroke = listOf(point) },
                            onDraw = { point -> draftStroke = draftStroke + point },
                            onDrawEnd = {
                                if (draftStroke.size > 1) strokes = strokes + ImageStroke(draftStroke)
                                draftStroke = emptyList()
                            },
                            onArrowStart = { point -> draftArrow = ImageArrow(point, point) },
                            onArrow = { point -> draftArrow = draftArrow?.copy(end = point) },
                            onArrowEnd = {
                                draftArrow?.let { arrows = arrows + it }
                                draftArrow = null
                            },
                            onCropChange = { start, end -> cropStart = start; cropEnd = end },
                            onText = { point -> textPosition = point; textValue = "" },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolButton("✎", "涂鸦", tool == ImageEditTool.Draw) { tool = ImageEditTool.Draw }
                    ToolButton("➜", "箭头", tool == ImageEditTool.Arrow) { tool = ImageEditTool.Arrow }
                    ToolButton("T", "文字", tool == ImageEditTool.Text) { tool = ImageEditTool.Text }
                    ToolButton("⌗", "裁切", tool == ImageEditTool.Crop) { startCropMode() }
                    ToolButton("⟳", "旋转90°", false, enabled = bitmap != null) { rotateRight() }
                    ToolButton("✓", "应用裁切", false, enabled = cropStart != null && cropEnd != null) { applyCrop() }
                }
            }
        }
    }

    val pendingTextPosition = textPosition
    if (pendingTextPosition != null) {
        AlertDialog(
            onDismissRequest = { textPosition = null },
            title = { Text("添加文字") },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("文字内容") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textValue.isNotBlank()) texts = texts + ImageTextMark(textValue, pendingTextPosition)
                    textPosition = null
                    textValue = ""
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { textPosition = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ToolButton(
    icon: String,
    contentDescription: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = icon,
            style = MaterialTheme.typography.headlineSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ImageEditCanvas(
    bitmap: Bitmap,
    tool: ImageEditTool,
    strokes: List<ImageStroke>,
    arrows: List<ImageArrow>,
    texts: List<ImageTextMark>,
    draftStroke: List<Offset>,
    draftArrow: ImageArrow?,
    cropStart: Offset?,
    cropEnd: Offset?,
    canvasSize: IntSize,
    onCanvasSizeChange: (IntSize) -> Unit,
    onDrawStart: (Offset) -> Unit,
    onDraw: (Offset) -> Unit,
    onDrawEnd: () -> Unit,
    onArrowStart: (Offset) -> Unit,
    onArrow: (Offset) -> Unit,
    onArrowEnd: () -> Unit,
    onCropChange: (Offset, Offset) -> Unit,
    onText: (Offset) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val latestCropStart by rememberUpdatedState(cropStart)
    val latestCropEnd by rememberUpdatedState(cropEnd)
    val latestOnCropChange by rememberUpdatedState(onCropChange)
    var cropDragMode by remember { mutableStateOf(CropDragMode.New) }
    var lastCropPoint by remember { mutableStateOf<Offset?>(null) }
    val pointerModifier = Modifier.pointerInput(tool, bitmap, canvasSize) {
        if (tool == ImageEditTool.Text) {
            detectTapGestures { offset ->
                canvasToImage(offset, bitmap, canvasSize)?.let(onText)
            }
        } else {
            detectDragGestures(
                onDragStart = { offset ->
                    val point = canvasToImage(offset, bitmap, canvasSize) ?: return@detectDragGestures
                    when (tool) {
                        ImageEditTool.Draw -> onDrawStart(point)
                        ImageEditTool.Arrow -> onArrowStart(point)
                        ImageEditTool.Crop -> {
                            cropDragMode = detectCropDragMode(offset, latestCropStart, latestCropEnd, bitmap, canvasSize)
                            lastCropPoint = point
                            if (cropDragMode == CropDragMode.New) latestOnCropChange(point, point)
                        }
                        ImageEditTool.Text -> Unit
                    }
                },
                onDrag = { change, _ ->
                    val point = canvasToImage(change.position, bitmap, canvasSize) ?: return@detectDragGestures
                    when (tool) {
                        ImageEditTool.Draw -> onDraw(point)
                        ImageEditTool.Arrow -> onArrow(point)
                        ImageEditTool.Crop -> {
                            val start = latestCropStart ?: point
                            val end = latestCropEnd ?: point
                            when (cropDragMode) {
                                CropDragMode.New -> latestOnCropChange(start, point)
                                CropDragMode.TopLeft -> latestOnCropChange(point, end)
                                CropDragMode.TopRight -> latestOnCropChange(Offset(start.x, point.y), Offset(point.x, end.y))
                                CropDragMode.BottomLeft -> latestOnCropChange(Offset(point.x, start.y), Offset(end.x, point.y))
                                CropDragMode.BottomRight -> latestOnCropChange(start, point)
                                CropDragMode.Move -> {
                                    val last = lastCropPoint ?: point
                                    val delta = point - last
                                    val moved = moveCropRect(start, end, delta, bitmap)
                                    latestOnCropChange(moved.first, moved.second)
                                    lastCropPoint = point
                                }
                            }
                        }
                        ImageEditTool.Text -> Unit
                    }
                },
                onDragEnd = {
                    when (tool) {
                        ImageEditTool.Draw -> onDrawEnd()
                        ImageEditTool.Arrow -> onArrowEnd()
                        else -> Unit
                    }
                },
                onDragCancel = {
                    when (tool) {
                        ImageEditTool.Draw -> onDrawEnd()
                        ImageEditTool.Arrow -> onArrowEnd()
                        else -> Unit
                    }
                },
            )
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged(onCanvasSizeChange)
            .then(pointerModifier)
    ) {
        val layout = calculateLayout(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()))
        drawImage(
            image = imageBitmap,
            dstOffset = IntOffset(layout.left.roundToInt(), layout.top.roundToInt()),
            dstSize = IntSize(layout.width.roundToInt(), layout.height.roundToInt()),
        )
        (strokes + listOfNotNull(draftStroke.takeIf { it.size > 1 }?.let { ImageStroke(it) })).forEach { stroke ->
            drawPath(
                path = stroke.points.toComposePath(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt())),
                color = stroke.color,
                style = Stroke(width = stroke.width),
            )
        }
        (arrows + listOfNotNull(draftArrow)).forEach { arrow -> drawArrow(arrow, bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        texts.forEach { text -> drawTextMark(text, bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        val start = cropStart
        val end = cropEnd
        if (start != null && end != null) {
            drawCropRect(start, end, bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()))
        }
    }
}

private fun calculateLayout(bitmap: Bitmap, canvasSize: IntSize): ImageCanvasLayout {
    val scale = min(canvasSize.width / bitmap.width.toFloat(), canvasSize.height / bitmap.height.toFloat())
        .takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    return ImageCanvasLayout(
        scale = scale,
        left = (canvasSize.width - width) / 2f,
        top = (canvasSize.height - height) / 2f,
        width = width,
        height = height,
    )
}

private fun canvasToImage(point: Offset, bitmap: Bitmap, canvasSize: IntSize): Offset? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val layout = calculateLayout(bitmap, canvasSize)
    val x = ((point.x - layout.left) / layout.scale).coerceIn(0f, bitmap.width.toFloat())
    val y = ((point.y - layout.top) / layout.scale).coerceIn(0f, bitmap.height.toFloat())
    return Offset(x, y)
}

private fun imageToCanvas(point: Offset, bitmap: Bitmap, canvasSize: IntSize): Offset {
    val layout = calculateLayout(bitmap, canvasSize)
    return Offset(layout.left + point.x * layout.scale, layout.top + point.y * layout.scale)
}

private fun List<Offset>.toComposePath(bitmap: Bitmap, canvasSize: IntSize): ComposePath {
    val path = ComposePath()
    forEachIndexed { index, point ->
        val canvasPoint = imageToCanvas(point, bitmap, canvasSize)
        if (index == 0) path.moveTo(canvasPoint.x, canvasPoint.y) else path.lineTo(canvasPoint.x, canvasPoint.y)
    }
    return path
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(arrow: ImageArrow, bitmap: Bitmap, canvasSize: IntSize) {
    val start = imageToCanvas(arrow.start, bitmap, canvasSize)
    val end = imageToCanvas(arrow.end, bitmap, canvasSize)
    drawLine(color = arrow.color, start = start, end = end, strokeWidth = arrow.width)
    val angle = atan2(end.y - start.y, end.x - start.x)
    val headLength = 34f
    val left = Offset(
        x = end.x - headLength * cos(angle - Math.PI.toFloat() / 6f),
        y = end.y - headLength * sin(angle - Math.PI.toFloat() / 6f),
    )
    val right = Offset(
        x = end.x - headLength * cos(angle + Math.PI.toFloat() / 6f),
        y = end.y - headLength * sin(angle + Math.PI.toFloat() / 6f),
    )
    drawLine(color = arrow.color, start = end, end = left, strokeWidth = arrow.width)
    drawLine(color = arrow.color, start = end, end = right, strokeWidth = arrow.width)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTextMark(text: ImageTextMark, bitmap: Bitmap, canvasSize: IntSize) {
    val position = imageToCanvas(text.position, bitmap, canvasSize)
    drawContext.canvas.nativeCanvas.drawText(
        text.text,
        position.x,
        position.y,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.RED
            textSize = text.size * calculateLayout(bitmap, canvasSize).scale
            style = Paint.Style.FILL
        },
    )
}


private fun detectCropDragMode(
    canvasPoint: Offset,
    cropStart: Offset?,
    cropEnd: Offset?,
    bitmap: Bitmap,
    canvasSize: IntSize,
): CropDragMode {
    val start = cropStart ?: return CropDragMode.New
    val end = cropEnd ?: return CropDragMode.New
    val a = imageToCanvas(start, bitmap, canvasSize)
    val b = imageToCanvas(end, bitmap, canvasSize)
    val left = min(a.x, b.x)
    val top = min(a.y, b.y)
    val right = max(a.x, b.x)
    val bottom = max(a.y, b.y)
    val threshold = 42f
    fun near(point: Offset) = (canvasPoint - point).getDistance() <= threshold
    return when {
        near(Offset(left, top)) -> CropDragMode.TopLeft
        near(Offset(right, top)) -> CropDragMode.TopRight
        near(Offset(left, bottom)) -> CropDragMode.BottomLeft
        near(Offset(right, bottom)) -> CropDragMode.BottomRight
        canvasPoint.x in left..right && canvasPoint.y in top..bottom -> CropDragMode.Move
        else -> CropDragMode.New
    }
}

private fun moveCropRect(start: Offset, end: Offset, delta: Offset, bitmap: Bitmap): Pair<Offset, Offset> {
    val left = min(start.x, end.x)
    val top = min(start.y, end.y)
    val right = max(start.x, end.x)
    val bottom = max(start.y, end.y)
    val width = right - left
    val height = bottom - top
    val newLeft = (left + delta.x).coerceIn(0f, bitmap.width - width)
    val newTop = (top + delta.y).coerceIn(0f, bitmap.height - height)
    val movedStart = Offset(newLeft, newTop)
    val movedEnd = Offset(newLeft + width, newTop + height)
    return movedStart to movedEnd
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropRect(start: Offset, end: Offset, bitmap: Bitmap, canvasSize: IntSize) {
    val imageLayout = calculateLayout(bitmap, canvasSize)
    val a = imageToCanvas(start, bitmap, canvasSize)
    val b = imageToCanvas(end, bitmap, canvasSize)
    val left = min(a.x, b.x).coerceIn(imageLayout.left, imageLayout.left + imageLayout.width)
    val top = min(a.y, b.y).coerceIn(imageLayout.top, imageLayout.top + imageLayout.height)
    val right = max(a.x, b.x).coerceIn(imageLayout.left, imageLayout.left + imageLayout.width)
    val bottom = max(a.y, b.y).coerceIn(imageLayout.top, imageLayout.top + imageLayout.height)
    val overlay = Color.Black.copy(alpha = 0.58f)

    drawRect(overlay, topLeft = Offset(imageLayout.left, imageLayout.top), size = androidx.compose.ui.geometry.Size(imageLayout.width, top - imageLayout.top))
    drawRect(overlay, topLeft = Offset(imageLayout.left, bottom), size = androidx.compose.ui.geometry.Size(imageLayout.width, imageLayout.top + imageLayout.height - bottom))
    drawRect(overlay, topLeft = Offset(imageLayout.left, top), size = androidx.compose.ui.geometry.Size(left - imageLayout.left, bottom - top))
    drawRect(overlay, topLeft = Offset(right, top), size = androidx.compose.ui.geometry.Size(imageLayout.left + imageLayout.width - right, bottom - top))

    val cropWidth = right - left
    val cropHeight = bottom - top
    drawRect(
        color = Color.White,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(cropWidth, cropHeight),
        style = Stroke(width = 3f),
    )

    val gridColor = Color.White.copy(alpha = 0.65f)
    for (i in 1..2) {
        val x = left + cropWidth * i / 3f
        val y = top + cropHeight * i / 3f
        drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.2f)
        drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
    }

    val handleRadius = 9f
    listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach { point ->
        drawCircle(Color.White, radius = handleRadius, center = point)
    }
}


private fun renderBitmap(
    base: Bitmap,
    strokes: List<ImageStroke>,
    arrows: List<ImageArrow>,
    texts: List<ImageTextMark>,
): Bitmap {
    val output = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.points.size > 1) {
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            paint.strokeWidth = stroke.width
            canvas.drawPath(path, paint)
        }
    }
    arrows.forEach { arrow ->
        paint.strokeWidth = arrow.width
        canvas.drawLine(arrow.start.x, arrow.start.y, arrow.end.x, arrow.end.y, paint)
        val angle = atan2(arrow.end.y - arrow.start.y, arrow.end.x - arrow.start.x)
        val headLength = 42f
        val leftX = arrow.end.x - headLength * cos(angle - Math.PI.toFloat() / 6f)
        val leftY = arrow.end.y - headLength * sin(angle - Math.PI.toFloat() / 6f)
        val rightX = arrow.end.x - headLength * cos(angle + Math.PI.toFloat() / 6f)
        val rightY = arrow.end.y - headLength * sin(angle + Math.PI.toFloat() / 6f)
        canvas.drawLine(arrow.end.x, arrow.end.y, leftX, leftY, paint)
        canvas.drawLine(arrow.end.x, arrow.end.y, rightX, rightY, paint)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.RED
        style = Paint.Style.FILL
        textSize = 48f
    }
    texts.forEach { mark ->
        textPaint.textSize = mark.size
        canvas.drawText(mark.text, mark.position.x, mark.position.y, textPaint)
    }
    return output
}

private fun loadBitmapForEdit(context: Context, imageUrl: String): Bitmap? {
    return runCatching {
        when {
            imageUrl.startsWith("data:image") -> {
                val bytes = Base64.decode(imageUrl.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            else -> context.contentResolver.openInputStream(imageUrl.toUri())?.use(BitmapFactory::decodeStream)
        }
    }.getOrNull()
}
