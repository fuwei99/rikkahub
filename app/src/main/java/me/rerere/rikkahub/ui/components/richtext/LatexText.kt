package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
    }.getOrElse { Rect(0, 0, 0, 0) }
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val drawable = remember(latex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processLatex(latex),
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = latex,
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

/**
 * 将一条行内公式按顶层运算符水平拆分为多段 Drawable，
 * 以便在文本流中换行，避免单体公式过长被挤出屏幕。
 * 拆分失败时返回空列表，调用方需自行回退。
 */
fun splitLatex(
    latex: String,
    maxWidthPx: Float,
    fontSize: Float,
    color: Int
): List<JLatexMathDrawable> {
    return runCatching {
        JLatexMathSplitter.split(processLatex(latex), maxWidthPx, fontSize, color)
    }.onFailure {
        it.printStackTrace()
    }.getOrElse { emptyList() }
}

@Composable
fun LatexDrawable(
    drawable: JLatexMathDrawable,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp()
            )
        ) {
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
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
    return normalizeLatexMacros(unwrapped)
}

private fun normalizeLatexMacros(latex: String): String {
    var result = latex
        .replace("\\dfrac", "\\frac")
        .replace("\\tfrac", "\\frac")
        .replace("\\leqslant", "\\leq")
        .replace("\\geqslant", "\\geq")
        .replace("\\varnothing", "\\emptyset")
        .replace("\\neq", "\\ne")

    result = replaceOneArgCommand(result, "text") { value -> "\\mathrm{${value.escapeTextForMathRoman()}}" }
    result = replaceOneArgCommand(result, "operatorname") { value -> "\\mathrm{${value.escapeTextForMathRoman()}}" }
    result = replaceOneArgCommand(result, "xlongequal") { value -> "\\stackrel{${normalizeLatexMacros(value)}}{=}" }
    result = replaceTwoArgCommand(result, "overset") { top, base ->
        "\\stackrel{${normalizeLatexMacros(top)}}{${normalizeLatexMacros(base)}}"
    }
    result = replaceTwoArgCommand(result, "stackrel") { top, base ->
        "\\stackrel{${normalizeLatexMacros(top)}}{${normalizeLatexMacros(base)}}"
    }
    result = replaceTwoArgCommand(result, "underset") { bottom, base ->
        "${normalizeLatexMacros(base)}_{${normalizeLatexMacros(bottom)}}"
    }
    return result
}

private fun String.escapeTextForMathRoman(): String =
    replace(" ", "\\ ")

private fun replaceOneArgCommand(
    input: String,
    command: String,
    replacement: (String) -> String,
): String {
    val prefix = "\\$command"
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        if (input.startsWith(prefix, i)) {
            val firstStart = input.indexOfNextNonWhitespace(i + prefix.length)
            if (firstStart >= 0 && input[firstStart] == '{') {
                val first = input.readBraceGroup(firstStart)
                if (first != null) {
                    out.append(replacement(first.value))
                    i = first.endExclusive
                    continue
                }
            }
        }
        out.append(input[i])
        i++
    }
    return out.toString()
}

private fun replaceTwoArgCommand(
    input: String,
    command: String,
    replacement: (String, String) -> String,
): String {
    val prefix = "\\$command"
    val out = StringBuilder(input.length)
    var i = 0
    while (i < input.length) {
        if (input.startsWith(prefix, i)) {
            val firstStart = input.indexOfNextNonWhitespace(i + prefix.length)
            if (firstStart >= 0 && input[firstStart] == '{') {
                val first = input.readBraceGroup(firstStart)
                val secondStart = first?.let { input.indexOfNextNonWhitespace(it.endExclusive) }
                if (first != null && secondStart != null && secondStart >= 0 && input[secondStart] == '{') {
                    val second = input.readBraceGroup(secondStart)
                    if (second != null) {
                        out.append(replacement(first.value, second.value))
                        i = second.endExclusive
                        continue
                    }
                }
            }
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
