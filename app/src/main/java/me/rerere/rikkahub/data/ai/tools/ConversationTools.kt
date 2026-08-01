package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation ids, titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` for snippets, then `conversation_fetch` if you need message details.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of recent conversations to return (default: 10, max: 30)")
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonObject {
                put("results", buildJsonArray {
                    recent.forEach { conversation ->
                        add(buildJsonObject {
                            put("conversation_id", conversation.id.toString())
                            put("title", conversation.title.ifBlank { "Untitled" })
                            put("last_chat", conversation.updateAt.toLocalDate())
                        })
                    }
                })
                put("hint", "Use conversation_search for snippets, then conversation_fetch with conversation_id for precise context or full text.")
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Search past conversations and return short snippets only. This tool is intentionally bounded to avoid flooding context.
            Use focused keywords. You can filter by conversation_id, date range, sort order, total limit, per-conversation limit, and snippet size.
            If you need the full conversation or more surrounding messages, call `conversation_fetch` with the returned conversation_id and message_id.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional conversation id to restrict search to one conversation")
                    })
                    put("from_date", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional start date, yyyy-MM-dd, filters by conversation update time")
                    })
                    put("to_date", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional end date, yyyy-MM-dd, filters by conversation update time")
                    })
                    put("sort", buildJsonObject {
                        put("type", "string")
                        put("description", "relevance (default), newest, or oldest")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of results to return (default: 8, max: 30)")
                    })
                    put("per_conversation_limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum results per conversation (default: 2, max: 10)")
                    })
                    put("context_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum characters per snippet around the closest keyword (default: 700, max: 2000)")
                    })
                    put("max_total_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum total snippet characters (default: 6000, max: 20000)")
                    })
                },
                required = listOf("query")
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: error("query is required")
            val conversationIdFilter = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            val from = params["from_date"]?.jsonPrimitive?.contentOrNull?.toStartInstantOrNull()
            val to = params["to_date"]?.jsonPrimitive?.contentOrNull?.toEndInstantOrNull()
            val sort = when (params["sort"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "newest", "newest_first" -> MessageSearchSort.NEWEST_FIRST
                "oldest", "oldest_first" -> MessageSearchSort.OLDEST_FIRST
                else -> MessageSearchSort.RELEVANCE
            }
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 8).coerceIn(1, 30)
            val perConversationLimit = (params["per_conversation_limit"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 10)
            val contextChars = (params["context_chars"]?.jsonPrimitive?.intOrNull ?: 700).coerceIn(120, 2000)
            val maxTotalChars = (params["max_total_chars"]?.jsonPrimitive?.intOrNull ?: 6000).coerceIn(500, 20_000)

            val perConversationCounts = mutableMapOf<String, Int>()
            var totalChars = 0
            var resultCount = 0
            var truncated = false
            val payload = buildJsonObject {
                put("query", query)
                put("results", buildJsonArray {
                    // 会话/日期过滤已下推到 FTS 查询；per_conversation_limit 仍在应用层生效，
                    // 故多取一些候选，避免单个话痨会话把配额吃光后无结果可补。
                    val fetchLimit = (limit * perConversationLimit).coerceIn(limit, 200)
                    val rawResults = conversationRepo.searchMessages(
                        keyword = query,
                        sort = sort,
                        conversationId = conversationIdFilter,
                        fromMillis = from?.toEpochMilli(),
                        toMillis = to?.toEpochMilli(),
                        limit = fetchLimit,
                    )
                    for (result in rawResults) {
                        if (resultCount >= limit) {
                            truncated = true
                            break
                        }
                        val usedInConversation = perConversationCounts[result.conversationId] ?: 0
                        if (usedInConversation >= perConversationLimit) continue

                        val conversation = runCatching { conversationRepo.getConversationById(Uuid.parse(result.conversationId)) }.getOrNull()
                        val messages = conversation?.currentMessages.orEmpty()
                        val messageIndex = messages.indexOfFirst { it.id.toString() == result.messageId }
                        val message = messages.getOrNull(messageIndex)
                        val messageText = message?.toSearchText().orEmpty()
                        val snippet = messageText.takeIf { it.isNotBlank() }?.snippetAround(query, contextChars)
                            ?: result.snippet.take(contextChars)
                        if (totalChars + snippet.length > maxTotalChars) {
                            truncated = true
                            break
                        }
                        totalChars += snippet.length
                        perConversationCounts[result.conversationId] = usedInConversation + 1
                        resultCount += 1

                        add(buildJsonObject {
                            put("conversation_id", result.conversationId)
                            put("conversation_title", result.title.ifBlank { conversation?.title?.ifBlank { "Untitled" } ?: "Untitled" })
                            put("message_id", result.messageId)
                            put("message_index", messageIndex)
                            put("role", message?.role?.name?.lowercase().orEmpty())
                            put("date", result.updateAt.toLocalDate())
                            put("snippet", snippet)
                        })
                    }
                })
                put("truncated", truncated)
                put("hint", "Results are snippets only. To read more, call conversation_fetch with conversation_id and optionally message_id.")
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_fetch",
        description = """
            Fetch a specific past conversation after conversation_search/recent_chats found the right conversation_id.
            Prefer mode=around_message or mode=range. Use mode=full only when the user explicitly needs the whole conversation.
            Results are still capped by max_chars to avoid context overflow.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Conversation id returned by recent_chats or conversation_search")
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("description", "around_message (default), range, or full")
                    })
                    put("message_id", buildJsonObject {
                        put("type", "string")
                        put("description", "For around_message: target message id returned by conversation_search")
                    })
                    put("before", buildJsonObject {
                        put("type", "integer")
                        put("description", "For around_message: messages before target (default: 3, max: 20)")
                    })
                    put("after", buildJsonObject {
                        put("type", "integer")
                        put("description", "For around_message: messages after target (default: 5, max: 20)")
                    })
                    put("start_index", buildJsonObject {
                        put("type", "integer")
                        put("description", "For range: first message index, inclusive")
                    })
                    put("end_index", buildJsonObject {
                        put("type", "integer")
                        put("description", "For range: last message index, inclusive")
                    })
                    put("max_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum returned message text characters (default: 12000, max: 50000)")
                    })
                },
                required = listOf("conversation_id")
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val conversationId = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: error("conversation_id is required")
            val conversation = conversationRepo.getConversationById(Uuid.parse(conversationId))
                ?: error("conversation not found: $conversationId")
            val messages = conversation.currentMessages
            val mode = params["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "around_message"
            val maxChars = (params["max_chars"]?.jsonPrimitive?.intOrNull ?: 12_000).coerceIn(500, 50_000)
            val range = when (mode) {
                "full" -> messages.indices
                "range" -> {
                    val start = (params["start_index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, messages.lastIndex.coerceAtLeast(0))
                    val end = (params["end_index"]?.jsonPrimitive?.intOrNull ?: start).coerceIn(start, messages.lastIndex.coerceAtLeast(start))
                    start..end
                }
                else -> {
                    val messageId = params["message_id"]?.jsonPrimitive?.contentOrNull
                    val center = messages.indexOfFirst { it.id.toString() == messageId }.takeIf { it >= 0 } ?: 0
                    val before = (params["before"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(0, 20)
                    val after = (params["after"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(0, 20)
                    (center - before).coerceAtLeast(0)..(center + after).coerceAtMost(messages.lastIndex.coerceAtLeast(0))
                }
            }

            var usedChars = 0
            var truncated = false
            val payload = buildJsonObject {
                put("conversation_id", conversation.id.toString())
                put("title", conversation.title.ifBlank { "Untitled" })
                put("created_at", conversation.createAt.toLocalDate())
                put("updated_at", conversation.updateAt.toLocalDate())
                put("mode", mode)
                put("messages", buildJsonArray {
                    for (index in range) {
                        val message = messages.getOrNull(index) ?: continue
                        val text = message.toSearchText()
                        val remaining = maxChars - usedChars
                        if (remaining <= 0) {
                            truncated = true
                            break
                        }
                        val clipped = text.take(remaining)
                        if (clipped.length < text.length) truncated = true
                        usedChars += clipped.length
                        add(buildJsonObject {
                            put("index", index)
                            put("message_id", message.id.toString())
                            put("role", message.role.name.lowercase())
                            put("text", clipped)
                        })
                        if (usedChars >= maxChars) {
                            truncated = true
                            break
                        }
                    }
                })
                put("truncated", truncated)
                if (truncated) {
                    put("hint", "Result hit max_chars. Fetch a narrower range or around a specific message_id.")
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

// 与 MessageFtsManager.extractFtsText 保持一致：命中可能落在工具调用里，
// 这里若只取 Text part，snippet 会错位到正文开头，产生误导。
private fun UIMessage.toSearchText(): String = buildString {
    parts.forEach { part ->
        val piece = when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Tool -> buildString {
                append(part.toolName)
                val input = part.input.trim()
                if (input.isNotBlank() && input != "{}") {
                    append('\n')
                    append(input.take(600))
                }
            }

            else -> ""
        }
        if (piece.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(piece)
        }
    }
}

private fun String.snippetAround(query: String, maxChars: Int): String {
    if (length <= maxChars) return this
    val terms = query.split(Regex("\\s+"))
        .map { it.trim().trim('[', ']', '"', '\'', '`') }
        .filter { it.isNotBlank() }
    val lower = lowercase()
    val hit = terms.asSequence()
        .map { lower.indexOf(it.lowercase()) }
        .firstOrNull { it >= 0 }
        ?: 0
    val half = maxChars / 2
    val start = (hit - half).coerceIn(0, (length - maxChars).coerceAtLeast(0))
    val end = (start + maxChars).coerceAtMost(length)
    return buildString {
        if (start > 0) append("...")
        append(this@snippetAround.substring(start, end))
        if (end < this@snippetAround.length) append("...")
    }
}

private fun String?.toStartInstantOrNull(): Instant? = runCatching {
    if (isNullOrBlank()) null else LocalDate.parse(this).atStartOfDay(ZoneId.systemDefault()).toInstant()
}.getOrNull()

private fun String?.toEndInstantOrNull(): Instant? = runCatching {
    if (isNullOrBlank()) null else LocalDate.parse(this).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusMillis(1)
}.getOrNull()
