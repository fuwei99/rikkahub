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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text("编辑图片", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
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
                            onCropStart = { point -> cropStart = point; cropEnd = point },
                            onCrop = { point -> cropEnd = point },
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
                    ToolButton("涂鸦", tool == ImageEditTool.Draw) { tool = ImageEditTool.Draw }
                    ToolButton("箭头", tool == ImageEditTool.Arrow) { tool = ImageEditTool.Arrow }
                    ToolButton("文字", tool == ImageEditTool.Text) { tool = ImageEditTool.Text }
                    ToolButton("裁切", tool == ImageEditTool.Crop) { tool = ImageEditTool.Crop }
                    TextButton(onClick = ::applyCrop, enabled = cropStart != null && cropEnd != null) { Text("应用裁切") }
                    TextButton(onClick = ::rotateRight, enabled = bitmap != null) { Text("旋转90°") }
                    TextButton(onClick = ::undo) { Text("撤销") }
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
private fun ToolButton(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
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
    onCropStart: (Offset) -> Unit,
    onCrop: (Offset) -> Unit,
    onText: (Offset) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
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
                        ImageEditTool.Crop -> onCropStart(point)
                        ImageEditTool.Text -> Unit
                    }
                },
                onDrag = { change, _ ->
                    val point = canvasToImage(change.position, bitmap, canvasSize) ?: return@detectDragGestures
                    when (tool) {
                        ImageEditTool.Draw -> onDraw(point)
                        ImageEditTool.Arrow -> onArrow(point)
                        ImageEditTool.Crop -> onCrop(point)
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCropRect(start: Offset, end: Offset, bitmap: Bitmap, canvasSize: IntSize) {
    val a = imageToCanvas(start, bitmap, canvasSize)
    val b = imageToCanvas(end, bitmap, canvasSize)
    val left = min(a.x, b.x)
    val top = min(a.y, b.y)
    val right = max(a.x, b.x)
    val bottom = max(a.y, b.y)
    drawRect(
        color = Color.White,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
        style = Stroke(width = 3f),
    )
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
