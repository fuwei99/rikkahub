package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearHistorySearchTransformerTest {

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text))
    )

    private fun assistantMessage(text: String, tools: List<UIMessagePart.Tool> = emptyList()) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = buildList {
            if (text.isNotEmpty()) {
                add(UIMessagePart.Text(text))
            }
            addAll(tools)
        }
    )

    private fun createToolPart(toolCallId: String, toolName: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = toolCallId,
            toolName = toolName,
            input = "{}",
            output = listOf(UIMessagePart.Text("search results"))
        )
    }

    @Test
    fun `should not clear search results if no search tools are present`() {
        val messages = listOf(
            userMessage("Hello"),
            assistantMessage("Hi!"),
            userMessage("How are you?")
        )
        val result = clearHistorySearch(messages)
        assertEquals(messages, result)
    }

    @Test
    fun `should clear search results in historical assistant messages`() {
        val messages = listOf(
            userMessage("What is the weather?"),
            assistantMessage(
                text = "Let me search...",
                tools = listOf(createToolPart("call_1", "search_web"))
            ),
            userMessage("Tell me about google"),
            assistantMessage(
                text = "Checking...",
                tools = listOf(createToolPart("call_2", "scrape_web"))
            ),
            userMessage("Thank you")
        )

        val result = clearHistorySearch(messages)

        assertEquals(5, result.size)
        // Message at index 1 is historical, search_web should be cleared
        val msg1Parts = result[1].parts
        assertEquals(1, msg1Parts.size)
        assertTrue(msg1Parts[0] is UIMessagePart.Text)

        // Message at index 3 is historical, scrape_web should be cleared
        val msg3Parts = result[3].parts
        assertEquals(1, msg3Parts.size)
        assertTrue(msg3Parts[0] is UIMessagePart.Text)
    }

    @Test
    fun `should not clear search results in the last message`() {
        val messages = listOf(
            userMessage("What is the weather?"),
            assistantMessage(
                text = "Let me search...",
                tools = listOf(createToolPart("call_1", "search_web"))
            )
        )

        val result = clearHistorySearch(messages)

        assertEquals(2, result.size)
        // Since assistant message is the last message (currently generating or running), its tools should not be cleared
        val msg1Parts = result[1].parts
        assertEquals(2, msg1Parts.size)
        assertTrue(msg1Parts[0] is UIMessagePart.Text)
        assertTrue(msg1Parts[1] is UIMessagePart.Tool)
        assertEquals("search_web", (msg1Parts[1] as UIMessagePart.Tool).toolName)
    }
}
