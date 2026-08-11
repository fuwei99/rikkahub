package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cases 环境 -> array + 定界符 的降级转换测试。
 *
 * 背景：上游 huarangmeng/latex 的 MatrixMeasurer.measureCases 把每行构造成
 * [condition, Text(" if "), expression]，条件列为空时会渲染出一个裸的 "if"。
 * 见 convertCasesToArray。
 */
class LatexCasesTest {

    @Test
    fun `cases without condition column no longer injects if`() {
        val out = convertCasesToArray(
            """\begin{cases} x_1 + x_2 = 0 \\ 2x_1 + x_2 + x_3 = 0 \end{cases}"""
        )
        assertEquals(
            """\left\lbrace\begin{array}{ll} x_1 + x_2 = 0 \\ 2x_1 + x_2 + x_3 = 0 \end{array}\right.""",
            out
        )
        assertFalse(out.contains("cases"))
    }

    @Test
    fun `cases with condition column keeps ampersand columns`() {
        val out = convertCasesToArray("""f(x)=\begin{cases} x & x>0 \\ -x & x\le 0 \end{cases}""")
        assertEquals(
            """f(x)=\left\lbrace\begin{array}{ll} x & x>0 \\ -x & x\le 0 \end{array}\right.""",
            out
        )
    }

    @Test
    fun `rcases uses right brace`() {
        val out = convertCasesToArray("""\begin{rcases} a \\ b \end{rcases} = c""")
        assertEquals("""\left.\begin{array}{ll} a \\ b \end{array}\right\rbrace = c""", out)
    }

    @Test
    fun `dcases and starred variants are converted`() {
        assertTrue(
            convertCasesToArray("""\begin{dcases} \frac12 & x>0 \end{dcases}""")
                .startsWith("""\left\lbrace\begin{array}{ll}""")
        )
        assertTrue(
            convertCasesToArray("""\begin{cases*} a & b \end{cases*}""")
                .startsWith("""\left\lbrace\begin{array}{ll}""")
        )
        assertTrue(
            convertCasesToArray("""\begin{rcases*} a \end{rcases*}""")
                .endsWith("""\end{array}\right\rbrace""")
        )
    }

    @Test
    fun `nested cases are converted from inside out`() {
        val out = convertCasesToArray(
            """\begin{cases} \begin{cases} a \\ b \end{cases} & x>0 \\ c & x<0 \end{cases}"""
        )
        assertFalse(out.contains("""\begin{cases}"""))
        assertEquals(2, Regex("""\\begin\{array\}""").findAll(out).count())
    }

    @Test
    fun `multiple sibling cases are all converted`() {
        val out = convertCasesToArray("""\begin{cases} a \end{cases} + \begin{cases} b \end{cases}""")
        assertEquals(2, Regex("""\\begin\{array\}""").findAll(out).count())
        assertFalse(out.contains("cases"))
    }

    @Test
    fun `unrelated latex is untouched`() {
        val inputs = listOf(
            """x^2 + \sin x""",
            """\begin{aligned} a &= b \end{aligned}""",
            """\begin{pmatrix} 1 & 2 \\ 3 & 4 \end{pmatrix}""",
            """\text{cases}""",
            """\begin{array}{ll} a \end{array}""",
        )
        inputs.forEach { assertEquals(it, convertCasesToArray(it)) }
    }

    @Test
    fun `unbalanced cases environment is left as is`() {
        val input = """\begin{cases} a \\ b"""
        assertEquals(input, convertCasesToArray(input))
    }

    @Test
    fun `processLatex applies the conversion after delimiter unwrapping`() {
        val out = processLatex("""$$\begin{cases} x_1 + x_2 = 0 \\ 2x_1 = 0 \end{cases}$$""")
        assertFalse(out.contains("cases"))
        assertTrue(out.contains("""\begin{array}{ll}"""))
        // applyCompatReplacements 不应破坏行分隔符 \\
        assertTrue(out.contains("""\\"""))
    }

    @Test
    fun `processLatex keeps braces converted to lbrace rbrace inside cases`() {
        val out = processLatex("""\begin{cases} \{a\} & x>0 \end{cases}""")
        assertFalse(out.contains("cases"))
        assertTrue(out.contains("""\lbrace"""))
        assertTrue(out.contains("""\rbrace"""))
    }
}
