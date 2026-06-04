package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object ClearHistorySearchTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>
    ): List<UIMessage> {
        if (!ctx.settings.searchCommonOptions.clearHistorySearch) {
            return messages
        }
        return clearHistorySearch(messages)
    }
}

internal fun clearHistorySearch(messages: List<UIMessage>): List<UIMessage> {
    return messages.mapIndexed { index, message ->
        // Keep the last message (which could be currently executing/awaiting tools) untouched
        if (index == messages.lastIndex) {
            message
        } else {
            val hasSearchTools = message.parts.any { part ->
                part is UIMessagePart.Tool && (part.toolName == "search_web" || part.toolName == "scrape_web")
            }
            if (hasSearchTools) {
                val filteredParts = message.parts.filter { part ->
                    !(part is UIMessagePart.Tool && (part.toolName == "search_web" || part.toolName == "scrape_web"))
                }
                message.copy(parts = filteredParts)
            } else {
                message
            }
        }
    }
}
