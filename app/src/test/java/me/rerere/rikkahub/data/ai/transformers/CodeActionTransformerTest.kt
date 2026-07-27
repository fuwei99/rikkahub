package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ToolCallingStrategy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.model.Model
import org.junit.Assert.*
import org.junit.Test

class CodeActionTransformerTest {

    private val mockContext = TransformerContext(
        model = Model(
            id = "test-model",
            name = "Test Model",
            provider = "test",
            toolCallingStrategy = ToolCallingStrategy.CODE_ACTION
        )
    )

    @Test
    fun testStreamingUnclosedXmlIsSuppressedInVisualTransform() = runTest {
        val streamingText = "好的，正在修改文件：\n<code_calls>\n<invoke name=\"workspace_edit_file\">\n<parameter name=\"path\">/workspace/foo.kt</parameter>"
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(streamingText))
        )

        val result = CodeActionTransformer.visualTransform(mockContext, listOf(message))
        val textPart = result.first().parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()

        assertNotNull(textPart)
        assertEquals("好的，正在修改文件：", textPart?.text)
        assertTrue(result.first().getTools().isEmpty())
    }

    @Test
    fun testClosedXmlExtractsToolInVisualTransform() = runTest {
        val closedText = "好的，正在修改文件：\n<code_calls>\n<invoke name=\"workspace_edit_file\">\n<parameter name=\"path\">/workspace/foo.kt</parameter>\n</invoke>\n</code_calls>"
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(closedText))
        )

        val result = CodeActionTransformer.visualTransform(mockContext, listOf(message))
        val textPart = result.first().parts.filterIsInstance<UIMessagePart.Text>().firstOrNull()
        val tools = result.first().getTools()

        assertEquals("好的，正在修改文件：", textPart?.text)
        assertEquals(1, tools.size)
        assertEquals("workspace_edit_file", tools.first().toolName)
        assertTrue(tools.first().input.contains("/workspace/foo.kt"))
    }
}
