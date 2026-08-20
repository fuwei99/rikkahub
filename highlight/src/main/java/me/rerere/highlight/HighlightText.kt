package me.rerere.highlight

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest

val LocalHighlighter = compositionLocalOf<Highlighter> { error("No Highlighter provided") }

/**
 * 单次送进 Prism(QuickJS) 的最大字符数。
 *
 * 这不是「超过就不高亮」的开关，而是分块粒度：长文本按行切成若干块逐块高亮，
 * 否则一次塞几十 KB 进 QuickJS 会明显卡。
 */
private const val MAX_CODE_LENGTH = 4096

/**
 * 完全放弃高亮的总长度上限。
 *
 * 超过这个量级（约 200KB）再逐块跑 Prism 收益也不大（屏幕上根本看不完），
 * 直接纯文本，省电省 CPU。
 */
private const val MAX_HIGHLIGHT_TOTAL_LENGTH = 200 * 1024

/**
 * 把代码按行切成不超过 [MAX_CODE_LENGTH] 的块，拼接后与原文严格相等。
 *
 * 按行切是为了尽量不破坏 token 边界；单行本身超长时才硬切。
 * 代价是跨块的多行结构（块注释、多行字符串）在块边界会断开高亮 ——
 * 比整个文件白给要好得多。
 */
private fun splitForHighlight(code: String): List<String> {
    if (code.length <= MAX_CODE_LENGTH) return listOf(code)
    val chunks = ArrayList<String>()
    val builder = StringBuilder()
    var index = 0
    while (index < code.length) {
        var lineEnd = code.indexOf('\n', index)
        lineEnd = if (lineEnd < 0) code.length else lineEnd + 1 // 换行符留在行尾
        var line = code.substring(index, lineEnd)
        index = lineEnd
        // 超长单行：硬切成 MAX_CODE_LENGTH 的片段
        while (line.length > MAX_CODE_LENGTH) {
            if (builder.isNotEmpty()) {
                chunks.add(builder.toString())
                builder.setLength(0)
            }
            chunks.add(line.substring(0, MAX_CODE_LENGTH))
            line = line.substring(MAX_CODE_LENGTH)
        }
        if (builder.length + line.length > MAX_CODE_LENGTH && builder.isNotEmpty()) {
            chunks.add(builder.toString())
            builder.setLength(0)
        }
        builder.append(line)
    }
    if (builder.isNotEmpty()) chunks.add(builder.toString())
    return chunks
}

@Composable
fun HighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = 12.sp,
    fontFamily: FontFamily = FontFamily.Monospace,
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val highlighter = LocalHighlighter.current
    var annotatedString by remember { mutableStateOf(AnnotatedString(code)) }

    val updatedCode by rememberUpdatedState(code)
    val updatedLanguage by rememberUpdatedState(language)
    val updatedColors by rememberUpdatedState(colors)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedCode to updatedLanguage }.collectLatest { (currentCode, currentLanguage) ->
            val palette = updatedColors
            if (currentCode.length > MAX_HIGHLIGHT_TOTAL_LENGTH) {
                annotatedString = AnnotatedString(currentCode)
                return@collectLatest
            }
            // 长文本分块高亮：先立刻显示纯文本占位（避免展开瞬间空白），
            // 再逐块替换成彩色。老实现是长度超阈值就整块退化成 Plain，
            // 于是「折叠(短)彩色 / 展开(长)全白」——这就是那个 bug 的成因。
            if (currentCode.length > MAX_CODE_LENGTH) {
                annotatedString = AnnotatedString(currentCode)
            }
            val chunks = splitForHighlight(currentCode)
            val builder = AnnotatedString.Builder()
            chunks.fastForEach { chunk ->
                // 单块解析失败（Prism 抛异常 / 未知 token）只让这一块退化成纯文本，
                // 不连坐整个文件。CancellationException 必须原样抛出，否则
                // collectLatest 取消不掉，切换内容时会有旧协程继续写 state。
                val tokens = try {
                    highlighter.highlight(chunk, currentLanguage)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    listOf(HighlightToken.Plain(content = chunk))
                }
                tokens.fastForEach { token ->
                    builder.buildHighlightText(token, palette)
                }
            }
            annotatedString = builder.toAnnotatedString()
        }
    }

    Text(
        modifier = modifier,
        text = annotatedString,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}

fun AnnotatedString.Builder.buildHighlightText(
    token: HighlightToken,
    colors: HighlightTextColorPalette
) {
    when (token) {
        is HighlightToken.Plain -> {
            append(token.content)
        }

        is HighlightToken.Token.StringContent -> {
            withStyle(getStyleForTokenType(token.type, colors)) {
                append(token.content)
            }
        }

        is HighlightToken.Token.StringListContent -> {
            withStyle(getStyleForTokenType(token.type, colors)) {
                token.content.fastForEach { append(it) }
            }
        }

        is HighlightToken.Token.Nested -> {
            token.content.forEach {
                buildHighlightText(it, colors)
            }
        }
    }
}

data class HighlightTextColorPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val operator: Color,
    val punctuation: Color,
    val className: Color,
    val property: Color,
    val boolean: Color,
    val variable: Color,
    val tag: Color,
    val attrName: Color,
    val attrValue: Color,
    val fallback: Color
) {
    companion object {
        val Default = HighlightTextColorPalette(
            keyword = Color(0xFFCC7832),
            string = Color(0xFF6A8759),
            number = Color(0xFF6897BB),
            comment = Color(0xFF808080),
            function = Color(0xFFFFC66D),
            operator = Color(0xFFCC7832),
            punctuation = Color(0xFFCC7832),
            className = Color(0xFFCB772F),
            property = Color(0xFFCB772F),
            boolean = Color(0xFF6897BB),
            variable = Color(0xFF6A8759),
            tag = Color(0xFFE8BF6A),
            attrName = Color(0xFFBABABA),
            attrValue = Color(0xFF6A8759),
            fallback = Color(0xFF808080),
        )
    }
}

// 根据token类型返回对应的文本样式
private fun getStyleForTokenType(type: String, colors: HighlightTextColorPalette): SpanStyle {
    return when (type) {
        "keyword" -> SpanStyle(color = colors.keyword)
        "string" -> SpanStyle(color = colors.string) // 绿色
        "number" -> SpanStyle(color = colors.number) // 蓝色
        "comment" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic) // 灰色斜体
        "function", "method" -> SpanStyle(color = colors.function) // 黄色
        "operator" -> SpanStyle(color = colors.operator) // 橙色
        "punctuation" -> SpanStyle(color = colors.punctuation) // 橙色
        "class-name", "property" -> SpanStyle(color = colors.className) // 棕色
        "boolean", "constant" -> SpanStyle(color = colors.boolean) // 蓝色
        "regex", "important", "variable" -> SpanStyle(color = colors.variable)
        "tag" -> SpanStyle(color = colors.tag) // 黄色
        "attr-name" -> SpanStyle(color = colors.attrName) // 浅灰色
        "attr-value" -> SpanStyle(color = colors.attrValue) // 绿色
        else -> {
            // println("unknown type $type")
            SpanStyle(color = colors.fallback)
        }
    }
}
