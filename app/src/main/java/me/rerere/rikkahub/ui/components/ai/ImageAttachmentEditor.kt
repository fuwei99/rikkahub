package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.geometry.Size
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
import kotlin.math.pow
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

private enum class MosaicStyle {
    PixelCoarse,
    PixelFine,
    Blur,
    Frosted,
    Diamond,
    ColorDiamond,
    Glass,
}

private enum class TextBoxAlign {
    Left,
    Right,
    Center,
    Justify,
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
        val style: MosaicStyle = MosaicStyle.PixelCoarse,
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
        val vertical: Boolean = false,
        val bordered: Boolean = true,
        val align: TextBoxAlign = TextBoxAlign.Center,
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
    var mosaicStyle by remember { mutableStateOf(MosaicStyle.PixelCoarse) }
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
        redoActions = emptyList()
    }

    fun deleteTextAction(id: Int) {
        val nextActions = actions.filterNot { it is ImageEditAction.TextBox && it.id == id }
        if (nextActions.size != actions.size) {
            actions = nextActions
            redoActions = emptyList()
        }
        selectedTextId = null
    }

    fun eraseStrokes(points: List<Offset>, width: Float) {
        if (points.isEmpty()) return
        val nextActions = buildList {
            actions.forEach { action ->
                if (action is ImageEditAction.Stroke) addAll(action.eraseBy(points, width)) else add(action)
            }
        }
        if (nextActions != actions) {
            actions = nextActions
            redoActions = emptyList()
        }
    }

    fun eraseMosaics(points: List<Offset>, width: Float) {
        if (points.isEmpty()) return
        val nextActions = actions.mapNotNull { action ->
            if (action is ImageEditAction.Mosaic) action.eraseBy(points, width) else action
        }
        if (nextActions != actions) {
            actions = nextActions
            redoActions = emptyList()
        }
    }

    fun eraseArrows(points: List<Offset>, width: Float) {
        if (points.isEmpty()) return
        val nextActions = actions.filterNot { action ->
            action is ImageEditAction.Arrow && points.any { point -> distanceToSegment(point, action.start, action.end) <= width * 1.5f }
        }
        if (nextActions.size != actions.size) {
            redoActions = emptyList()
            actions = nextActions
        }
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
                            mosaicStyle = mosaicStyle,
                            onCanvasSizeChange = { canvasSize = it },
                            onDraftPointsChange = { draftPoints = it },
                            onDraftArrowChange = { draftArrow = it },
                            onCommitAction = ::pushAction,
                            onUpdateAction = ::replaceAction,
                            onEraseStrokes = ::eraseStrokes,
                            onEraseMosaics = ::eraseMosaics,
                            onEraseArrows = ::eraseArrows,
                            onDeleteText = ::deleteTextAction,
                            onFinishText = { selectedTextId = null },
                            onSelectText = { selectedTextId = it },
                            onCreateText = { point ->
                                val id = nextTextId++
                                val textAction = ImageEditAction.TextBox(
                                    id = id,
                                    text = "请点击输入文字",
                                    position = point,
                                    boxWidth = base.width * 0.36f,
                                    bold = false,
                                    vertical = false,
                                    bordered = true,
                                    align = TextBoxAlign.Left,
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
                            showToolOptions = nextTool == ImageEditTool.Draw || nextTool == ImageEditTool.Arrow || nextTool == ImageEditTool.Mosaic
                            if (nextTool != ImageEditTool.Text) selectedTextId = null
                        },
                        onCrop = ::enterCrop,
                    )
                    if (showToolOptions && (tool == ImageEditTool.Draw || tool == ImageEditTool.Arrow || tool == ImageEditTool.Mosaic)) {
                        BrushOptionsBar(
                            isMosaic = tool == ImageEditTool.Mosaic,
                            selectedColor = selectedColor,
                            useEraser = useEraser,
                            width = brushWidth,
                            mosaicStyle = mosaicStyle,
                            onEraser = { useEraser = true },
                            onColor = { color -> selectedColor = color; useEraser = false },
                            onMosaicStyle = { style -> mosaicStyle = style; useEraser = false },
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
                                onToggleDirection = {
                                    val currentBitmap = bitmap
                                    replaceAction(if (currentBitmap != null) selectedText.toggleDirectionPreserveCenter(currentBitmap) else selectedText.copy(vertical = !selectedText.vertical))
                                },
                                onCycleAlign = { replaceAction(selectedText.copy(align = selectedText.align.next())) },
                                onToggleBorder = { replaceAction(selectedText.copy(bordered = !selectedText.bordered)) },
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
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) { Text("取消", color = Color.White) }
        Text(title, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
        Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onUndo, enabled = canUndo) { Text("↶", color = if (canUndo) Color.White else Color.DarkGray, fontSize = 32.sp, fontWeight = FontWeight.Black) }
            TextButton(onClick = onRedo, enabled = canRedo) { Text("↷", color = if (canRedo) Color.White else Color.DarkGray, fontSize = 32.sp, fontWeight = FontWeight.Black) }
            TextButton(onClick = onDone) { Text("完成", color = Color(0xFF10C469)) }
        }
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
    isMosaic: Boolean,
    selectedColor: Color,
    useEraser: Boolean,
    width: Float,
    mosaicStyle: MosaicStyle,
    onEraser: () -> Unit,
    onColor: (Color) -> Unit,
    onMosaicStyle: (MosaicStyle) -> Unit,
    onWidth: (Float) -> Unit,
) {
    var showWidth by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EraserButton(selected = useEraser, onClick = onEraser)
            VerticalDivider()
            if (isMosaic) {
                MosaicStyle.values().forEach { style ->
                    MosaicDot(style = style, selected = !useEraser && style == mosaicStyle, onClick = { onMosaicStyle(style) })
                }
            } else {
                editorColors.forEach { color -> ColorDot(color, selected = !useEraser && color == selectedColor, onClick = { onColor(color) }) }
            }
            VerticalDivider()
            Surface(
                modifier = Modifier.size(42.dp).clickable { showWidth = !showWidth },
                color = Color.DarkGray,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(34.dp)) {
                        drawCircle(Color.White, radius = width.coerceIn(4f, 44f) / 2f)
                    }
                }
            }
        }
        if (showWidth) {
            androidx.compose.material3.Slider(
                value = width,
                onValueChange = onWidth,
                valueRange = 4f..56f,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun TextOptionsBar(
    action: ImageEditAction.TextBox,
    onColor: (Color) -> Unit,
    onToggleBold: () -> Unit,
    onToggleDirection: () -> Unit,
    onCycleAlign: () -> Unit,
    onToggleBorder: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextToolIconButton(onClick = onToggleBold, selected = action.bold) {
            Text("B", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        }
        TextToolIconButton(onClick = onToggleDirection, selected = false) {
            DirectionIcon()
        }
        TextToolIconButton(onClick = onCycleAlign, selected = false) {
            AlignIcon(action.align)
        }
        TextToolIconButton(onClick = onToggleBorder, selected = false) {
            BorderIcon(action.bordered)
        }
        editorColors.forEach { color -> ColorDot(color, selected = color == action.color, onClick = { onColor(color) }) }
    }
}

@Composable
private fun TextToolIconButton(
    onClick: () -> Unit,
    selected: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.size(40.dp).clickable(onClick = onClick),
        color = if (selected) Color.White.copy(alpha = 0.22f) else Color(0xFF2B2B2B),
        shape = RoundedCornerShape(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun DirectionIcon() {
    Canvas(modifier = Modifier.size(25.dp)) {
        val stroke = 2.4f
        val c = Color.White
        drawLine(c, Offset(size.width * 0.25f, size.height * 0.22f), Offset(size.width * 0.75f, size.height * 0.22f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.75f, size.height * 0.22f), Offset(size.width * 0.62f, size.height * 0.10f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.75f, size.height * 0.22f), Offset(size.width * 0.62f, size.height * 0.34f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.75f, size.height * 0.78f), Offset(size.width * 0.25f, size.height * 0.78f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.25f, size.height * 0.78f), Offset(size.width * 0.38f, size.height * 0.66f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.25f, size.height * 0.78f), Offset(size.width * 0.38f, size.height * 0.90f), strokeWidth = stroke)
        drawRect(c.copy(alpha = 0.9f), Offset(size.width * 0.37f, size.height * 0.37f), Size(size.width * 0.26f, size.height * 0.26f), style = Stroke(width = stroke))
    }
}

@Composable
private fun AlignIcon(align: TextBoxAlign) {
    Canvas(modifier = Modifier.size(25.dp)) {
        val c = Color.White
        val stroke = 2.8f
        val fullLeft = size.width * 0.16f
        val fullRight = size.width * 0.84f
        val short = size.width * 0.42f
        val y1 = size.height * 0.25f
        val y2 = size.height * 0.50f
        val y3 = size.height * 0.75f
        drawLine(c, Offset(fullLeft, y1), Offset(fullRight, y1), strokeWidth = stroke)
        val (middleLeft, middleRight) = when (align) {
            TextBoxAlign.Left -> fullLeft to (fullLeft + short)
            TextBoxAlign.Right -> (fullRight - short) to fullRight
            TextBoxAlign.Center -> ((size.width - short) / 2f) to ((size.width + short) / 2f)
            TextBoxAlign.Justify -> fullLeft to fullRight
        }
        drawLine(c, Offset(middleLeft, y2), Offset(middleRight, y2), strokeWidth = stroke)
        drawLine(c, Offset(fullLeft, y3), Offset(fullRight, y3), strokeWidth = stroke)
    }
}

@Composable
private fun BorderIcon(bordered: Boolean) {
    Canvas(modifier = Modifier.size(25.dp)) {
        val c = Color.White
        val stroke = 2.5f
        val left = size.width * 0.18f
        val top = size.height * 0.22f
        val right = size.width * 0.82f
        val bottom = size.height * 0.78f
        if (bordered) {
            drawRect(c, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = stroke))
        } else {
            val dash = size.width * 0.13f
            var x = left
            while (x < right) {
                drawLine(c, Offset(x, top), Offset((x + dash).coerceAtMost(right), top), strokeWidth = stroke)
                drawLine(c, Offset(x, bottom), Offset((x + dash).coerceAtMost(right), bottom), strokeWidth = stroke)
                x += dash * 1.8f
            }
            var y = top
            while (y < bottom) {
                drawLine(c, Offset(left, y), Offset(left, (y + dash).coerceAtMost(bottom)), strokeWidth = stroke)
                drawLine(c, Offset(right, y), Offset(right, (y + dash).coerceAtMost(bottom)), strokeWidth = stroke)
                y += dash * 1.8f
            }
        }
    }
}


@Composable
private fun ColorDot(color: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    val size = if (selected) 42.dp else 32.dp
    Surface(
        modifier = Modifier.size(size).border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.45f), CircleShape).clickable(onClick = onClick),
        color = if (label != null) Color.DarkGray else color,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) { if (label != null) Text(label, color = Color.White) }
    }
}

@Composable
private fun EraserButton(selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(if (selected) 46.dp else 38.dp).border(2.dp, Color.White, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        color = Color(0xFF2B2B2B),
        shape = RoundedCornerShape(10.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
            val stroke = 2.2f
            val left = size.width * 0.30f
            val right = size.width * 0.70f
            val top = size.height * 0.10f
            val bottom = size.height * 0.90f
            val split = size.height * 0.34f
            drawRect(Color(0xFFF2F2F2), Offset(left, top), Size(right - left, split - top))
            drawRect(Color(0xFFFFB0B9), Offset(left, split), Size(right - left, bottom - split))
            drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = stroke))
            drawLine(Color.White, Offset(left, split), Offset(right, split), strokeWidth = stroke)
        }
    }
}


@Composable
private fun VerticalDivider() {
    Box(modifier = Modifier.height(32.dp).width(1.dp).background(Color.White.copy(alpha = 0.35f)))
}

@Composable
private fun MosaicDot(style: MosaicStyle, selected: Boolean, onClick: () -> Unit) {
    val dotSize = if (selected) 42.dp else 34.dp
    Surface(
        modifier = Modifier.size(dotSize).border(2.dp, if (selected) Color.White else Color.White.copy(alpha = 0.45f), CircleShape).clickable(onClick = onClick),
        color = Color(0xFF303030),
        shape = CircleShape,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(7.dp)) {
            when (style) {
                MosaicStyle.PixelCoarse -> drawMosaicIcon(blocks = 2, colored = false)
                MosaicStyle.PixelFine -> drawMosaicIcon(blocks = 4, colored = false)
                MosaicStyle.Blur -> drawCircle(Color.LightGray.copy(alpha = 0.75f), radius = size.minDimension() * 0.28f)
                MosaicStyle.Frosted -> {
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = size.minDimension() * 0.32f)
                    drawCircle(Color.LightGray.copy(alpha = 0.55f), radius = size.minDimension() * 0.22f)
                }
                MosaicStyle.Diamond -> drawDiamondIcon(colored = false)
                MosaicStyle.ColorDiamond -> drawDiamondIcon(colored = true)
                MosaicStyle.Glass -> drawGlassIcon()
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMosaicIcon(blocks: Int, colored: Boolean) {
    val gap = 2f
    val cell = (size.minDimension() - gap * (blocks - 1)) / blocks
    for (x in 0 until blocks) for (y in 0 until blocks) {
        val color = if (colored) editorColors[(x + y) % editorColors.size] else if ((x + y) % 2 == 0) Color.White else Color.Gray
        drawRect(color, Offset(x * (cell + gap), y * (cell + gap)), Size(cell, cell))
    }
}

private fun Size.minDimension(): Float = min(width, height)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDiamondIcon(colored: Boolean) {
    val colors = if (colored) listOf(Color.Red, Color.Yellow, Color.Cyan, Color.Magenta) else listOf(Color.White, Color.Gray, Color.LightGray, Color.DarkGray)
    val centers = listOf(Offset(size.width * .35f, size.height * .35f), Offset(size.width * .65f, size.height * .35f), Offset(size.width * .35f, size.height * .65f), Offset(size.width * .65f, size.height * .65f))
    centers.forEachIndexed { i, center ->
        val r = size.minDimension() * .16f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(center.x, center.y - r); lineTo(center.x + r, center.y); lineTo(center.x, center.y + r); lineTo(center.x - r, center.y); close()
        }
        drawPath(path, colors[i])
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassIcon() {
    drawMosaicIcon(blocks = 3, colored = true)
    drawLine(Color.White.copy(alpha = .75f), Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 2f)
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
    mosaicStyle: MosaicStyle,
    onCanvasSizeChange: (IntSize) -> Unit,
    onDraftPointsChange: (List<Offset>) -> Unit,
    onDraftArrowChange: (ImageEditAction.Arrow?) -> Unit,
    onCommitAction: (ImageEditAction) -> Unit,
    onUpdateAction: (ImageEditAction) -> Unit,
    onEraseStrokes: (List<Offset>, Float) -> Unit,
    onEraseMosaics: (List<Offset>, Float) -> Unit,
    onEraseArrows: (List<Offset>, Float) -> Unit,
    onDeleteText: (Int) -> Unit,
    onFinishText: () -> Unit,
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
    var pointerCanvasPoint by remember { mutableStateOf<Offset?>(null) }
    val draftAction: ImageEditAction? = when {
        useEraser -> null
        draftPoints.size > 1 && tool == ImageEditTool.Mosaic -> ImageEditAction.Mosaic(draftPoints, brushWidth, mosaicStyle, false)
        draftPoints.size > 1 -> ImageEditAction.Stroke(draftPoints, selectedColor, brushWidth, false)
        draftArrow != null -> draftArrow
        else -> null
    }
    val displayActions = when {
        screen == EditScreen.Crop -> actions
        useEraser && draftPoints.isNotEmpty() -> nonSelectedTextActions.previewErase(tool, draftPoints, brushWidth)
        else -> nonSelectedTextActions + listOfNotNull(draftAction)
    }
    val renderedBitmap = remember(bitmap, displayActions) { renderBitmap(bitmap, displayActions) }
    val renderedImageBitmap = remember(renderedBitmap) { renderedBitmap.asImageBitmap() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .then(if (screen == EditScreen.Crop) Modifier.padding(horizontal = 36.dp, vertical = 42.dp) else Modifier)
                .fillMaxSize()
                .onSizeChanged(onCanvasSizeChange)
                .pointerInput(screen, tool, bitmap, canvasSize, selectedColor, brushWidth, useEraser, mosaicStyle) {
                if (screen == EditScreen.Main && tool == ImageEditTool.Text) {
                    detectTapGestures { offset ->
                        val point = canvasToImage(offset, bitmap, canvasSize) ?: return@detectTapGestures
                        val hit = actions.filterIsInstance<ImageEditAction.TextBox>().asReversed().firstOrNull { it.contains(point) }
                        when {
                            hit != null -> onSelectText(hit.id)
                            selectedTextId == null -> onCreateText(point)
                        }
                    }
                } else {
                    var gesturePoints = emptyList<Offset>()
                    var gestureArrow: ImageEditAction.Arrow? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            pointerCanvasPoint = if (screen == EditScreen.Main && (tool == ImageEditTool.Draw || tool == ImageEditTool.Mosaic || (tool == ImageEditTool.Arrow && useEraser))) offset else null
                            val point = canvasToImage(offset, bitmap, canvasSize) ?: return@detectDragGestures
                            if (screen == EditScreen.Crop) {
                                cropDragMode = detectCropDragMode(offset, latestCropStart, latestCropEnd, bitmap, canvasSize)
                                lastCropPoint = point
                                if (cropDragMode == CropDragMode.New) latestOnCropChange(point, point)
                            } else {
                                when (tool) {
                                    ImageEditTool.Draw, ImageEditTool.Mosaic -> {
                                        gesturePoints = listOf(point)
                                        onDraftPointsChange(gesturePoints)
                                    }
                                    ImageEditTool.Arrow -> {
                                        gesturePoints = listOf(point)
                                        if (useEraser) {
                                            onDraftPointsChange(gesturePoints)
                                        } else {
                                            gestureArrow = ImageEditAction.Arrow(point, point, selectedColor, brushWidth, false)
                                            onDraftArrowChange(gestureArrow)
                                        }
                                    }
                                    ImageEditTool.Text -> Unit
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            pointerCanvasPoint = if (screen == EditScreen.Main && (tool == ImageEditTool.Draw || tool == ImageEditTool.Mosaic || (tool == ImageEditTool.Arrow && useEraser))) change.position else null
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
                                    ImageEditTool.Draw, ImageEditTool.Mosaic -> {
                                        gesturePoints = gesturePoints + point
                                        onDraftPointsChange(gesturePoints)
                                    }
                                    ImageEditTool.Arrow -> {
                                        gesturePoints = gesturePoints + point
                                        if (useEraser) {
                                            onDraftPointsChange(gesturePoints)
                                        } else {
                                            gestureArrow = gestureArrow?.copy(end = point)
                                            onDraftArrowChange(gestureArrow)
                                        }
                                    }
                                    ImageEditTool.Text -> Unit
                                }
                            }
                        },
                        onDragEnd = {
                            pointerCanvasPoint = null
                            if (screen == EditScreen.Main) {
                                when (tool) {
                                    ImageEditTool.Draw -> {
                                        if (useEraser) {
                                            if (gesturePoints.isNotEmpty()) onEraseStrokes(gesturePoints, brushWidth)
                                        } else if (gesturePoints.size > 1) {
                                            onCommitAction(ImageEditAction.Stroke(gesturePoints, selectedColor, brushWidth, false))
                                        }
                                        gesturePoints = emptyList()
                                        onDraftPointsChange(emptyList())
                                    }
                                    ImageEditTool.Mosaic -> {
                                        if (useEraser) {
                                            if (gesturePoints.isNotEmpty()) onEraseMosaics(gesturePoints, brushWidth)
                                        } else if (gesturePoints.size > 1) {
                                            onCommitAction(ImageEditAction.Mosaic(gesturePoints, brushWidth, mosaicStyle, false))
                                        }
                                        gesturePoints = emptyList()
                                        onDraftPointsChange(emptyList())
                                    }
                                    ImageEditTool.Arrow -> {
                                        if (useEraser) onEraseArrows(gesturePoints, brushWidth) else gestureArrow?.let(onCommitAction)
                                        gesturePoints = emptyList()
                                        gestureArrow = null
                                        onDraftPointsChange(emptyList())
                                        onDraftArrowChange(null)
                                    }
                                    ImageEditTool.Text -> Unit
                                }
                            }
                        },
                        onDragCancel = {
                            pointerCanvasPoint = null
                            gesturePoints = emptyList()
                            gestureArrow = null
                            onDraftPointsChange(emptyList())
                            onDraftArrowChange(null)
                        },
                    )
                }
            }
        ) {
            val layout = calculateLayout(bitmap, IntSize(size.width.roundToInt(), size.height.roundToInt()))
            drawImage(
                image = renderedImageBitmap,
                dstOffset = IntOffset(layout.left.roundToInt(), layout.top.roundToInt()),
                dstSize = IntSize(layout.width.roundToInt(), layout.height.roundToInt()),
            )
            pointerCanvasPoint?.let { pointer ->
                val pointerRadius = brushWidth * layout.scale / 2f
                drawCircle(Color.White.copy(alpha = 0.80f), radius = pointerRadius, center = pointer)
                drawCircle(Color.Black.copy(alpha = 0.95f), radius = pointerRadius, center = pointer, style = Stroke(width = 1.5f))
            }
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
                onDelete = { onDeleteText(textAction.id) },
                onDone = onFinishText,
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
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onSelect: () -> Unit,
) {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return
    val density = LocalDensity.current
    val layout = calculateLayout(bitmap, canvasSize)
    val topLeft = imageToCanvas(action.position, bitmap, canvasSize)
    val boxWidthPx = action.boxWidth * layout.scale
    val fontSize = with(density) { (boxWidthPx / 7f).coerceIn(16f, 48f).toSp() }
    val editorValue = if (action.vertical) action.text.toList().joinToString("\n") else action.text
    Box(
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .width(with(density) { boxWidthPx.toDp() })
            .clickable(onClick = onSelect)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-70).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingMenuButton(onClick = onDelete) { TrashIcon() }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .pointerInput(action.id, layout.scale) {
                            var startPosition = action.position
                            var totalDelta = Offset.Zero
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    startPosition = action.position
                                    totalDelta = Offset.Zero
                                },
                                onDrag = { change, dragAmount ->
                                    totalDelta += Offset(dragAmount.x / layout.scale, dragAmount.y / layout.scale)
                                    onUpdate(action.copy(position = constrainTextPosition(startPosition + totalDelta, action, bitmap)))
                                    change.consume()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { MoveIcon() }
                FloatingMenuButton(onClick = onDone) { CheckIcon() }
            }
            Canvas(modifier = Modifier.size(width = 18.dp, height = 9.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2f, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(path, Color.Black.copy(alpha = 0.78f))
            }
        }
        Box(
            modifier = Modifier
                .border(2.dp, if (action.bordered) action.color else Color.White.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            BasicTextField(
                value = editorValue,
                onValueChange = { value -> onUpdate(action.copy(text = if (action.vertical) value.replace("\n", "") else value)) },
                textStyle = TextStyle(
                    color = action.color,
                    fontSize = fontSize,
                    fontWeight = if (action.bold) FontWeight.Bold else FontWeight.Normal,
                    textAlign = action.align.toComposeTextAlign(),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 8.dp)
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.78f), CircleShape)
                    .border(1.dp, Color.White, CircleShape)
                    .pointerInput(action.id, layout.scale) {
                        var startWidth = action.boxWidth
                        var totalDx = 0f
                        detectDragGestures(
                            onDragStart = {
                                startWidth = action.boxWidth
                                totalDx = 0f
                            },
                            onDrag = { change, dragAmount ->
                                totalDx += dragAmount.x / layout.scale
                                val newWidth = (startWidth + totalDx).coerceIn(bitmap.width * 0.12f, bitmap.width.toFloat())
                                val resized = action.copy(boxWidth = newWidth)
                                onUpdate(resized.copy(position = constrainTextPosition(resized.position, resized, bitmap)))
                                change.consume()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(12.dp)) {
                    val c = Color.White
                    drawLine(c, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 2f)
                    drawLine(c, Offset(size.width * 0.35f, size.height), Offset(size.width, size.height * 0.35f), strokeWidth = 2f)
                }
            }
        }
    }
}

@Composable
private fun FloatingMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun TrashIcon() {
    Canvas(modifier = Modifier.size(23.dp)) {
        val c = Color.White
        val stroke = 2.2f
        drawLine(c, Offset(size.width * 0.25f, size.height * 0.30f), Offset(size.width * 0.75f, size.height * 0.30f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.40f, size.height * 0.18f), Offset(size.width * 0.60f, size.height * 0.18f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.34f, size.height * 0.38f), Offset(size.width * 0.40f, size.height * 0.86f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.66f, size.height * 0.38f), Offset(size.width * 0.60f, size.height * 0.86f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.40f, size.height * 0.86f), Offset(size.width * 0.60f, size.height * 0.86f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.47f, size.height * 0.45f), Offset(size.width * 0.47f, size.height * 0.76f), strokeWidth = stroke)
        drawLine(c, Offset(size.width * 0.57f, size.height * 0.45f), Offset(size.width * 0.57f, size.height * 0.76f), strokeWidth = stroke)
    }
}

@Composable
private fun MoveIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val c = Color.White
        val stroke = 2.2f
        val center = Offset(size.width / 2f, size.height / 2f)
        fun arrow(end: Offset, wing1: Offset, wing2: Offset) {
            drawLine(c, center, end, strokeWidth = stroke)
            drawLine(c, end, wing1, strokeWidth = stroke)
            drawLine(c, end, wing2, strokeWidth = stroke)
        }
        arrow(Offset(center.x, 1f), Offset(center.x - 4f, 6f), Offset(center.x + 4f, 6f))
        arrow(Offset(center.x, size.height - 1f), Offset(center.x - 4f, size.height - 6f), Offset(center.x + 4f, size.height - 6f))
        arrow(Offset(1f, center.y), Offset(6f, center.y - 4f), Offset(6f, center.y + 4f))
        arrow(Offset(size.width - 1f, center.y), Offset(size.width - 6f, center.y - 4f), Offset(size.width - 6f, center.y + 4f))
        drawCircle(c, radius = 2.4f, center = center)
    }
}

@Composable
private fun CheckIcon() {
    Canvas(modifier = Modifier.size(23.dp)) {
        drawLine(Color.White, Offset(size.width * 0.20f, size.height * 0.55f), Offset(size.width * 0.43f, size.height * 0.76f), strokeWidth = 3f)
        drawLine(Color.White, Offset(size.width * 0.43f, size.height * 0.76f), Offset(size.width * 0.82f, size.height * 0.26f), strokeWidth = 3f)
    }
}


private fun TextBoxAlign.next(): TextBoxAlign = when (this) {
    TextBoxAlign.Left -> TextBoxAlign.Right
    TextBoxAlign.Right -> TextBoxAlign.Center
    TextBoxAlign.Center -> TextBoxAlign.Justify
    TextBoxAlign.Justify -> TextBoxAlign.Left
}

private fun TextBoxAlign.toComposeTextAlign(): TextAlign = when (this) {
    TextBoxAlign.Left -> TextAlign.Start
    TextBoxAlign.Right -> TextAlign.End
    TextBoxAlign.Center -> TextAlign.Center
    TextBoxAlign.Justify -> TextAlign.Justify
}

private fun ImageEditAction.TextBox.visualSize(boxWidthOverride: Float = boxWidth): Size {
    val textSize = (boxWidthOverride / 7f).coerceIn(24f, 96f)
    val height = if (vertical) {
        textSize * text.length.coerceAtLeast(1) * 1.15f
    } else {
        textSize * 1.5f
    }
    return Size(boxWidthOverride, height)
}

private fun ImageEditAction.TextBox.toggleDirectionPreserveCenter(bitmap: Bitmap): ImageEditAction.TextBox {
    val oldSize = visualSize()
    val newVertical = !vertical
    val center = position + Offset(oldSize.width / 2f, oldSize.height / 2f)
    val rotatedWidth = oldSize.height.coerceIn(bitmap.width * 0.12f, bitmap.width.toFloat())
    val probe = copy(vertical = newVertical, boxWidth = rotatedWidth)
    val newSize = probe.visualSize(rotatedWidth)
    val newPosition = constrainTextPosition(
        center - Offset(newSize.width / 2f, newSize.height / 2f),
        probe,
        bitmap,
    )
    return probe.copy(position = newPosition)
}

private fun constrainTextPosition(
    position: Offset,
    action: ImageEditAction.TextBox,
    bitmap: Bitmap,
): Offset {
    val size = action.visualSize()
    val maxX = (bitmap.width - size.width).coerceAtLeast(0f)
    val maxY = (bitmap.height - size.height).coerceAtLeast(0f)
    return Offset(position.x.coerceIn(0f, maxX), position.y.coerceIn(0f, maxY))
}

private fun ImageEditAction.TextBox.contains(point: Offset): Boolean {
    val size = visualSize()
    return point.x in position.x..(position.x + size.width) && point.y in position.y..(position.y + size.height)
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
        val previewColor = when (action.style) {
            MosaicStyle.PixelCoarse, MosaicStyle.PixelFine -> Color.LightGray.copy(alpha = 0.62f)
            MosaicStyle.Blur -> Color.White.copy(alpha = 0.28f)
            MosaicStyle.Frosted -> Color.White.copy(alpha = 0.42f)
            MosaicStyle.Diamond -> Color.Gray.copy(alpha = 0.65f)
            MosaicStyle.ColorDiamond, MosaicStyle.Glass -> editorColors[(point.x.roundToInt() + point.y.roundToInt()).mod(editorColors.size)].copy(alpha = 0.58f)
        }
        drawRect(
            color = if (action.erase) Color.White.copy(alpha = 0.35f) else previewColor,
            topLeft = Offset(center.x - action.width / 2f, center.y - action.width / 2f),
            size = Size(action.width, action.width),
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

private fun List<ImageEditAction>.previewErase(
    tool: ImageEditTool,
    eraserPoints: List<Offset>,
    eraserWidth: Float,
): List<ImageEditAction> {
    if (eraserPoints.isEmpty()) return this
    return when (tool) {
        ImageEditTool.Draw -> buildList {
            this@previewErase.forEach { action ->
                if (action is ImageEditAction.Stroke) addAll(action.eraseBy(eraserPoints, eraserWidth)) else add(action)
            }
        }
        ImageEditTool.Mosaic -> mapNotNull { action ->
            if (action is ImageEditAction.Mosaic) action.eraseBy(eraserPoints, eraserWidth) else action
        }
        ImageEditTool.Arrow -> filterNot { action ->
            action is ImageEditAction.Arrow && eraserPoints.any { point ->
                distanceToSegment(point, action.start, action.end) <= eraserWidth * 1.5f
            }
        }
        ImageEditTool.Text -> this
    }
}

private fun ImageEditAction.Stroke.eraseBy(eraserPoints: List<Offset>, eraserWidth: Float): List<ImageEditAction.Stroke> {
    val segments = mutableListOf<MutableList<Offset>>()
    var current = mutableListOf<Offset>()
    points.forEach { point ->
        if (isNearEraser(point, eraserPoints, eraserWidth + width * 0.5f)) {
            if (current.size > 1) segments += current
            current = mutableListOf()
        } else {
            current += point
        }
    }
    if (current.size > 1) segments += current
    return segments.map { copy(points = it, erase = false) }
}

private fun ImageEditAction.Mosaic.eraseBy(eraserPoints: List<Offset>, eraserWidth: Float): ImageEditAction.Mosaic? {
    val kept = points.filterNot { point -> isNearEraser(point, eraserPoints, eraserWidth + width * 0.5f) }
    return copy(points = kept, erase = false).takeIf { kept.size > 1 }
}

private fun isNearEraser(point: Offset, eraserPoints: List<Offset>, threshold: Float): Boolean {
    if (eraserPoints.isEmpty()) return false
    if (eraserPoints.size == 1) return (point - eraserPoints.first()).getDistance() <= threshold
    return eraserPoints.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= threshold }
}

private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val lengthSquared = (end.x - start.x).pow(2) + (end.y - start.y).pow(2)
    if (lengthSquared == 0f) return (point - start).getDistance()
    val t = (((point.x - start.x) * (end.x - start.x) + (point.y - start.y) * (end.y - start.y)) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + t * (end.x - start.x), start.y + t * (end.y - start.y))
    return (point - projection).getDistance()
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

private fun expandBrushPoints(points: List<Offset>, width: Float): List<Offset> {
    if (points.size < 2) return points
    val result = mutableListOf<Offset>()
    val step = (width * 0.35f).coerceAtLeast(2f)
    points.zipWithNext().forEach { (a, b) ->
        result += a
        val distance = (b - a).getDistance()
        val count = (distance / step).roundToInt().coerceAtLeast(1)
        for (i in 1 until count) {
            val t = i / count.toFloat()
            result += Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }
    result += points.last()
    return result
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
        expandBrushPoints(stroke.points, stroke.width).forEach { restoreBaseCircle(canvas, base, it, stroke.width) }
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
    val rect = action.brushDirtyRect(output.width, output.height) ?: return
    val mask = action.createBrushMask(rect) ?: return
    val source = if (action.erase) base else output
    val sourceRoi = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
    val effect = if (action.erase) {
        sourceRoi
    } else {
        createMosaicEffect(sourceRoi, action.style, action.width)
    }
    val maskedPatch = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
    AndroidCanvas(maskedPatch).apply {
        drawBitmap(effect, 0f, 0f, null)
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            drawBitmap(mask, 0f, 0f, this)
            xfermode = null
        }
    }
    AndroidCanvas(output).drawBitmap(maskedPatch, rect.left.toFloat(), rect.top.toFloat(), null)
}

private fun ImageEditAction.Mosaic.brushDirtyRect(width: Int, height: Int): Rect? {
    if (points.isEmpty()) return null
    val radius = this.width / 2f + 2f
    var left = points.minOf { it.x } - radius
    var top = points.minOf { it.y } - radius
    var right = points.maxOf { it.x } + radius
    var bottom = points.maxOf { it.y } + radius
    left = left.coerceIn(0f, width - 1f)
    top = top.coerceIn(0f, height - 1f)
    right = right.coerceIn(left + 1f, width.toFloat())
    bottom = bottom.coerceIn(top + 1f, height.toFloat())
    return Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
        .takeIf { it.width() > 0 && it.height() > 0 }
}

private fun ImageEditAction.Mosaic.createBrushMask(rect: Rect): Bitmap? {
    if (points.isEmpty()) return null
    val mask = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(mask)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
    if (points.size == 1) {
        canvas.drawCircle(points.first().x, points.first().y, width / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        })
    } else {
        val path = Path()
        expandBrushPoints(points, width).forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        canvas.drawPath(path, paint)
    }
    return mask
}

private fun createMosaicEffect(source: Bitmap, style: MosaicStyle, width: Float): Bitmap {
    return when (style) {
        MosaicStyle.PixelCoarse -> pixelateBitmap(source, blockSize = width.coerceIn(18f, 64f).roundToInt())
        MosaicStyle.PixelFine -> pixelateBitmap(source, blockSize = (width * 0.42f).coerceIn(6f, 24f).roundToInt())
        MosaicStyle.Blur -> blurLikeBitmap(source, scaleDivisor = 8, overlayAlpha = 0)
        MosaicStyle.Frosted -> blurLikeBitmap(source, scaleDivisor = 10, overlayAlpha = 64)
        MosaicStyle.Diamond -> diamondMosaicBitmap(source, width, colored = false)
        MosaicStyle.ColorDiamond -> diamondMosaicBitmap(source, width, colored = true)
        MosaicStyle.Glass -> glassMosaicBitmap(source, width)
    }
}

private fun pixelateBitmap(source: Bitmap, blockSize: Int): Bitmap {
    val smallWidth = max(1, source.width / blockSize.coerceAtLeast(1))
    val smallHeight = max(1, source.height / blockSize.coerceAtLeast(1))
    val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, false)
    return Bitmap.createScaledBitmap(small, source.width, source.height, false)
}

private fun blurLikeBitmap(source: Bitmap, scaleDivisor: Int, overlayAlpha: Int): Bitmap {
    val small = Bitmap.createScaledBitmap(
        source,
        max(1, source.width / scaleDivisor.coerceAtLeast(2)),
        max(1, source.height / scaleDivisor.coerceAtLeast(2)),
        true,
    )
    val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
    if (overlayAlpha > 0) {
        AndroidCanvas(blurred).drawColor(android.graphics.Color.argb(overlayAlpha, 255, 255, 255))
    }
    return blurred
}

private fun diamondMosaicBitmap(source: Bitmap, width: Float, colored: Boolean): Bitmap {
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    val step = width.coerceIn(12f, 56f)
    var row = 0
    var cy = -step
    while (cy < source.height + step) {
        var col = 0
        var cx = if (row % 2 == 0) 0f else step / 2f
        while (cx < source.width + step) {
            val sampleX = cx.roundToInt().coerceIn(0, source.width - 1)
            val sampleY = cy.roundToInt().coerceIn(0, source.height - 1)
            val baseColor = source.getPixel(sampleX, sampleY)
            paint.color = if (colored) tintColor(baseColor, (row + col) % editorColors.size) else baseColor
            val r = step * 0.62f
            val path = Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx + r, cy)
                lineTo(cx, cy + r)
                lineTo(cx - r, cy)
                close()
            }
            canvas.drawPath(path, paint)
            cx += step
            col++
        }
        cy += step
        row++
    }
    return output
}

private fun glassMosaicBitmap(source: Bitmap, width: Float): Bitmap {
    val output = pixelateBitmap(source, blockSize = (width * 0.5f).coerceIn(8f, 36f).roundToInt())
    val canvas = AndroidCanvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = android.graphics.Color.argb(155, 255, 255, 255)
    }
    val step = width.coerceIn(18f, 56f)
    var x = -source.height.toFloat()
    while (x < source.width + source.height) {
        canvas.drawLine(x, 0f, x + source.height, source.height.toFloat(), paint)
        x += step
    }
    val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(42, 255, 255, 255)
    }
    canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), overlay)
    return output
}

private fun tintColor(baseColor: Int, index: Int): Int {
    val tint = editorColors[index].copy(alpha = 0.28f).toArgb()
    val br = android.graphics.Color.red(baseColor)
    val bg = android.graphics.Color.green(baseColor)
    val bb = android.graphics.Color.blue(baseColor)
    val tr = android.graphics.Color.red(tint)
    val tg = android.graphics.Color.green(tint)
    val tb = android.graphics.Color.blue(tint)
    return android.graphics.Color.rgb(
        ((br * 0.72f) + (tr * 0.28f)).roundToInt().coerceIn(0, 255),
        ((bg * 0.72f) + (tg * 0.28f)).roundToInt().coerceIn(0, 255),
        ((bb * 0.72f) + (tb * 0.28f)).roundToInt().coerceIn(0, 255),
    )
}



private fun renderText(output: Bitmap, action: ImageEditAction.TextBox) {
    val canvas = AndroidCanvas(output)
    val textSize = (action.boxWidth / 7f).coerceIn(24f, 96f)
    val boxSize = action.visualSize()
    if (action.bordered) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = action.color.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            canvas.drawRect(action.position.x, action.position.y, action.position.x + boxSize.width, action.position.y + boxSize.height, this)
        }
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = action.color.toArgb()
        this.textSize = textSize
        typeface = if (action.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        style = Paint.Style.FILL
    }
    if (action.vertical) {
        paint.textAlign = Paint.Align.CENTER
        action.text.forEachIndexed { index, char ->
            canvas.drawText(char.toString(), action.position.x + action.boxWidth / 2f, action.position.y + textSize * (index + 1), paint)
        }
    } else {
        val (paintAlign, x) = when (action.align) {
            TextBoxAlign.Left, TextBoxAlign.Justify -> Paint.Align.LEFT to action.position.x
            TextBoxAlign.Right -> Paint.Align.RIGHT to (action.position.x + action.boxWidth)
            TextBoxAlign.Center -> Paint.Align.CENTER to (action.position.x + action.boxWidth / 2f)
        }
        paint.textAlign = paintAlign
        canvas.drawText(action.text, x, action.position.y + paint.textSize, paint)
    }
}


private fun restoreBaseCircle(canvas: AndroidCanvas, base: Bitmap, center: Offset, width: Float) {
    val radius = width / 2f
    val left = (center.x - radius).roundToInt().coerceIn(0, base.width - 1)
    val top = (center.y - radius).roundToInt().coerceIn(0, base.height - 1)
    val right = (center.x + radius).roundToInt().coerceIn(left + 1, base.width)
    val bottom = (center.y + radius).roundToInt().coerceIn(top + 1, base.height)
    val patch = Bitmap.createBitmap(base, left, top, right - left, bottom - top)
    canvas.drawBitmap(patch, left.toFloat(), top.toFloat(), null)
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
