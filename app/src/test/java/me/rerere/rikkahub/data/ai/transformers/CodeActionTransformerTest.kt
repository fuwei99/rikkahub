package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ToolCallingStrategy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直接测 [CodeActionTransformer.applyToolExtraction], 不经过 TransformerContext:
 * 后者需要 Android Context / Assistant / Settings, JVM 单测里造不出来, 而这段逻辑
 * 真正依赖的只有 strategy 与消息内容。
 */
class CodeActionTransformerTest {

    private fun assistantText(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun visualTransform(messages: List<UIMessage>) =
        CodeActionTransformer.applyToolExtraction(
            strategy = ToolCallingStrategy.CODE_ACTION,
            messages = messages,
            isFinal = false,
        )

    @Test
    fun streamingUnclosedXmlIsSuppressedInVisualTransform() {
        val streamingText = "好的，正在修改文件：\n<code_calls>\n<invoke name=\"workspace_edit_file\">\n" +
            "<parameter name=\"path\">/workspace/foo.kt</parameter>"

        val result = visualTransform(listOf(assistantText(streamingText)))
        val textPart = result.first().parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()

        assertNotNull(textPart)
        assertEquals("好的，正在修改文件：", textPart?.text)
        assertTrue(result.first().getTools().isEmpty())
    }

    @Test
    fun closedXmlExtractsToolInVisualTransform() {
        val closedText = "好的，正在修改文件：\n<code_calls>\n<invoke name=\"workspace_edit_file\">\n" +
            "<parameter name=\"path\">/workspace/foo.kt</parameter>\n</invoke>\n</code_calls>"

        val result = visualTransform(listOf(assistantText(closedText)))
        val textPart = result.first().parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()
        val tools = result.first().getTools()

        assertEquals("好的，正在修改文件：", textPart?.text)
        assertEquals(1, tools.size)
        assertEquals("workspace_edit_file", tools.first().toolName)
        assertTrue(tools.first().input.contains("/workspace/foo.kt"))
    }

    @Test
    fun otherStrategiesLeaveMessagesUntouched() {
        val raw = "<code_calls>\n<invoke name=\"workspace_edit_file\"></invoke>\n</code_calls>"
        val messages = listOf(assistantText(raw))

        val result = CodeActionTransformer.applyToolExtraction(
            strategy = ToolCallingStrategy.NATIVE,
            messages = messages,
            isFinal = true,
        )
        assertEquals(messages, result)
    }
}
