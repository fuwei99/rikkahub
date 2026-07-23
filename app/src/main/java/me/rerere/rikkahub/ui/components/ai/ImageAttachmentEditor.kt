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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Text,
    Arrow,
    Mosaic,
}

private enum class EditScreen {
    Main,
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

private sealed interface ImageEditAction {
    val color: Color
    val width: Float
    val erase: Boolean

    data class Stroke(
        val points: List<Offset>,
        override val color: Color,
        override val width: Float,
        override val erase: Boolean = false,
    ) : ImageEditAction

    data class Arrow(
        val start: Offset,
        val end: Offset,
        override val color: Color,
        override val width: Float,
        override val erase: Boolean = false,
    ) : ImageEditAction

    data class Mosaic(
        val points: List<Offset>,
        override val width: Float,
        override val erase: Boolean = false,
    ) : ImageEditAction {
        override val color: Color = Color.Transparent
    }

    data class TextBox(
        val id: Int,
        val text: String,
        val position: Offset,
        val boxWidth: Float,
        val bold: Boolean,
        override val color: Color,
    ) : ImageEditAction {
        override val width: Float = 0f
        override val erase: Boolean = false
    }
}

private data class ImageCanvasLayout(val scale: Float, val left: Float, val top: Float, val width: Float, val height: Float)

private val editorColors = listOf(
    Color.White,
    Color.Black,
    Color(0xFFE53935),
    Color(0xFFFF8F00),
    Color(0xFFFFEB3B),
    Color(0xFF8BC34A),
    Color(0xFF1B5E20),
    Color(0xFF03A9F4),
    Color(0xFF0D47A1),
    Color(0xFF8E24AA),
)

@Composable
internal fun ImageAttachmentEditorDialog(
    imageUrl: String,
    onDismiss: () -> Unit,
    onSave: (Uri) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }
    var bitmapUndo by remember { mutableStateOf(emptyList<Bitmap>()) }
    var bitmapRedo by remember { mutableStateOf(emptyList<Bitmap>()) }
    var screen by remember { mutableStateOf(EditScreen.Main) }
    var tool by remember { mutableStateOf(ImageEditTool.Draw) }
    var selectedColor by remember { mutableStateOf(Color(0xFFE53935)) }
    var useEraser by remember { mutableStateOf(false) }
    var brushWidth by remember { mutableStateOf(10f) }
    var showToolOptions by remember { mutableStateOf(true) }
    var actions by remember { mutableStateOf(emptyList<ImageEditAction>()) }
    var redoActions by remember { mutableStateOf(emptyList<ImageEditAction>()) }
    var draftPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var draftArrow by remember { mutableStateOf<ImageEditAction.Arrow?>(null) }
    var cropStart by remember { mutableStateOf<Offset?>(null) }
    var cropEnd by remember { mutableStateOf<Offset?>(null) }
    var selectedTextId by remember { mutableStateOf<Int?>(null) }
    var nextTextId by remember { mutableIntStateOf(1) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(imageUrl) {
        bitmap = withContext(Dispatchers.IO) { loadBitmapForEdit(context, imageUrl) }
    }

    fun rendered(): Bitmap? = bitmap?.let { renderBitmap(it, actions) }

    fun pushAction(action: ImageEditAction) {
        actions = actions + action
        redoActions = emptyList()
    }

    fun replaceAction(action: ImageEditAction) {
        actions = actions.map { if (it is ImageEditAction.TextBox && action is ImageEditAction.TextBox && it.id == action.id) action else it }
    }

    fun snapshotBitmapForTransform() {
        bitmap?.let { current -> bitmapUndo = bitmapUndo + current.copy(Bitmap.Config.ARGB_8888, true) }
        bitmapRedo = emptyList()
    }

    fun clearDrafts() {
        draftPoints = emptyList()
        draftArrow = null
        selectedTextId = null
    }

    fun undo() {
        when {
            draftPoints.isNotEmpty() -> draftPoints = emptyList()
            draftArrow != null -> draftArrow = null
            actions.isNotEmpty() -> {
                redoActions = redoActions + actions.last()
                actions = actions.dropLast(1)
            }
            bitmapUndo.isNotEmpty() -> {
                bitmap?.let { current -> bitmapRedo = bitmapRedo + current.copy(Bitmap.Config.ARGB_8888, true) }
                bitmap = bitmapUndo.last()
                bitmapUndo = bitmapUndo.dropLast(1)
            }
        }
    }

    fun redo() {
        when {
            redoActions.isNotEmpty() -> {
                actions = actions + redoActions.last()
                redoActions = redoActions.dropLast(1)
            }
            bitmapRedo.isNotEmpty() -> {
                bitmap?.let { current -> bitmapUndo = bitmapUndo + current.copy(Bitmap.Config.ARGB_8888, true) }
                bitmap = bitmapRedo.last()
                bitmapRedo = bitmapRedo.dropLast(1)
            }
        }
    }

    fun enterCrop() {
        val base = bitmap ?: return
        screen = EditScreen.Crop
        selectedTextId = null
        cropStart = Offset.Zero
        cropEnd = Offset(base.width.toFloat(), base.height.toFloat())
    }

    fun rotateCrop(clockwise: Boolean) {
        val current = rendered() ?: return
        val start = cropStart ?: Offset.Zero
        val end = cropEnd ?: Offset(current.width.toFloat(), current.height.toFloat())
        snapshotBitmapForTransform()
        val matrix = Matrix().apply { postRotate(if (clockwise) 90f else -90f) }
        bitmap = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
        actions = emptyList()
        redoActions = emptyList()
        val oldWidth = current.width.toFloat()
        val oldHeight = current.height.toFloat()
        val corners = listOf(
            Offset(start.x, start.y),
            Offset(start.x, end.y),
            Offset(end.x, start.y),
            Offset(end.x, end.y),
        ).map { point ->
            if (clockwise) Offset(oldHeight - point.y, point.x) else Offset(point.y, oldWidth - point.x)
        }
        cropStart = Offset(corners.minOf { it.x }, corners.minOf { it.y })
        cropEnd = Offset(corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    fun applyCrop() {
        val current = rendered() ?: return
        val start = cropStart ?: return
        val end = cropEnd ?: return
        val left = min(start.x, end.x).coerceIn(0f, current.width - 1f)
        val top = min(start.y, end.y).coerceIn(0f, current.height - 1f)
        val right = max(start.x, end.x).coerceIn(left + 1f, current.width.toFloat())
        val bottom = max(start.y, end.y).coerceIn(top + 1f, current.height.toFloat())
        snapshotBitmapForTransform()
        bitmap = Bitmap.createBitmap(current, left.roundToInt(), top.roundToInt(), (right - left).roundToInt(), (bottom - top).roundToInt())
        actions = emptyList()
        redoActions = emptyList()
        cropStart = null
        cropEnd = null
        screen = EditScreen.Main
    }

    fun save() {
        val output = rendered() ?: return
        val file = File(context.cacheDir, "edited_${Uuid.random()}.png")
        file.outputStream().use { outputStream -> output.compress(Bitmap.CompressFormat.PNG, 100, outputStream) }
        onSave(file.toUri())
    }

    val canUndo = draftPoints.isNotEmpty() || draftArrow != null || actions.isNotEmpty() || bitmapUndo.isNotEmpty()
    val canRedo = redoActions.isNotEmpty() || bitmapRedo.isNotEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                EditorTopBar(
                    title = if (screen == EditScreen.Crop) "裁剪图片" else "编辑图片",
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onCancel = onDismiss,
                    onUndo = ::undo,
                    onRedo = ::redo,
                    onDone = ::save,
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    val base = bitmap
                    if (base == null) {
                        Text("正在加载图片...", color = Color.White, modifier = Modifier.padding(16.dp))
                    } else {
                        ImageEditCanvas(
                            bitmap = base,
                            screen = screen,
                            tool = tool,
                            actions = actions,
                            selectedTextId = selectedTextId,
                            draftPoints = draftPoints,
                            draftArrow = draftArrow,
                            cropStart = cropStart,
                            cropEnd = cropEnd,
                            canvasSize = canvasSize,
                            selectedColor = selectedColor,
                            brushWidth = brushWidth,
                            useEraser = useEraser,
                            onCanvasSizeChange = { canvasSize = it },
                            onDraftPointsChange = { draftPoints = it },
                            onDraftArrowChange = { draftArrow = it },
                            onCommitAction = ::pushAction,
                            onUpdateAction = ::replaceAction,
                            onSelectText = { selectedTextId = it },
                            onCreateText = { point ->
                                val id = nextTextId++
                                val textAction = ImageEditAction.TextBox(
                                    id = id,
                                    text = "请点击输入文字",
                                    position = point,
                                    boxWidth = base.width * 0.36f,
                                    bold = false,
                                    color = selectedColor,
                                )
                                pushAction(textAction)
                                selectedTextId = id
                            },
                            onCropChange = { start, end -> cropStart = start; cropEnd = end },
                        )
                    }
                }

                if (screen == EditScreen.Crop) {
                    CropBottomBar(
                        onRotateLeft = { rotateCrop(clockwise = false) },
                        onRotateRight = { rotateCrop(clockwise = true) },
                        onReset = {
                            val base = bitmap ?: return@CropBottomBar
                            cropStart = Offset.Zero
                            cropEnd = Offset(base.width.toFloat(), base.height.toFloat())
                        },
                        onCancelCrop = {
                            cropStart = null
                            cropEnd = null
                            screen = EditScreen.Main
                        },
                        onApplyCrop = ::applyCrop,
                    )
                } else {
                    MainBottomBar(
                        tool = tool,
                        onTool = { nextTool ->
                            tool = nextTool
                            showToolOptions = nextTool == ImageEditTool.Draw || nextTool == ImageEditTool.Mosaic
                            if (nextTool != ImageEditTool.Text) selectedTextId = null
                        },
                        onCrop = ::enterCrop,
                    )
                    if (showToolOptions && (tool == ImageEditTool.Draw || tool == ImageEditTool.Mosaic)) {
                        BrushOptionsBar(
                            selectedColor = selectedColor,
                            useEraser = useEraser,
                            width = brushWidth,
                            onEraser = { useEraser = true },
                            onColor = { color -> selectedColor = color; useEraser = false },
                            onWidth = { brushWidth = it },
                        )
                    }
                    selectedTextId?.let { id ->
                        val selectedText = actions.filterIsInstance<ImageEditAction.TextBox>().firstOrNull { it.id == id }
                        if (selectedText != null) {
                            TextOptionsBar(
                                action = selectedText,
                                onColor = { replaceAction(selectedText.copy(color = it)) },
                                onToggleBold = { replaceAction(selectedText.copy(bold = !selectedText.bold)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
        Text(title, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo, enabled = canUndo) { Text("↶", color = if (canUndo) Color.White else Color.DarkGray) }
        TextButton(onClick = onRedo, enabled = canRedo) { Text("↷", color = if (canRedo) Color.White else Color.DarkGray) }
        TextButton(onClick = onDone) { Text("完成", color = Color(0xFF10C469)) }
    }
}

@Composable
private fun MainBottomBar(
    tool: ImageEditTool,
    onTool: (ImageEditTool) -> Unit,
    onCrop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTextTool("✎", "涂鸦", selected = tool == ImageEditTool.Draw) { onTool(ImageEditTool.Draw) }
        IconTextTool("T", "文字", selected = tool == ImageEditTool.Text) { onTool(ImageEditTool.Text) }
        IconTextTool("➜", "箭头", selected = tool == ImageEditTool.Arrow) { onTool(ImageEditTool.Arrow) }
        IconTextTool("▦", "马赛克", selected = tool == ImageEditTool.Mosaic) { onTool(ImageEditTool.Mosaic) }
        IconTextTool("⌗", "裁剪", selected = false) { onCrop() }
    }
}

@Composable
private fun CropBottomBar(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onReset: () -> Unit,
    onCancelCrop: () -> Unit,
    onApplyCrop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRotateLeft) { Text("↺", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
            TextButton(onClick = onReset) { Text("还原", color = Color.White) }
            TextButton(onClick = onRotateRight) { Text("↻", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancelCrop) { Text("✕", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
            TextButton(onClick = onApplyCrop) { Text("✓", color = Color.White, style = MaterialTheme.typography.headlineMedium) }
        }
    }
}

@Composable
private fun IconTextTool(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(icon, color = if (selected) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BrushOptionsBar(
    selectedColor: Color,
    useEraser: Boolean,
    width: Float,
    onEraser: () -> Unit,
    onColor: (Color) -> Unit,
    onWidth: (Float) -> Unit,
) {
    var showWidth by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(Color.Transparent, selected = useEraser, label = "⌫", onClick = onEraser)
            editorColors.forEach { color -> ColorDot(color, selected = !useEraser && color == selectedColor, onClick = { onColor(color) }) }
            Surface(
                modifier = Modifier.size(36.dp).clickable { showWidth = !showWidth },
                color = Color.DarkGray,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(30.dp)) {
                        drawCircle(Color.White, radius = width.coerceIn(4f, 36f) / 2f)
                    }
                }
            }
        }
        if (showWidth) {
            androidx.compose.material3.Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = 4f..48f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun TextOptionsBar(action: ImageEditAction.TextBox, onColor: (Color) -> Unit, onToggleBold: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleBold) { Text("B", color = Color.White, fontWeight = if (action.bold) FontWeight.Black else FontWeight.Normal) }
        editorColors.forEach { color -> ColorDot(color, selected = color == action.color, onClick = { onColor(color) }) }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(34.dp).border(if (selected) 3.dp else 1.dp, Color.White, CircleShape).clickable(onClick = onClick),
        color = if (label != null) Color.DarkGray else color,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label != null) Text(label, color = Color.White)
        }
    }
}

@Composable
private fun ImageEditCanvas(
    bitmap: Bitmap,
    screen: EditScreen,
    tool: ImageEditTool,
    actions: List<ImageEditAction>,
    selectedTextId: Int?,
    draftPoints: List<Offset>,
    draftArrow: ImageEditAction.Arrow?,
    cropStart: Offset?,
    cropEnd: Offset?,
    canvasSize: IntSize,
    selectedColor: Color,
    brushWidth: Float,
    useEraser: Boolean,
    onCanvasSizeChange: (IntSize) -> Unit,
    onDraftPointsChange: (List<Offset>) -> Unit,
    onDraftArrowChange: (ImageEditAction.Arrow?) -> Unit,
    onCommitAction: (ImageEditAction) -> Unit,
    onUpdateAction: (ImageEditAction) -> Unit,
    onSelectText: (Int?) -> Unit,
    onCreateText: (Offset) -> Unit,
    onCropChange: (Offset, Offset) -> Unit,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val latestCropStart by rememberUpdatedState(cropStart)
    val latestCropEnd by rememberUpdatedState(cropEnd)
    val latestOnCropChange by rememberUpdatedState(onCropChange)
    var cropDragMode by remember { mutableStateOf(CropDragMode.New) }
    var lastCropPoint by remember { mutableStateOf<Offset?>(null) }
    val nonSelectedTextActions = actions.filterNot { it is ImageEditAction.TextBox && it.id == selectedTextId }
    val selectedText = actions.filterIsInstance<ImageEditAction.TextBox>().firstOrNull { it.id == selectedTextId }

    Box(modifier = Modifier.fillMaxSize().onSizeChanged(onCanvasSizeChange)) {
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(screen, tool, bitmap, canvasSize, selectedColor, brushWidth, useEraser) {
                if (screen == EditScreen.Main && tool == ImageEditTool.Text) {
                    detectTapGestures { offset ->
                        canvasToImage(offset, bitmap, canvasSize)?.let { point ->
                            onCreateText(point)
                        }
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val point = canvasToImage(offset, bitmap, canvasSize) ?: return@detectDragGestures
                            if (screen == EditScreen.Crop) {
                                cropDragMode = detectCropDragMode(offset, latestCropStart, latestCropEnd, bitmap, canvasSize)
                                lastCropPoint = point
                                if (cropDragMode == CropDragMode.New) latestOnCropChange(point, point)
                            } else {
                                when (tool) {
                                    ImageEditTool.Draw, ImageEditTool.Mosaic -> onDraftPointsChange(listOf(point))
                                    ImageEditTool.Arrow -> onDraftArrowChange(ImageEditAction.Arrow(point, point, selectedColor, brushWidth, useEraser))
                                    ImageEditTool.Text -> Unit
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            val point = canvasToImage(change.position, bitmap, canvasSize) ?: return@detectDragGestures
                            if (screen == EditScreen.Crop) {
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
                                        val moved = moveCropRect(start, end, point - last, bitmap)
                                        latestOnCropChange(moved.first, moved.second)
                                        lastCropPoint = point
                                    }
                                }
                            } else {
                                when (tool) {
                                    ImageEditTool.Draw, ImageEditTool.Mosaic -> onDraftPointsChange(draftPoints + point)
                                    ImageEditTool.Arrow -> onDraftArrowChange(draftArrow?.copy(end = point))
                                    ImageEditTool.Text -> Unit
                                }
                            }
                        },
                        onDragEnd = {
                            if (screen == EditScreen.Main) {
                                when (tool) {
                                    ImageEditTool.Draw -> {
                                        if (draftPoints.size > 1) onCommitAction(ImageEditAction.Stroke(draftPoints, selectedColor, brushWidth, useEraser))
                                        onDraftPointsChange(emptyList())
                                    }
                                    ImageEditTool.Mosaic -> {
                                        if (draftPoints.size > 1) onCommitAction(ImageEditAction.Mosaic(draftPoints, brushWidth, useEraser))
                                        onDraftPointsChange(emptyList())
                                    }
                                    ImageEditTool.Arrow -> {
                                        draftArrow?.let(onCommitAction)
                                        onDraftArrowChange(null)
                                    }
                                    ImageEditTool.Text -> Unit
                                }
                            }
                        },
                        onDragCancel = {
                            onDraftPointsChange(emptyList())
                            onDraftArrowChange(null)
                        },
                    )
                }
            }
        ) {
            val layout = calculateLayout(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()))
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset(layout.left.roundToInt(), layout.top.roundToInt()),
                dstSize = IntSize(layout.width.roundToInt(), layout.height.roundToInt()),
            )
            drawActions(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()), nonSelectedTextActions)
            if (draftPoints.size > 1) {
                val draftAction = if (tool == ImageEditTool.Mosaic) {
                    ImageEditAction.Mosaic(draftPoints, brushWidth, useEraser)
                } else {
                    ImageEditAction.Stroke(draftPoints, selectedColor, brushWidth, useEraser)
                }
                drawActions(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()), listOf(draftAction))
            }
            draftArrow?.let { drawActions(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()), listOf(it)) }
            val start = cropStart
            val end = cropEnd
            if (screen == EditScreen.Crop && start != null && end != null) drawCropRect(start, end, bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()))
        }

        selectedText?.let { textAction ->
            EditableTextOverlay(
                action = textAction,
                bitmap = bitmap,
                canvasSize = canvasSize,
                onUpdate = onUpdateAction,
                onSelect = { onSelectText(textAction.id) },
            )
        }
    }
}

@Composable
private fun EditableTextOverlay(
    action: ImageEditAction.TextBox,
    bitmap: Bitmap,
    canvasSize: IntSize,
    onUpdate: (ImageEditAction.TextBox) -> Unit,
    onSelect: () -> Unit,
) {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return
    val density = LocalDensity.current
    val layout = calculateLayout(bitmap, canvasSize)
    val topLeft = imageToCanvas(action.position, bitmap, canvasSize)
    val boxWidthPx = action.boxWidth * layout.scale
    val fontSize = with(density) { (boxWidthPx / 7f).coerceIn(16f, 48f).toSp() }
    Box(
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .width(with(density) { boxWidthPx.toDp() })
            .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .padding(4.dp)
            .pointerInput(action.id, canvasSize, layout.scale) {
                detectDragGestures { change, dragAmount ->
                    val imageDelta = Offset(dragAmount.x / layout.scale, dragAmount.y / layout.scale)
                    onUpdate(action.copy(position = action.position + imageDelta))
                    change.consume()
                }
            }
            .clickable(onClick = onSelect)
    ) {
        BasicTextField(
            value = action.text,
            onValueChange = { onUpdate(action.copy(text = it.ifBlank { "请点击输入文字" })) },
            textStyle = TextStyle(
                color = action.color,
                fontSize = fontSize,
                fontWeight = if (action.bold) FontWeight.Bold else FontWeight.Normal,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(18.dp)
                .background(Color.White, RoundedCornerShape(3.dp))
                .pointerInput(action.id, layout.scale) {
                    detectDragGestures { change, dragAmount ->
                        val newWidth = (action.boxWidth + dragAmount.x / layout.scale).coerceIn(bitmap.width * 0.18f, bitmap.width.toFloat())
                        onUpdate(action.copy(boxWidth = newWidth))
                        change.consume()
                    }
                }
        )
    }
}

private fun calculateLayout(bitmap: Bitmap, canvasSize: IntSize): ImageCanvasLayout {
    val scale = min(canvasSize.width / bitmap.width.toFloat(), canvasSize.height / bitmap.height.toFloat())
        .takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    return ImageCanvasLayout(scale, (canvasSize.width - width) / 2f, (canvasSize.height - height) / 2f, width, height)
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawActions(bitmap: Bitmap, canvasSize: IntSize, actions: List<ImageEditAction>) {
    actions.forEach { action ->
        when (action) {
            is ImageEditAction.Stroke -> {
                if (action.points.size > 1) {
                    drawPath(
                        path = action.points.toComposePath(bitmap, canvasSize),
                        color = if (action.erase) Color.White.copy(alpha = 0.65f) else action.color,
                        style = Stroke(width = action.width),
                    )
                }
            }
            is ImageEditAction.Arrow -> drawArrow(action, bitmap, canvasSize)
            is ImageEditAction.Mosaic -> drawMosaicPreview(action, bitmap, canvasSize)
            is ImageEditAction.TextBox -> drawTextMark(action, bitmap, canvasSize)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(arrow: ImageEditAction.Arrow, bitmap: Bitmap, canvasSize: IntSize) {
    val start = imageToCanvas(arrow.start, bitmap, canvasSize)
    val end = imageToCanvas(arrow.end, bitmap, canvasSize)
    val color = if (arrow.erase) Color.White.copy(alpha = 0.65f) else arrow.color
    drawLine(color = color, start = start, end = end, strokeWidth = arrow.width)
    val angle = atan2(end.y - start.y, end.x - start.x)
    val headLength = 34f
    val left = Offset(end.x - headLength * cos(angle - Math.PI.toFloat() / 6f), end.y - headLength * sin(angle - Math.PI.toFloat() / 6f))
    val right = Offset(end.x - headLength * cos(angle + Math.PI.toFloat() / 6f), end.y - headLength * sin(angle + Math.PI.toFloat() / 6f))
    drawLine(color = color, start = end, end = left, strokeWidth = arrow.width)
    drawLine(color = color, start = end, end = right, strokeWidth = arrow.width)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMosaicPreview(action: ImageEditAction.Mosaic, bitmap: Bitmap, canvasSize: IntSize) {
    action.points.forEach { point ->
        val center = imageToCanvas(point, bitmap, canvasSize)
        drawRect(
            color = if (action.erase) Color.White.copy(alpha = 0.35f) else Color.LightGray.copy(alpha = 0.55f),
            topLeft = Offset(center.x - action.width / 2f, center.y - action.width / 2f),
            size = androidx.compose.ui.geometry.Size(action.width, action.width),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTextMark(text: ImageEditAction.TextBox, bitmap: Bitmap, canvasSize: IntSize) {
    val position = imageToCanvas(text.position, bitmap, canvasSize)
    val layout = calculateLayout(bitmap, canvasSize)
    drawContext.canvas.nativeCanvas.drawText(
        text.text,
        position.x,
        position.y + (text.boxWidth * layout.scale / 7f).coerceIn(16f, 48f),
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text.color.toArgb()
            textSize = (text.boxWidth * layout.scale / 7f).coerceIn(16f, 48f)
            typeface = if (text.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            style = Paint.Style.FILL
        },
    )
}

private fun detectCropDragMode(canvasPoint: Offset, cropStart: Offset?, cropEnd: Offset?, bitmap: Bitmap, canvasSize: IntSize): CropDragMode {
    val start = cropStart ?: return CropDragMode.New
    val end = cropEnd ?: return CropDragMode.New
    val a = imageToCanvas(start, bitmap, canvasSize)
    val b = imageToCanvas(end, bitmap, canvasSize)
    val left = min(a.x, b.x)
    val top = min(a.y, b.y)
    val right = max(a.x, b.x)
    val bottom = max(a.y, b.y)
    val threshold = 46f
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
    return Offset(newLeft, newTop) to Offset(newLeft + width, newTop + height)
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
    drawRect(overlay, Offset(imageLayout.left, imageLayout.top), androidx.compose.ui.geometry.Size(imageLayout.width, top - imageLayout.top))
    drawRect(overlay, Offset(imageLayout.left, bottom), androidx.compose.ui.geometry.Size(imageLayout.width, imageLayout.top + imageLayout.height - bottom))
    drawRect(overlay, Offset(imageLayout.left, top), androidx.compose.ui.geometry.Size(left - imageLayout.left, bottom - top))
    drawRect(overlay, Offset(right, top), androidx.compose.ui.geometry.Size(imageLayout.left + imageLayout.width - right, bottom - top))
    val cropWidth = right - left
    val cropHeight = bottom - top
    drawRect(Color.White, Offset(left, top), androidx.compose.ui.geometry.Size(cropWidth, cropHeight), style = Stroke(width = 3f))
    val gridColor = Color.White.copy(alpha = 0.65f)
    for (i in 1..2) {
        val x = left + cropWidth * i / 3f
        val y = top + cropHeight * i / 3f
        drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.2f)
        drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.2f)
    }
    listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach { point -> drawCircle(Color.White, radius = 9f, center = point) }
}

private fun renderBitmap(base: Bitmap, actions: List<ImageEditAction>): Bitmap {
    val output = base.copy(Bitmap.Config.ARGB_8888, true)
    actions.forEach { action ->
        when (action) {
            is ImageEditAction.Stroke -> renderStroke(output, base, action)
            is ImageEditAction.Arrow -> renderArrow(output, base, action)
            is ImageEditAction.Mosaic -> renderMosaic(output, base, action)
            is ImageEditAction.TextBox -> renderText(output, action)
        }
    }
    return output
}

private fun renderStroke(output: Bitmap, base: Bitmap, stroke: ImageEditAction.Stroke) {
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.color.toArgb()
        strokeWidth = stroke.width
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    if (stroke.erase) {
        stroke.points.forEach { restoreBaseCircle(canvas, base, it, stroke.width) }
        return
    }
    if (stroke.points.size > 1) {
        val path = Path()
        stroke.points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
        canvas.drawPath(path, paint)
    }
}

private fun renderArrow(output: Bitmap, base: Bitmap, arrow: ImageEditAction.Arrow) {
    if (arrow.erase) {
        restoreBaseCircle(android.graphics.Canvas(output), base, arrow.end, arrow.width * 2f)
        return
    }
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arrow.color.toArgb()
        strokeWidth = arrow.width
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
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

private fun renderMosaic(output: Bitmap, base: Bitmap, action: ImageEditAction.Mosaic) {
    val canvas = android.graphics.Canvas(output)
    action.points.forEach { point ->
        if (action.erase) {
            restoreBaseCircle(canvas, base, point, action.width)
        } else {
            drawMosaicPatch(canvas, output, point, action.width)
        }
    }
}

private fun renderText(output: Bitmap, action: ImageEditAction.TextBox) {
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = action.color.toArgb()
        textSize = (action.boxWidth / 7f).coerceIn(24f, 96f)
        typeface = if (action.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        style = Paint.Style.FILL
    }
    canvas.drawText(action.text, action.position.x, action.position.y + paint.textSize, paint)
}

private fun restoreBaseCircle(canvas: android.graphics.Canvas, base: Bitmap, center: Offset, width: Float) {
    val radius = width / 2f
    val left = (center.x - radius).roundToInt().coerceIn(0, base.width - 1)
    val top = (center.y - radius).roundToInt().coerceIn(0, base.height - 1)
    val right = (center.x + radius).roundToInt().coerceIn(left + 1, base.width)
    val bottom = (center.y + radius).roundToInt().coerceIn(top + 1, base.height)
    val patch = Bitmap.createBitmap(base, left, top, right - left, bottom - top)
    canvas.drawBitmap(patch, left.toFloat(), top.toFloat(), null)
}

private fun drawMosaicPatch(canvas: android.graphics.Canvas, bitmap: Bitmap, center: Offset, width: Float) {
    val radius = width / 2f
    val left = (center.x - radius).roundToInt().coerceIn(0, bitmap.width - 1)
    val top = (center.y - radius).roundToInt().coerceIn(0, bitmap.height - 1)
    val right = (center.x + radius).roundToInt().coerceIn(left + 1, bitmap.width)
    val bottom = (center.y + radius).roundToInt().coerceIn(top + 1, bitmap.height)
    val patch = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    val small = Bitmap.createScaledBitmap(patch, max(1, patch.width / 12), max(1, patch.height / 12), false)
    val mosaic = Bitmap.createScaledBitmap(small, patch.width, patch.height, false)
    canvas.drawBitmap(mosaic, left.toFloat(), top.toFloat(), null)
}

private fun loadBitmapForEdit(context: Context, imageUrl: String): Bitmap? = runCatching {
    when {
        imageUrl.startsWith("data:image") -> {
            val bytes = Base64.decode(imageUrl.substringAfter("base64,"), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        else -> context.contentResolver.openInputStream(imageUrl.toUri())?.use(BitmapFactory::decodeStream)
    }
}.getOrNull()
