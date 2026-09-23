package me.rerere.rikkahub.ui.components.richtext

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.highlight.HighlightText
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.highlight.buildHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toDp
import kotlin.time.Clock

private const val COLLAPSE_LINES = 10
private val PREVIEWABLE_LANGUAGES = setOf("html", "svg")

/**
 * 内联预览默认就铺开的语言。
 *
 * SVG 体积一般可控，铺开看正好；HTML 可能是一整页，全铺开能把整轮对话顶飞，
 * 所以默认收在 [COLLAPSED_PREVIEW_HEIGHT] 里，想看自己点展开。
 */
private val PREVIEW_EXPANDED_BY_DEFAULT = setOf("svg")

/** 内联预览折叠时的高度 */
private val COLLAPSED_PREVIEW_HEIGHT = 200.dp

/** 内联预览展开时占屏幕高度的比例 */
private const val EXPANDED_PREVIEW_SCREEN_FRACTION = 0.7f

/** 展开高度的下限，屏幕再矮也别压成一条缝 */
private val EXPANDED_PREVIEW_MIN_HEIGHT = 320.dp

/**
 * 展开图标：上下两个尖角朝外（^ ∨）。
 *
 * 路径照设计稿手写，不去赌第三方图标库里的名字 —— 引错一个名字整包编译红，
 * 收益完全不值。
 */
private val ExpandPreviewIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ExpandPreview",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(8f, 9f); lineTo(12f, 5f); lineTo(16f, 9f)
            moveTo(8f, 15f); lineTo(12f, 19f); lineTo(16f, 15f)
        }
    }.build()
}

/** 折叠图标：两个对着的实心三角，尖头相对（fold） */
private val FoldPreviewIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "FoldPreview",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 5f); lineTo(16f, 5f); lineTo(12f, 10f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 19f); lineTo(16f, 19f); lineTo(12f, 14f); close()
        }
    }.build()
}

@Composable
fun HighlightCodeBlock(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    completeCodeBlock: Boolean = true,
    style: TextStyle? = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    /**
     * 是否允许把 html/svg 当网页内联预览。
     *
     * `workspace_read_file` 这类「只读回来一段」的场景必须关掉 —— 它返回的是文件的一个
     * 切片，半截 HTML 渲染出来就是一片空白，还不如老老实实显示源码。
     */
    allowPreview: Boolean = true,
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings = LocalSettings.current
    val normalizedLanguage = remember(language) { language.lowercase() }
    val canInlinePreview =
        allowPreview && completeCodeBlock && normalizedLanguage in PREVIEWABLE_LANGUAGES
    var previewMode by remember(canInlinePreview, code, normalizedLanguage) {
        mutableStateOf(canInlinePreview)
    }

    // SVG 默认铺开，HTML 默认收着；切换按钮挂在动作栏眼睛旁边，原地展开不跳页
    var previewExpanded by remember(canInlinePreview, code, normalizedLanguage) {
        mutableStateOf(normalizedLanguage in PREVIEW_EXPANDED_BY_DEFAULT)
    }
    val expandedPreviewHeight = with(LocalConfiguration.current) {
        (screenHeightDp * EXPANDED_PREVIEW_SCREEN_FRACTION).dp
    }.coerceAtLeast(EXPANDED_PREVIEW_MIN_HEIGHT)

    var isExpanded by remember(settings.displaySetting.codeBlockAutoCollapse) {
        mutableStateOf(!settings.displaySetting.codeBlockAutoCollapse)
    }
    val autoWrap = settings.displaySetting.codeBlockAutoWrap
    val showLineNumbers = settings.displaySetting.showLineNumbers

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(code.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            HighlightCodeActions(
                language = language,
                scope = scope,
                clipboardManager = clipboardManager,
                code = code,
                createDocumentLauncher = createDocumentLauncher,
                navController = navController,
                completeCodeBlock = completeCodeBlock,
                previewMode = previewMode,
                canInlinePreview = canInlinePreview,
                onTogglePreviewMode = {
                    previewMode = !previewMode
                },
                previewExpanded = previewExpanded,
                onTogglePreviewExpanded = {
                    previewExpanded = !previewExpanded
                },
            )
        }
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
        ) {
            when {
                canInlinePreview && previewMode -> {
                    CodeBlockPreview(
                        code = code,
                        language = normalizedLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(
                                if (previewExpanded) expandedPreviewHeight else COLLAPSED_PREVIEW_HEIGHT
                            ),
                    )
                }
                completeCodeBlock && normalizedLanguage == "mermaid" -> {
                    Mermaid(
                        code = code,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    val textStyle = LocalTextStyle.current.merge(style)
                    val codeLines = remember(code) { code.lines() }
                    val collapsedCode = remember(codeLines) { codeLines.take(COLLAPSE_LINES).joinToString("\n") }
                    val displayCode = if (isExpanded) code else collapsedCode
                    val displayLines = remember(displayCode) { displayCode.lines() }

                    // 如果显示行号且自动换行，需要逐行渲染以保持对齐
                    when {
                        showLineNumbers && autoWrap -> {
                            CodeBlockWithLineNumbersWrapped(
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                            )
                        }
                        else -> {
                            CodeBlockDefault(
                                displayCode = displayCode,
                                displayLines = displayLines,
                                language = language,
                                textStyle = textStyle,
                                colorPalette = colorPalette,
                                autoWrap = autoWrap,
                                showLineNumbers = showLineNumbers,
                                scrollState = scrollState,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    // 代码折叠按钮
                    if (settings.displaySetting.codeBlockAutoCollapse && codeLines.size > COLLAPSE_LINES) {
                        Box(
                            modifier = Modifier
                                .onClick {
                                    isExpanded = !isExpanded
                                }
                                .fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(textStyle.fontSize.toDp())
                                )
                                Text(
                                    text = if (isExpanded) {
                                        stringResource(id = R.string.code_block_collapse)
                                    } else {
                                        stringResource(id = R.string.code_block_expand)
                                    },
                                    fontSize = textStyle.fontSize,
                                    lineHeight = textStyle.lineHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockWithLineNumbersWrapped(
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
) {
    val lineNumberWidth = remember(displayLines.size) {
        displayLines.size.toString().length
    }
    SelectionContainer {
        Column {
            displayLines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    HighlightText(
                        code = line,
                        language = language,
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        colors = colorPalette,
                        overflow = TextOverflow.Visible,
                        softWrap = true,
                        fontFamily = JetbrainsMono,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockDefault(
    displayCode: String,
    displayLines: List<String>,
    language: String,
    textStyle: TextStyle,
    colorPalette: HighlightTextColorPalette,
    autoWrap: Boolean,
    showLineNumbers: Boolean,
    scrollState: ScrollState,
) {
    Row(
        modifier = Modifier.then(
            if (autoWrap) {
                Modifier
            } else {
                Modifier.horizontalScroll(scrollState)
            }
        )
    ) {
        // 行号列
        if (showLineNumbers) {
            val lineNumberWidth = remember(displayLines.size) {
                displayLines.size.toString().length
            }
            Column(
                modifier = Modifier.padding(end = 8.dp)
            ) {
                displayLines.forEachIndexed { index, _ ->
                    Text(
                        text = (index + 1).toString().padStart(lineNumberWidth, ' '),
                        fontSize = textStyle.fontSize,
                        lineHeight = textStyle.lineHeight,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        softWrap = false,
                    )
                }
            }
        }

        // 代码列
        SelectionContainer {
            HighlightText(
                code = displayCode,
                language = language,
                modifier = Modifier.animateContentSize(),
                fontSize = textStyle.fontSize,
                lineHeight = textStyle.lineHeight,
                colors = colorPalette,
                overflow = TextOverflow.Visible,
                softWrap = autoWrap,
                fontFamily = JetbrainsMono
            )
        }
    }
}

@Composable
private fun HighlightCodeActions(
    language: String,
    scope: CoroutineScope,
    clipboardManager: Clipboard,
    code: String,
    createDocumentLauncher: ManagedActivityResultLauncher<String, Uri?>,
    navController: Navigator,
    completeCodeBlock: Boolean = true,
    previewMode: Boolean = false,
    canInlinePreview: Boolean = false,
    onTogglePreviewMode: () -> Unit = {},
    previewExpanded: Boolean = false,
    onTogglePreviewExpanded: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = language,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = 0.5f),
        )
        Spacer(Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconSize = 16.dp
            val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

            Icon(
                imageVector = HugeIcons.Download04,
                contentDescription = stringResource(id = R.string.chat_page_save),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        val extension = when (language.lowercase()) {
                            "kotlin" -> "kt"
                            "java" -> "java"
                            "python" -> "py"
                            "javascript" -> "js"
                            "typescript" -> "ts"
                            "cpp", "c++" -> "cpp"
                            "c" -> "c"
                            "html" -> "html"
                            "css" -> "css"
                            "xml" -> "xml"
                            "json" -> "json"
                            "yaml", "yml" -> "yml"
                            "markdown", "md" -> "md"
                            "sql" -> "sql"
                            "sh", "bash" -> "sh"
                            "svg" -> "svg"
                            else -> "txt"
                        }
                        createDocumentLauncher.launch(
                            "code_${
                                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                            }.$extension"
                        )
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = stringResource(id = R.string.code_block_copy),
                tint = iconTint,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .onClick {
                        scope.launch {
                            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                        }
                    }
                    .padding(4.dp)
                    .size(iconSize)
            )

            val normalizedLanguage = language.lowercase()
            if (canInlinePreview) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Code else HugeIcons.View,
                    contentDescription = if (previewMode) "Code" else stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            onTogglePreviewMode()
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }

            // 原地展开/折叠：只有内联预览真的在显示时才谈得上
            if (canInlinePreview && previewMode) {
                Icon(
                    imageVector = if (previewExpanded) FoldPreviewIcon else ExpandPreviewIcon,
                    contentDescription = if (previewExpanded) "折叠" else "展开",
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick { onTogglePreviewExpanded() }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }

            // 眼睛跳全屏和内联预览共用同一条闸: 内容本来就不完整时, 跳过去也是白板
            if (canInlinePreview) {
                Icon(
                    imageVector = HugeIcons.Eye,
                    contentDescription = stringResource(id = R.string.code_block_preview),
                    tint = iconTint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onClick {
                            val content = buildCodePreviewHtml(code = code, language = normalizedLanguage)
                            val contentId = WebViewContentCache.store(context.cacheDir, content)
                            navController.navigate(Screen.WebView(contentId = contentId))
                        }
                        .padding(4.dp)
                        .size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockPreview(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = buildCodePreviewHtml(code = code, language = language),
        baseUrl = "https://rikkahub.local",
        mimeType = "text/html",
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    )

    WebView(
        state = state,
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
    )
}

private fun buildCodePreviewHtml(code: String, language: String): String {
    return if (language == "svg") {
        """<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
    } else {
        code
    }
}

class HighlightCodeVisualTransformation(
    val language: String,
    val highlighter: Highlighter,
    val darkMode: Boolean
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotatedString = try {
            val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
            if (text.text.isEmpty()) {
                AnnotatedString("")
            } else {
                runBlocking {
                    val tokens = highlighter.highlight(text.text, language)
                    buildAnnotatedString {
                        tokens.forEach { token ->
                            buildHighlightText(token, colorPalette)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AnnotatedString(text.text)
        }

        return TransformedText(
            text = annotatedString,
            offsetMapping = OffsetMapping.Identity
        )
    }

    companion object {
        @Composable
        fun regex() = HighlightCodeVisualTransformation(
            language = "regex",
            highlighter = LocalHighlighter.current,
            darkMode = LocalDarkMode.current,
        )
    }
}
