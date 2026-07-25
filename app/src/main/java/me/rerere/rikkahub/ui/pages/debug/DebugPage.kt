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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.assumeLatexSize
import me.rerere.rikkahub.ui.components.richtext.Mermaid
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Debug Mode")
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

        LatexBaselineDebugSection()
    }
}

@Composable
private fun LatexBaselineDebugSection() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val density = LocalDensity.current
    var markdown by remember {
        mutableStateOf(
            """
            我们有 ${'$'}a=b${'$'}，接下来：

            $$
            b=c
            $$

            所以 ${'$'}a=c${'$'}。

            我们有 ${'$'}\int_0^1 f(x)\,dx=F(1)-F(0)${'$'}，所以 ${'$'}\int_0^1 x\,dx=\frac12${'$'}。
            """.trimIndent()
        )
    }
    val svg = remember(markdown, density) {
        with(density) { buildLatexBaselineSvg(markdown, fontSizePx = 18.dp.toPx()) }
    }

    HorizontalDivider()
    Text("LaTeX Baseline Debug", style = MaterialTheme.typography.labelMedium)
    Text(
        "输入 Markdown/LaTeX 混排文本。下面会正常渲染，同时生成带 baseline 和公式占位框的 SVG 诊断图。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = markdown,
        onValueChange = { markdown = it },
        label = { Text("Markdown / LaTeX") },
        minLines = 5,
        maxLines = 14,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("实际 Markdown 渲染", style = MaterialTheme.typography.labelMedium)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        MarkdownBlock(markdown, modifier = Modifier.fillMaxWidth())
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("latex-baseline-debug.svg", svg))
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
}

private fun buildLatexBaselineSvg(markdown: String, fontSizePx: Float): String {
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
                val bounds = assumeLatexSize(segment.text, fontSizePx)
                val boxW = bounds.width().coerceAtLeast(1).toFloat()
                val boxH = bounds.height().coerceAtLeast(1).toFloat()
                val boxTop = baseline - boxH / 2f
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
