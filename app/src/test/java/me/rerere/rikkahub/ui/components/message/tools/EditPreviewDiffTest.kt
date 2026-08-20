package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildEditPreviewDiff] / [editOpCount] 的回归测试。
 *
 * 事故背景（2026-08-21）：edit 工具的批量 `edits` 模式下，顶层没有 old_text/new_text，
 * 老实现直接 return null → 卡片摘要整块空白。等待审批的批量编辑因此变成「闭眼点同意」。
 * 这里把「批量必须出 diff」钉死。
 */
class EditPreviewDiffTest {

    private fun args(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `single edit mode produces diff`() {
        val diff = buildEditPreviewDiff(
            args("""{"path":"a.kt","old_text":"foo","new_text":"bar"}""")
        )
        assertNotNull(diff)
        assertTrue(diff!!.contains("-foo"))
        assertTrue(diff.contains("+bar"))
        assertTrue(diff.contains("a.kt"))
    }

    @Test
    fun `batch edits mode produces diff for every op`() {
        val diff = buildEditPreviewDiff(
            args(
                """
                {"path":"a.kt","edits":[
                  {"old_text":"alpha","new_text":"ALPHA"},
                  {"old_text":"beta","new_text":"BETA"},
                  {"old_text":"gamma","new_text":"GAMMA"}
                ]}
                """.trimIndent()
            )
        )
        assertNotNull(diff)
        // 三处编辑的增删行全部到位：任何一处丢失都说明合成漏了 op
        listOf("-alpha", "+ALPHA", "-beta", "+BETA", "-gamma", "+GAMMA").forEach {
            assertTrue("missing $it in:\n$diff", diff!!.contains(it))
        }
        // 文件头带 i/n 编号，批量时用来区分段落边界
        assertTrue(diff!!.contains("edit 1/3"))
        assertTrue(diff.contains("edit 3/3"))
    }

    @Test
    fun `batch edits without path still previews`() {
        // MCP 侧存在只给 edits 不给 path 的实现，不该因此丢掉整段预览
        val diff = buildEditPreviewDiff(args("""{"edits":[{"old_text":"x","new_text":"y"}]}"""))
        assertNotNull(diff)
        assertTrue(diff!!.contains("-x"))
        assertTrue(diff.contains("+y"))
    }

    @Test
    fun `malformed or empty inputs return null instead of crashing`() {
        assertNull(buildEditPreviewDiff(args("""{}""")))
        assertNull(buildEditPreviewDiff(args("""{"path":"a.kt"}""")))
        assertNull(buildEditPreviewDiff(args("""{"path":"a.kt","edits":[]}""")))
        // edits 元素缺字段：跳过该元素，全都不合法则为 null
        assertNull(buildEditPreviewDiff(args("""{"path":"a.kt","edits":[{"old_text":"x"}]}""")))
        // 顶层只有 old_text 没有 new_text：不能拿单编辑分支硬凑
        assertNull(buildEditPreviewDiff(args("""{"path":"a.kt","old_text":"x"}""")))
    }

    @Test
    fun `partially malformed batch keeps the valid ops`() {
        val diff = buildEditPreviewDiff(
            args(
                """{"path":"a.kt","edits":[{"old_text":"x"},{"old_text":"ok","new_text":"OK"}]}"""
            )
        )
        assertNotNull(diff)
        assertTrue(diff!!.contains("+OK"))
    }

    @Test
    fun `identical text yields no diff`() {
        assertNull(buildEditPreviewDiff(args("""{"path":"a.kt","old_text":"same","new_text":"same"}""")))
    }

    @Test
    fun `edit op count only counts batch mode`() {
        assertEquals(2, editOpCount(args("""{"edits":[{"old_text":"a","new_text":"b"},{"old_text":"c","new_text":"d"}]}""")))
        assertNull(editOpCount(args("""{"path":"a.kt","old_text":"a","new_text":"b"}""")))
        assertNull(editOpCount(args("""{"edits":[]}""")))
    }
}
