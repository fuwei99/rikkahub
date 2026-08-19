package me.rerere.rikkahub.ui.pages.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.nav.BackButton
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import me.rerere.rikkahub.ui.components.richtext.MarkdownRenderTrace
import me.rerere.rikkahub.ui.components.richtext.measureInlineMath
import me.rerere.rikkahub.ui.components.richtext.Mermaid
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.richtext.processLatex

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("调试面板")
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        val state = rememberPagerState { 3 }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.currentPage,
            ) {
                Tab(
                    selected = state.currentPage == 0,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text("Main")
                    }
                )
                Tab(
                    selected = state.currentPage == 1,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text("Colors")
                    }
                )
                Tab(
                    selected = state.currentPage == 2,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(2)
                        }
                    },
                    text = {
                        Text("Logging")
                    }
                )
            }
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> MainPage(vm)
                    1 -> ColorsPage()
                    2 -> Box {}
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: DebugVM) {
    val settings = LocalSettings.current
    val conversationCount by vm.conversationCount.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ChatBubblePreviewSection()

        HorizontalDivider()

        var avatar: Avatar by remember { mutableStateOf(Avatar.Emoji("😎")) }
        UIAvatar(
            value = avatar,
            onUpdate = {
                println("Avatar updated: $it")
                avatar = it
            },
            name = "A"
        )
        Mermaid(
            code = """
                mindmap
                  root((mindmap))
                    Origins
                      Long history
                      ::icon(fa fa-book)
                      Popularisation
                        British popular psychology author Tony Buzan
                    Research
                      On effectiveness<br/>and features
                      On Automatic creation
                        Uses
                            Creative techniques
                            Strategic planning
                            Argument mapping
                    Tools
                      Pen and paper
                      Mermaid
                """.trimIndent(),
            modifier = Modifier.fillMaxWidth(),
        )

        var counter by remember {
            mutableIntStateOf(0)
        }
        val toaster = LocalToaster.current
        Button(
            onClick = {
                toaster.show("测试 ${counter++}")
                toaster.show("测试 ${counter++}", type = ToastType.Info)
                toaster.show("测试 ${counter++}", type = ToastType.Error)
            }
        ) {
            Text("toast")
        }
        Button(
            onClick = {
                vm.updateSettings(
                    settings.copy(
                        chatModelId = Uuid.random()
                    )
                )
            }
        ) {
            Text("重置Chat模型")
        }

        Button(
            onClick = {
                error("测试崩溃 ${Random.nextInt(0..1000)}")
            }
        ) {
            Text("崩溃")
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Conversation 数量: ${conversationCount?.toString() ?: "..."}",
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.refreshConversationCount() }) {
                Text("刷新")
            }
        }

        Button(
            onClick = {
                vm.createOversizedConversation(30)
                toaster.show("正在创建 30MB 超大对话...")
            }
        ) {
            Text("创建超大对话 (30MB)")
        }

        Button(
            onClick = {
                vm.createConversationWithMessages(1024)
                toaster.show("正在创建 1024 条消息对话...")
            }
        ) {
            Text("创建 1024 个消息的聊天")
        }

        HorizontalDivider()

        Text("Launch Stats", style = MaterialTheme.typography.labelMedium)

        var launchCountInput by remember(settings.launchCount) {
            mutableStateOf(settings.launchCount.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = launchCountInput,
                onValueChange = { launchCountInput = it },
                label = { Text("launchCount (current: ${settings.launchCount})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                launchCountInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(launchCount = it))
                }
            }) {
                Text("Set")
            }
        }

        var dismissedAtInput by remember(settings.sponsorAlertDismissedAt) {
            mutableStateOf(settings.sponsorAlertDismissedAt.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = dismissedAtInput,
                onValueChange = { dismissedAtInput = it },
                label = { Text("sponsorAlertDismissedAt (current: ${settings.sponsorAlertDismissedAt})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                dismissedAtInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = it))
                }
            }) {
                Text("Set")
            }
        }

    }
}

/**
 * 调试面板的第一项：直接复用聊天页面的 ChatMessage，避免调试页自己重新实现气泡。
 * 这样输入确认后的结果会经过与真实聊天完全相同的消息节点和 Markdown 渲染链路。
 */
@Composable
private fun ChatBubblePreviewSection() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val latexMeasurer = rememberLatexMeasurer()
    val toaster = LocalToaster.current
    val chatFontSize = LocalTextStyle.current.fontSize.takeOrElse { 16.sp }
    val svg = remember(renderedMarkdown, density, latexMeasurer, chatFontSize) {
        with(density) {
            buildLatexBaselineSvg(
                renderedMarkdown,
                fontSizePx = chatFontSize.toPx(),
                measurer = latexMeasurer,
                density = this,
            )
        }
    }
    DisposableEffect(Unit) {
        MarkdownRenderTrace.start()
        onDispose { MarkdownRenderTrace.stop() }
    }
    var draft by rememberSaveable {
        mutableStateOf(
            "别想在单位化那点分母 ${'$'}\\frac{1}{\\sqrt{3}}, \\frac{1}{\\sqrt{2}}, \\frac{1}{\\sqrt{6}}${'$'} 上偷懒"
        )
    }
    var renderedMarkdown by rememberSaveable { mutableStateOf(draft) }
    val messageNode = remember(renderedMarkdown) {
        MessageNode.of(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(renderedMarkdown)),
            )
        )
    }

    Text("聊天气泡渲染检查", style = MaterialTheme.typography.titleMedium)
    Text(
        "这里直接调用聊天界面的 ChatMessage。编辑内容后点击确认，再观察 Markdown / LaTeX 的真实渲染结果。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (editing) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Markdown / LaTeX") },
            minLines = 5,
            maxLines = 16,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                MarkdownRenderTrace.clear()
                renderedMarkdown = draft
                editing = false
            }) {
                Text("确认渲染")
            }
            Button(onClick = {
                draft = renderedMarkdown
                editing = false
            }) {
                Text("取消")
            }
        }
    } else {
        Button(onClick = { editing = true }) {
            Text("编辑内容")
        }
    }

    ChatMessage(
        node = messageNode,
        modifier = Modifier.fillMaxWidth(),
        loading = false,
        model = null,
        assistant = null,
        lastMessage = false,
        onFork = {},
        onRegenerate = {},
        onEdit = { editing = true },
        onShare = {},
        onDelete = {},
        onUpdate = {},
    )

    Text("SVG 辅助诊断图", style = MaterialTheme.typography.labelMedium)
    Text(
        "这是根据同一段输入和实际聊天字号生成的估算图，不替代真实气泡；真实坐标会写入保存日志。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("markdown-render-debug.svg", svg))
            toaster.show("SVG 已复制")
        }) {
            Text("复制 SVG")
        }
    }
    OutlinedTextField(
        value = svg,
        onValueChange = {},
        label = { Text("SVG 诊断图文本") },
        minLines = 6,
        maxLines = 12,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
    )

    Button(onClick = {
        val folder = saveMarkdownRenderLog(
            context = context,
            markdown = renderedMarkdown,
            density = density,
            latexMeasurer = latexMeasurer,
            fontSize = chatFontSize,
        )
        toaster.show("渲染日志已保存：${folder.name}")
    }) {
        Text("保存日志")
    }
}

private val debugInlineMathRegex = Regex("""(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)""")

private fun saveMarkdownRenderLog(
    context: android.content.Context,
    markdown: String,
    density: androidx.compose.ui.unit.Density,
    latexMeasurer: LatexMeasurerState,
    fontSize: TextUnit,
): File {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val folder = File(AppPaths.filesDir(context), "logs/markdown-render/$timestamp")
    folder.mkdirs()
    File(folder, "input.md").writeText(markdown)

    val formulas = debugInlineMathRegex.findAll(markdown).mapIndexed { index, match ->
        val raw = match.value
        val formula = match.groupValues[1]
        val processed = processLatex(formula, inline = true)
        val dimensions = latexMeasurer.measureInlineMath(formula, fontSize)
        buildString {
            appendLine("### Formula ${index + 1}")
            appendLine("- rawRange: ${match.range}")
            appendLine("- raw: `$raw`")
            appendLine("- formula: `${formula.replace("`", "\\`")}`")
            appendLine("- processed: `${processed.replace("`", "\\`")}`")
            if (dimensions == null) {
                appendLine("- measure: null")
            } else {
                val widthSp = with(density) { dimensions.widthPx.toSp() }
                val heightSp = with(density) { dimensions.heightPx.toSp() }
                appendLine("- dimensions.widthPx: ${dimensions.widthPx}")
                appendLine("- dimensions.heightPx: ${dimensions.heightPx}")
                appendLine("- dimensions.baselinePx: ${dimensions.baselinePx}")
                appendLine("- dimensions.contentWidthPx: ${dimensions.contentWidthPx}")
                appendLine("- dimensions.contentHeightPx: ${dimensions.contentHeightPx}")
                appendLine("- dimensions.contentBaselinePx: ${dimensions.contentBaselinePx}")
                appendLine("- placeholder.widthSp: $widthSp")
                appendLine("- placeholder.heightSp: $heightSp")
                appendLine("- placeholder.widthRoundTripPx: ${with(density) { widthSp.toPx() }}")
                appendLine("- placeholder.heightRoundTripPx: ${with(density) { heightSp.toPx() }}")
                appendLine("- placeholder.verticalAlign: TextCenter")
            }
            appendLine()
        }
    }.toList()

    val (textLayouts, latexLayouts) = MarkdownRenderTrace.snapshot()

    File(folder, "render-trace.md").writeText(
        buildString {
            appendLine("# Markdown 渲染调试日志")
            appendLine()
            appendLine("## Environment")
            appendLine("- timestamp: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())}")
            appendLine("- appVersion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("- android: ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
            appendLine("- density: ${density.density}")
            appendLine("- fontScale: ${density.fontScale}")
            appendLine("- renderingPath: ChatMessage -> MarkdownBlock")
            appendLine("- fontSize: $fontSize")
            appendLine("- markdownLength: ${markdown.length}")
            appendLine()
            appendLine("## Raw Markdown")
            appendLine("```markdown")
            appendLine(markdown)
            appendLine("```")
            appendLine()
            appendLine("## Formula Measurements")
            if (formulas.isEmpty()) appendLine("No inline dollar formulas found.")
            formulas.forEach { append(it) }
            appendLine()
            appendLine("## Runtime TextLayoutResult")
            if (textLayouts.isEmpty()) {
                appendLine("No runtime TextLayoutResult captured.")
            } else {
                textLayouts.forEachIndexed { layoutIndex, layout ->
                    appendLine("### Layout ${layoutIndex + 1}")
                    appendLine("- textLength: ${layout.text.length}")
                    appendLine("- layoutWidthPx: ${layout.widthPx}")
                    appendLine("- layoutHeightPx: ${layout.heightPx}")
                    appendLine("- lineCount: ${layout.lineCount}")
                    layout.replacementBoxes.forEachIndexed { index, box ->
                        appendLine("- replacement[$index].index: ${box.index}")
                        appendLine("- replacement[$index].box: ${box.leftPx},${box.topPx} - ${box.rightPx},${box.bottomPx}")
                        appendLine("- replacement[$index].nextBox: ${box.nextLeftPx},${box.nextTopPx} - ${box.nextRightPx},${box.nextBottomPx}")
                        appendLine("- replacement[$index].line: ${box.lineIndex} top=${box.lineTopPx} bottom=${box.lineBottomPx} baseline=${box.lineBaselinePx}")
                    }
                }
            }
            appendLine()
            appendLine("## Runtime Latex Layout")
            if (latexLayouts.isEmpty()) {
                appendLine("No runtime Latex layout captured.")
            } else {
                latexLayouts.forEachIndexed { index, layout ->
                    appendLine("### Latex Layout ${index + 1}")
                    appendLine("- latex: `${layout.latex.replace("`", "\\`")}`")
                    appendLine("- requestedWidthPx: ${layout.requestedWidthPx}")
                    appendLine("- requestedHeightPx: ${layout.requestedHeightPx}")
                    appendLine("- measuredWidthPx: ${layout.measuredWidthPx}")
                    appendLine("- measuredHeightPx: ${layout.measuredHeightPx}")
                    appendLine("- windowBoundsPx: ${layout.leftPx},${layout.topPx} - ${layout.rightPx},${layout.bottomPx}")
                }
            }
        }
    )
    return folder
}

private fun buildLatexBaselineSvg(
    markdown: String,
    fontSizePx: Float,
    measurer: LatexMeasurerState,
    density: androidx.compose.ui.unit.Density,
): String {
    val width = 900f
    val lineHeight = fontSizePx * 2.1f
    val baselineOffset = fontSizePx * 1.35f
    val textCharWidth = fontSizePx * 0.56f
    val rows = markdown.lines().ifEmpty { listOf("") }
    val height = (rows.size.coerceAtLeast(1) * lineHeight + 24f).toInt()
    val svg = StringBuilder()
    svg.appendLine("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${width.toInt()}\" height=\"$height\" viewBox=\"0 0 ${width.toInt()} $height\">")
    svg.appendLine("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
    rows.forEachIndexed { rowIndex, line ->
        val yTop = 12f + rowIndex * lineHeight
        val baseline = yTop + baselineOffset
        svg.appendLine("<line x1=\"0\" y1=\"${baseline.fmt()}\" x2=\"${width.toInt()}\" y2=\"${baseline.fmt()}\" stroke=\"red\" stroke-width=\"1\" stroke-dasharray=\"4 4\"/>")
        var x = 12f
        parseInlineMathSegments(line).forEach { segment ->
            if (segment.isMath) {
                val fontSize = with(density) { fontSizePx.toSp() }
                val dims = measurer.measureInlineMath(segment.text, fontSize)
                val boxW = (dims?.widthPx ?: 1f).coerceAtLeast(1f)
                val boxH = (dims?.heightPx ?: 1f).coerceAtLeast(1f)
                val ascent = (dims?.baselinePx ?: boxH / 2f)
                // 公式基线与正文基线对齐：盒顶 = 基线 - ascent
                val boxTop = baseline - ascent
                svg.appendLine("<rect x=\"${x.fmt()}\" y=\"${boxTop.fmt()}\" width=\"${boxW.fmt()}\" height=\"${boxH.fmt()}\" fill=\"rgba(30,144,255,0.10)\" stroke=\"blue\" stroke-width=\"1\"/>")
                svg.appendLine("<text x=\"${(x + 2).fmt()}\" y=\"${(baseline - 3).fmt()}\" fill=\"#0645ad\" font-size=\"10\">${segment.text.escapeXml()}</text>")
                x += boxW + 2f
            } else {
                svg.appendLine("<text x=\"${x.fmt()}\" y=\"${baseline.fmt()}\" fill=\"black\" font-size=\"${fontSizePx.fmt()}\" dominant-baseline=\"alphabetic\">${segment.text.escapeXml()}</text>")
                x += segment.text.length * textCharWidth
            }
        }
    }
    svg.appendLine("</svg>")
    return svg.toString()
}

private data class LatexDebugSegment(val text: String, val isMath: Boolean)

private fun parseInlineMathSegments(line: String): List<LatexDebugSegment> {
    val result = mutableListOf<LatexDebugSegment>()
    var i = 0
    while (i < line.length) {
        val start = line.indexOf('$', i)
        if (start < 0) {
            if (i < line.length) result += LatexDebugSegment(line.substring(i), false)
            break
        }
        if (start > i) result += LatexDebugSegment(line.substring(i, start), false)
        val end = line.indexOf('$', start + 1)
        if (end < 0) {
            result += LatexDebugSegment(line.substring(start), false)
            break
        }
        result += LatexDebugSegment(line.substring(start + 1, end), true)
        i = end + 1
    }
    return result
}

private fun Float.fmt(): String = "%.2f".format(java.util.Locale.US, this)

private fun String.escapeXml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

@Composable
private fun ColorsPage() {
    val colorScheme = MaterialTheme.colorScheme
    val colorTokens = remember(colorScheme) {
        listOf(
            "primary" to colorScheme.primary,
            "onPrimary" to colorScheme.onPrimary,
            "primaryContainer" to colorScheme.primaryContainer,
            "onPrimaryContainer" to colorScheme.onPrimaryContainer,
            "inversePrimary" to colorScheme.inversePrimary,
            "secondary" to colorScheme.secondary,
            "onSecondary" to colorScheme.onSecondary,
            "secondaryContainer" to colorScheme.secondaryContainer,
            "onSecondaryContainer" to colorScheme.onSecondaryContainer,
            "tertiary" to colorScheme.tertiary,
            "onTertiary" to colorScheme.onTertiary,
            "tertiaryContainer" to colorScheme.tertiaryContainer,
            "onTertiaryContainer" to colorScheme.onTertiaryContainer,
            "background" to colorScheme.background,
            "onBackground" to colorScheme.onBackground,
            "surface" to colorScheme.surface,
            "onSurface" to colorScheme.onSurface,
            "surfaceVariant" to colorScheme.surfaceVariant,
            "onSurfaceVariant" to colorScheme.onSurfaceVariant,
            "surfaceTint" to colorScheme.surfaceTint,
            "inverseSurface" to colorScheme.inverseSurface,
            "inverseOnSurface" to colorScheme.inverseOnSurface,
            "surfaceBright" to colorScheme.surfaceBright,
            "surfaceDim" to colorScheme.surfaceDim,
            "surfaceContainer" to colorScheme.surfaceContainer,
            "surfaceContainerHigh" to colorScheme.surfaceContainerHigh,
            "surfaceContainerHighest" to colorScheme.surfaceContainerHighest,
            "surfaceContainerLow" to colorScheme.surfaceContainerLow,
            "surfaceContainerLowest" to colorScheme.surfaceContainerLowest,
            "error" to colorScheme.error,
            "onError" to colorScheme.onError,
            "errorContainer" to colorScheme.errorContainer,
            "onErrorContainer" to colorScheme.onErrorContainer,
            "outline" to colorScheme.outline,
            "outlineVariant" to colorScheme.outlineVariant,
            "scrim" to colorScheme.scrim,
            "primaryFixed" to colorScheme.primaryFixed,
            "primaryFixedDim" to colorScheme.primaryFixedDim,
            "onPrimaryFixed" to colorScheme.onPrimaryFixed,
            "onPrimaryFixedVariant" to colorScheme.onPrimaryFixedVariant,
            "secondaryFixed" to colorScheme.secondaryFixed,
            "secondaryFixedDim" to colorScheme.secondaryFixedDim,
            "onSecondaryFixed" to colorScheme.onSecondaryFixed,
            "onSecondaryFixedVariant" to colorScheme.onSecondaryFixedVariant,
            "tertiaryFixed" to colorScheme.tertiaryFixed,
            "tertiaryFixedDim" to colorScheme.tertiaryFixedDim,
            "onTertiaryFixed" to colorScheme.onTertiaryFixed,
            "onTertiaryFixedVariant" to colorScheme.onTertiaryFixedVariant,
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(colorTokens, key = { it.first }) { (name, color) ->
            ColorTokenItem(name, color)
        }
    }
}

@Composable
private fun ColorTokenItem(name: String, color: Color) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
        )
        Column(modifier = Modifier.weight(2f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                color.toHexString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Color.toHexString(): String {
    val argb = toArgb()
    return "#%08X".format(argb)
}
