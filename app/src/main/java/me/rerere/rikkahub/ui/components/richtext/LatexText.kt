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
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.LatexDimensions
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import java.util.LinkedHashMap

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
    inline: Boolean = false,
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
        latex = processLatex(latex, inline),
        modifier = modifier,
        config = config,
    )
}

/**
 * 行内公式测量：返回宽/高/基线（px），用于构建 [androidx.compose.foundation.text.InlineTextContent]
 * 的 Placeholder（配合 PlaceholderVerticalAlign.AboveBaseline 实现基线对齐）。
 */
private object LatexMeasureCache {
    private const val MAX_ENTRIES = 1024
    private val cache = object : LinkedHashMap<String, LatexDimensions>(128, 0.75f, true) {}

    @Synchronized
    fun getOrPut(key: String, measure: () -> LatexDimensions?): LatexDimensions? {
        cache[key]?.let { return it }
        val value = measure() ?: return null
        cache[key] = value
        while (cache.size > MAX_ENTRIES) {
            val oldest = cache.entries.iterator().next().key
            cache.remove(oldest)
        }
        return value
    }
}

fun LatexMeasurerState.measureInlineMath(
    latex: String,
    fontSize: TextUnit,
): LatexDimensions? {
    val resolvedFontSize = fontSize.takeOrElse { DefaultMathFontSize }
    val processed = processLatex(latex, inline = true)
    val key = "${processed.length}:${processed.hashCode()}:$resolvedFontSize"
    return LatexMeasureCache.getOrPut(key) {
        measure(
            latex = processed,
            config = LatexConfig(fontSize = resolvedFontSize),
        )
    }
}

/**
 * 行内公式的 Placeholder 策略：
 * - 浅公式（基线下部分小）：基线对齐，Placeholder 只占 ascent，depth 向下溢出。
 * - 深公式（\cfrac、\displaystyle 积分等 depth 很大）：改用 TextCenter + 完整高度，
 *   让行高完整容纳公式，避免溢出部分压到下一行文字。
 */
class InlineMathPlacement(
    val width: TextUnit,
    val height: TextUnit,
    val verticalAlign: PlaceholderVerticalAlign,
    val baselineMode: Boolean,
)

fun computeInlineMathPlacement(
    dimensions: LatexDimensions,
    density: Density,
    fontSize: TextUnit,
): InlineMathPlacement = with(density) {
    // Use a full-height TextCenter placeholder for all inline math. The previous
    // AboveBaseline/overflow strategy was fragile in Compose Text: after display
    // math or in bold/blockquote paragraphs, shallow formulas like $A$ could be
    // painted too high. Full-height placement is more stable and fixes the
    // visible vertical drift without clipping deep formulas.
    InlineMathPlacement(
        width = dimensions.widthPx.coerceAtLeast(1f).toSp(),
        height = dimensions.heightPx.coerceAtLeast(1f).toSp(),
        verticalAlign = PlaceholderVerticalAlign.TextCenter,
        baselineMode = false,
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
    baselineMode: Boolean = true,
) {
    val density = LocalDensity.current
    with(density) {
        if (baselineMode) {
            val depthPx = (dimensions.heightPx - dimensions.baselinePx).coerceAtLeast(0f)
            Box(modifier = Modifier.fillMaxSize()) {
                LatexText(
                    latex = latex,
                    fontSize = fontSize,
                    inline = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (depthPx / 2f).toDp())
                        .requiredHeight(dimensions.heightPx.toDp()),
                )
            }
        } else {
            // Placeholder 已占满完整高度，直接居中绘制，不溢出
            Box(modifier = Modifier.fillMaxSize()) {
                LatexText(
                    latex = latex,
                    fontSize = fontSize,
                    inline = true,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
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
fun processLatex(latex: String, inline: Boolean = false): String {
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
    var result = unwrapped
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
    result = replaceExtensibleCommand(result, "xlongequal", LONG_EQUAL)
    result = replaceExtensibleCommand(result, "xrightleftharpoons", "\\rightleftharpoons")
    result = replaceInfixChoose(result)
    result = replaceCenternot(result)
    result = applyCompatReplacements(result)
    if (inline) result = downsizeInlineOperators(result)
    return result
}

/**
 * `\centernot` 引擎不支持。它本是把斜杠居中盖在下一个符号上，语义即"否定"。
 * 直接降级为 `\not`（引擎认），排版略有偏差但语义完全正确。
 * 处理裸命令 `\centernot`（不吞参数，\not 本身作用于紧跟的符号）。
 */
private val CENTERNOT_REGEX = Regex("""\\centernot(?![a-zA-Z])""")

private fun replaceCenternot(input: String): String {
    if (!input.contains("\\centernot")) return input
    return CENTERNOT_REGEX.replace(input, """\\not""")
}

/**
 * 中缀命令 `\choose` `\brack` `\brace` 引擎不支持（plain TeX 中缀原语，
 * 需吞掉左右两侧整个子公式）。经实测该引擎 \atop/\genfrac/\atopwithdelims
 * 全不支持, 但 \substack 可用, 故统一用 \substack + 定界符降级：
 *   `a \choose b` -> `\binom{a}{b}`
 *   `a \brack b`  -> `\left[\substack{a\\b}\right]`        (第一类斯特林数)
 *   `a \brace b`  -> `\left\lbrace\substack{a\\b}\right\rbrace` (第二类斯特林数)
 * 左侧吞到最近的未配对 `{` 或串首, 右侧吞到配对 `}` 或串尾。
 */
private data class InfixRule(val name: String, val wrapLeft: String, val wrapRight: String)

private val INFIX_RULES = listOf(
    // \choose 走 \binom 专门分支, wrap 字段不使用
    InfixRule("choose", "", ""),
    InfixRule("brack", "\\left[\\substack{", "}\\right]"),
    InfixRule("brace", "\\left\\lbrace\\substack{", "}\\right\\rbrace"),
)

private fun replaceInfixChoose(input: String): String {
    var result = input
    for (rule in INFIX_RULES) {
        result = replaceOneInfix(result, rule)
    }
    return result
}

private fun replaceOneInfix(input: String, rule: InfixRule): String {
    val cmd = "\\" + rule.name
    if (!input.contains(cmd)) return input
    // 从右往左处理, 保证嵌套时索引稳定
    var result = input
    while (true) {
        val idx = findInfixCommand(result, cmd) ?: break
        val left = extractLeftOperand(result, idx)          // [start, idx)
        val right = extractRightOperand(result, idx + cmd.length) // [after, end)
        val leftExpr = result.substring(left.first, idx).trim()
        val rightExpr = result.substring(idx + cmd.length, right).trim()
        val replacement = if (rule.name == "choose") {
            "\\binom{$leftExpr}{$rightExpr}"
        } else {
            "${rule.wrapLeft}$leftExpr\\\\$rightExpr${rule.wrapRight}"
        }
        result = result.substring(0, left.first) + replacement + result.substring(right)
    }
    return result
}

/** 找到一个作为独立命令出现的中缀命令位置（后面不接字母），忽略 `\\cmd` 转义误伤。 */
private fun findInfixCommand(input: String, cmd: String): Int? {
    var from = 0
    while (true) {
        val i = input.indexOf(cmd, from)
        if (i < 0) return null
        val after = i + cmd.length
        val wholeCmd = after >= input.length || !input[after].isLetter()
        if (wholeCmd) return i
        from = i + cmd.length
    }
}

/** 向左吞左操作数：到最近的未配对 `{`（不含）或串首。返回 [start, idx)。 */
private fun extractLeftOperand(input: String, idx: Int): Pair<Int, Int> {
    var depth = 0
    var i = idx - 1
    while (i >= 0) {
        val c = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'
        if (!escaped) {
            when (c) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) return (i + 1) to idx
                    depth--
                }
            }
        }
        i--
    }
    return 0 to idx
}

/** 向右吞右操作数：到最近的未配对 `}`（不含）或串尾。返回结束下标(exclusive)。 */
private fun extractRightOperand(input: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < input.length) {
        val c = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'
        if (!escaped) {
            when (c) {
                '{' -> depth++
                '}' -> {
                    if (depth == 0) return i
                    depth--
                }
            }
        }
        i++
    }
    return input.length
}

private val MIDDLE_REGEX = Regex("""\\middle(?![a-zA-Z])\s*""")

/**
 * 渲染引擎不支持的标准写法 -> 等价兼容写法：
 * - `\middle` 不支持：去掉命令本身，保留后面的定界符（不拉伸但能渲染）
 * - `\{` `\}` -> `\lbrace` `\rbrace`
 * - `\|` -> `\Vert`
 */
private fun applyCompatReplacements(input: String): String {
    var result = input
    if (result.contains("\\middle")) {
        result = MIDDLE_REGEX.replace(result, "")
    }
    if (!result.contains("\\{") && !result.contains("\\}") && !result.contains("\\|")) {
        return result
    }
    // 单遍扫描, 正确处理 \\ 转义(行分隔符后紧跟 {/}/| 不能误替换)
    val out = StringBuilder(result.length + 16)
    var i = 0
    while (i < result.length) {
        val c = result[i]
        if (c == '\\' && i + 1 < result.length) {
            when (result[i + 1]) {
                '\\' -> { out.append("\\\\"); i += 2; continue }
                '{' -> { out.append("\\lbrace "); i += 2; continue }
                '}' -> { out.append("\\rbrace "); i += 2; continue }
                '|' -> { out.append("\\Vert "); i += 2; continue }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * 行内模式下，引擎把 \lim/\max/\gcd/\operatorname 等函数名按大型算子渲染，
 * 字号明显大于正文。降级为 \mathrm 普通文本（行内上下标本来就在侧边，语义不变）。
 * 块级公式不处理，保留 limits 上下限排版。
 */
private val INLINE_OPERATOR_REGEX =
    Regex("""\\(limsup|liminf|lim|sup|inf|max|min|gcd|deg|dim|ker|det|arg|hom|bmod)(?![a-zA-Z])""")
private val OPERATORNAME_REGEX = Regex("""\\operatorname\*?\s*\{([^{}]*)\}""")

private fun downsizeInlineOperators(input: String): String {
    if (!input.contains('\\')) return input
    var result = OPERATORNAME_REGEX.replace(input) { m -> "\\mathrm{${m.groupValues[1]}}" }
    result = INLINE_OPERATOR_REGEX.replace(result) { m ->
        when (val name = m.groupValues[1]) {
            "limsup" -> "\\mathrm{lim\\,sup}"
            "liminf" -> "\\mathrm{lim\\,inf}"
            "bmod" -> "\\;\\mathrm{mod}\\;"
            else -> "\\mathrm{$name}"
        }
    }
    return result
}

/** 拼接三个等号模拟可延伸长等号 */
private const val LONG_EQUAL = """=\!=\!="""

/**
 * 渲染引擎不支持的可延伸命令（\xlongequal、\xrightleftharpoons 等），
 * 降级为 \overset/\underset + 基础符号：
 * - `\cmd{above}`        -> `\overset{above}{base}`
 * - `\cmd[below]{above}` -> `\overset{above}{\underset{below}{base}}`
 * - 裸 `\cmd`            -> `base`
 */
private fun replaceExtensibleCommand(input: String, name: String, base: String): String {
    val cmd = "\\" + name
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
                    "\\overset{${replaceExtensibleCommand(above, name, base)}}{\\underset{${replaceExtensibleCommand(below, name, base)}}{$base}}"

                above != null -> "\\overset{${replaceExtensibleCommand(above, name, base)}}{$base}"
                below != null -> "\\underset{${replaceExtensibleCommand(below, name, base)}}{$base}"
                else -> base
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
