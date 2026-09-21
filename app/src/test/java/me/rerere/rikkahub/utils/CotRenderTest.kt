package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoT 渲染层成本控制（2026-09-22 性能事故）：
 * 体量闸门 / 尾部窗口 / 碎片化换行归一化。
 */
class CotRenderTest {

    // ---------- normalizeFragmentedLineBreaks ----------

    @Test
    fun `连续的碎片行会被压成同一行`() {
        val input = "（输出）\n\n好。\n\n我输出。"
        assertEquals("（输出） 好。 我输出。", input.normalizeFragmentedLineBreaks())
    }

    @Test
    fun `碎片行合并时吃掉中间空行但不吞掉段落分隔`() {
        val input = "好。\n\n我输出。\n\n继续。\n\n这是一段正常长度的正文，不该被并到上面去。"
        assertEquals(
            "好。 我输出。 继续。\n\n这是一段正常长度的正文，不该被并到上面去。",
            input.normalizeFragmentedLineBreaks()
        )
    }

    @Test
    fun `少于三行的碎片保持原样`() {
        val input = "好。\n\n我输出。"
        assertEquals(input, input.normalizeFragmentedLineBreaks())
    }

    @Test
    fun `代码围栏内部一律不动`() {
        val input = buildString {
            append("```kotlin\n")
            append("val a = 1\n")
            append("\n")
            append("val b = 2\n")
            append("```\n")
            append("\n")
            append("好。\n\n我输出。\n\n继续。")
        }
        val out = input.normalizeFragmentedLineBreaks()
        assertTrue(out.contains("```kotlin\nval a = 1\n\nval b = 2\n```"))
        assertTrue(out.endsWith("好。 我输出。 继续。"))
    }

    @Test
    fun `列表和标题这类有结构的行不受影响`() {
        val input = "- 嗯\n- 好\n- 继续\n\n# 标题\n\n1. 一\n2. 二\n3. 三"
        assertEquals(input, input.normalizeFragmentedLineBreaks())
    }

    @Test
    fun `正常的段落文本原样返回`() {
        val input = "这是一段很正常的思考内容，讲的是怎么把同步链路拆干净。\n\n第二段，同样正常。"
        assertEquals(input, input.normalizeFragmentedLineBreaks())
    }

    // ---------- takeTailOnLineBoundary ----------

    @Test
    fun `短文本不裁剪`() {
        assertEquals("abc", "abc".takeTailOnLineBoundary(10))
    }

    @Test
    fun `长文本按行边界取尾部且不超过上限`() {
        val input = "第一行很长很长很长很长\n第二行\n第三行"
        val out = input.takeTailOnLineBoundary(6)
        assertTrue(out.length <= 6)
        assertTrue(input.endsWith(out))
        assertFalse(out.startsWith("\n"))
    }

    // ---------- exceedsCotRenderBudget ----------

    @Test
    fun `字符数超限即判定超预算`() {
        assertTrue("x".repeat(COT_RENDER_MAX_CHARS + 1).exceedsCotRenderBudget())
        assertFalse("x".repeat(100).exceedsCotRenderBudget())
    }

    @Test
    fun `行数超限即判定超预算`() {
        // 字符数远小于上限，但碎片化换行把行数堆爆了 —— 这才是线上真正的杀手
        val input = buildString {
            repeat(COT_RENDER_MAX_LINES + 10) { append("好\n") }
        }
        assertTrue(input.length < COT_RENDER_MAX_CHARS)
        assertTrue(input.exceedsCotRenderBudget())
    }

    // ---------- extractThinkingTitle（回归：正则提到顶层后语义不变） ----------

    @Test
    fun `取最后一条独占整行的加粗文本`() {
        assertEquals(
            "第二步",
            "**第一步**\n内容\n**第二步**\n更多内容".extractThinkingTitle()
        )
    }

    @Test
    fun `没有加粗整行时返回空`() {
        assertNull("普通内容 **行内加粗** 而已".extractThinkingTitle())
    }
}
