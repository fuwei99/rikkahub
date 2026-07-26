package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    LatexText(
        latex = latex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
        inline = true,
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val resolvedFontSize = fontSize
        .takeOrElse { LocalTextStyle.current.fontSize }
        .takeOrElse { 16.sp }
    val color = LocalContentColor.current
    val config = remember(resolvedFontSize, color) {
        LatexConfig(
            fontSize = resolvedFontSize,
            theme = LatexTheme.light(color = color),
        )
    }
    Box(
        modifier = modifier.padding(8.dp)
    ) {
        // 长公式在运算符/关系符处自动换行，替代原来的水平滚动
        LatexAutoWrap(
            latex = processLatex(latex),
            config = config,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
