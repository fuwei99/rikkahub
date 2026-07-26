package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.LatexDimensions
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme

private val DefaultMathFontSize = 16.sp

/**
 * 原生 Compose LaTeX 渲染（huarangmeng/latex, KaTeX 字体）。
 * 解析失败时库会以错误色内联显示未识别命令，而非静默失败。
 */
@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val merged = style.merge(fontSize = fontSize, color = color)
    val resolvedColor = merged.color.takeOrElse { LocalContentColor.current }
    val resolvedFontSize = merged.fontSize.takeOrElse { DefaultMathFontSize }
    val config = remember(resolvedFontSize, resolvedColor) {
        LatexConfig(
            fontSize = resolvedFontSize,
            theme = LatexTheme.light(color = resolvedColor),
        )
    }
    Latex(
        latex = processLatex(latex),
        modifier = modifier,
        config = config,
    )
}

/**
 * 行内公式测量：返回宽/高/基线（px），用于构建 [androidx.compose.foundation.text.InlineTextContent]
 * 的 Placeholder（配合 PlaceholderVerticalAlign.AboveBaseline 实现基线对齐）。
 */
fun LatexMeasurerState.measureInlineMath(
    latex: String,
    fontSize: TextUnit,
): LatexDimensions? {
    val resolvedFontSize = fontSize.takeOrElse { DefaultMathFontSize }
    return measure(
        latex = processLatex(latex),
        config = LatexConfig(fontSize = resolvedFontSize),
    )
}

/**
 * 行内公式内容。Placeholder 高度只保留公式基线以上部分（ascent），并以
 * AboveBaseline 对齐，使公式基线与正文基线重合；基线以下（depth）向下溢出绘制。
 *
 * requiredHeight 撑出完整高度后，Compose 会将溢出内容自动居中，
 * 因此需要向下偏移 depth/2 让公式顶部与占位框顶部对齐。
 */
@Composable
fun InlineMathContent(
    latex: String,
    dimensions: LatexDimensions,
    fontSize: TextUnit,
) {
    val density = LocalDensity.current
    with(density) {
        val depthPx = (dimensions.heightPx - dimensions.baselinePx).coerceAtLeast(0f)
        Box(modifier = Modifier.fillMaxSize()) {
            LatexText(
                latex = latex,
                fontSize = fontSize,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (depthPx / 2f).toDp())
                    .requiredHeight(dimensions.heightPx.toDp()),
            )
        }
    }
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)$""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]$""", RegexOption.DOT_MATCHES_ALL)

/**
 * 去掉数学定界符（$...$、$$...$$、\(...\)、\[...\]）并应用兼容性替换。
 */
fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    val unwrapped = when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
    return replaceXlongequal(unwrapped)
}

/** 拼接三个等号模拟可延伸长等号 */
private const val LONG_EQUAL = """=\!=\!="""

/**
 * 渲染引擎不支持 \xlongequal，降级为 \overset/\underset + 长等号：
 * - `\xlongequal{above}`        -> `\overset{above}{===}`
 * - `\xlongequal[below]{above}` -> `\overset{above}{\underset{below}{===}}`
 * - 裸 `\xlongequal`            -> `===`
 */
private fun replaceXlongequal(input: String): String {
    val cmd = "\\xlongequal"
    if (!input.contains(cmd)) return input
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        val after = i + cmd.length
        val isWholeCmd = input.startsWith(cmd, i) &&
            (after >= input.length || !input[after].isLetter())
        if (isWholeCmd) {
            var cursor = after
            var below: String? = null
            var above: String? = null
            // 可选参数 [below]
            val optStart = input.indexOfNextNonWhitespace(cursor)
            if (optStart >= 0 && input[optStart] == '[') {
                val end = input.indexOf(']', optStart + 1)
                if (end > 0) {
                    below = input.substring(optStart + 1, end)
                    cursor = end + 1
                }
            }
            // 必选参数 {above}
            val braceStart = input.indexOfNextNonWhitespace(cursor)
            if (braceStart >= 0 && input[braceStart] == '{') {
                val group = input.readBraceGroup(braceStart)
                if (group != null) {
                    above = group.value
                    cursor = group.endExclusive
                }
            }
            val replacement = when {
                above != null && below != null ->
                    "\\overset{${replaceXlongequal(above)}}{\\underset{${replaceXlongequal(below)}}{$LONG_EQUAL}}"

                above != null -> "\\overset{${replaceXlongequal(above)}}{$LONG_EQUAL}"
                below != null -> "\\underset{${replaceXlongequal(below)}}{$LONG_EQUAL}"
                else -> LONG_EQUAL
            }
            out.append(replacement)
            i = cursor
            continue
        }
        out.append(input[i])
        i++
    }
    return out.toString()
}

private data class BraceGroup(val value: String, val endExclusive: Int)

private fun String.indexOfNextNonWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index++
    return if (index < length) index else -1
}

private fun String.readBraceGroup(start: Int): BraceGroup? {
    if (start !in indices || this[start] != '{') return null
    var depth = 0
    var escaped = false
    val value = StringBuilder()
    for (index in start until length) {
        val char = this[index]
        if (index == start) {
            depth = 1
            continue
        }
        if (escaped) {
            value.append(char)
            escaped = false
            continue
        }
        if (char == '\\') {
            value.append(char)
            escaped = true
            continue
        }
        when (char) {
            '{' -> {
                depth++
                value.append(char)
            }
            '}' -> {
                depth--
                if (depth == 0) return BraceGroup(value.toString(), index + 1)
                value.append(char)
            }
            else -> value.append(char)
        }
    }
    return null
}
