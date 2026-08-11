package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * 对话历史工具（2026-08-11 三合一重构）。
 *
 * 从前是 `recent_chats` / `conversation_search` / `conversation_fetch` 三个工具，
 * 同开同关却各交一份 description + schema，白烧 token；并且各有坑：
 * - recent 按「置顶优先」排序（那是会话列表 UI 的排序），limit 小的时候返回的全是置顶，
 *   真正最近的活动被挤掉；
 * - 只输出到「日」的日期，拿不到精确时间，更没有发信设备（那行 `[发送时间及所在设备: ...]`
 *   是 messageTemplate 在发请求前拼的，压根不落库，读历史的工具刨不出来）；
 * - 排除不了 agent 自己的会话（查岗 agent 每次都把自己捞出来）；
 * - fetch 没有 tail 模式，要先 `start_index=999999` 探一次 lastIndex 才能取最近 N 条。
 *
 * 现在合并为单工具 `chat_history` + action，并把时间/设备/agent 身份全部结构化输出。
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
    conversationId: Uuid,
    /** 全部助手 (id, name)，用于 assistant 参数解析与回显助手名 */
    assistantsProvider: () -> List<Pair<Uuid, String>> = { emptyList() },
): List<Tool> = listOf(
    Tool(
        name = "chat_history",
        description = """
            Look into past conversations with the user. One tool, three actions:

            - action=recent — list conversations ordered by LAST ACTIVITY TIME (newest first; pinned
              conversations are NOT boosted). Returns precise timestamps, the sending device of the
              last user message, message counts and an optional preview / last N messages.
              Use `since_minutes` for "what happened in the last hour", `include_last_messages` to get
              the actual tail in the same call (no follow-up fetch needed).
              By default agent conversations (sub-agents, scheduled tasks, supervision check-ins) and
              your own conversation are excluded — set exclude_agents/exclude_self=false to include them.
            - action=search — keyword search over message text, returns bounded snippets.
            - action=fetch — read messages of one conversation. mode=tail (default) returns the last N
              messages without needing to know indices; also around_message / range / full.

            Every returned message carries `sent_at` (local time, second precision) and, for user
            messages, `device` (the device it was sent from; absent on older messages — treat missing
            device as unknown, not as evidence). Do not try to parse timestamps out of message text.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("recent")
                            add("search")
                            add("fetch")
                        })
                        put("description", "recent | search | fetch")
                    })
                    // ---- recent ----
                    put("assistant", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "recent/search: 'current' (default), 'all', or an assistant name / id"
                        )
                    })
                    put("exclude_agents", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "recent: exclude agent conversations (sub-agents, scheduled tasks, supervision). Default true."
                        )
                    })
                    put("exclude_self", buildJsonObject {
                        put("type", "boolean")
                        put("description", "recent: exclude the conversation you are running in. Default true.")
                    })
                    put("since_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "recent: only conversations active within the last N minutes")
                    })
                    put("preview_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "recent: preview length of the last message (default 200, 0 = none, max 1000)")
                    })
                    put("include_last_messages", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "recent: also return the last N messages of each conversation in full (default 0, max 20)"
                        )
                    })
                    // ---- search ----
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "search: keywords to look for in past messages")
                    })
                    put("from_date", buildJsonObject {
                        put("type", "string")
                        put("description", "search: start date yyyy-MM-dd (by conversation update time)")
                    })
                    put("to_date", buildJsonObject {
                        put("type", "string")
                        put("description", "search: end date yyyy-MM-dd")
                    })
                    put("sort", buildJsonObject {
                        put("type", "string")
                        put("description", "search: relevance (default), newest, or oldest")
                    })
                    put("per_conversation_limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "search: max results per conversation (default 2, max 10)")
                    })
                    put("context_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "search: chars per snippet (default 700, max 2000)")
                    })
                    put("max_total_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "search: max total snippet chars (default 6000, max 20000)")
                    })
                    // ---- shared / fetch ----
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "fetch: target conversation. search: restrict to one conversation.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "recent: max conversations (default 10, max 30). search: max results (default 8, max 30). fetch+tail: max messages (default 15, max 50).")
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("tail")
                            add("around_message")
                            add("range")
                            add("full")
                        })
                        put("description", "fetch: tail (default) | around_message | range | full")
                    })
                    put("message_id", buildJsonObject {
                        put("type", "string")
                        put("description", "fetch/around_message: target message id from search")
                    })
                    put("before", buildJsonObject {
                        put("type", "integer")
                        put("description", "fetch/around_message: messages before target (default 3, max 20)")
                    })
                    put("after", buildJsonObject {
                        put("type", "integer")
                        put("description", "fetch/around_message: messages after target (default 5, max 20)")
                    })
                    put("start_index", buildJsonObject {
                        put("type", "integer")
                        put("description", "fetch/range: first message index, inclusive")
                    })
                    put("end_index", buildJsonObject {
                        put("type", "integer")
                        put("description", "fetch/range: last message index, inclusive")
                    })
                    put("max_chars", buildJsonObject {
                        put("type", "integer")
                        put("description", "fetch: max total message chars (default 12000, max 50000)")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim()
                ?: "recent"
            val payload = when (action) {
                "search" -> runSearch(conversationRepo, params, assistantId, assistantsProvider)
                "fetch" -> runFetch(conversationRepo, params)
                "recent" -> runRecent(conversationRepo, params, assistantId, conversationId, assistantsProvider)
                else -> buildJsonObject {
                    put("error", "unknown action: $action (expected recent | search | fetch)")
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

// ------------------------------------------------------------------ recent

private suspend fun runRecent(
    repo: ConversationRepository,
    params: kotlinx.serialization.json.JsonObject,
    currentAssistantId: Uuid,
    currentConversationId: Uuid,
    assistantsProvider: () -> List<Pair<Uuid, String>>,
) = buildJsonObject {
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
    val assistants = assistantsProvider()
    val assistantFilter = resolveAssistantFilter(
        raw = params["assistant"]?.jsonPrimitive?.contentOrNull,
        currentAssistantId = currentAssistantId,
        assistants = assistants,
    )
    val excludeAgents = params["exclude_agents"]?.jsonPrimitive?.booleanOrNull ?: true
    val excludeSelf = params["exclude_self"]?.jsonPrimitive?.booleanOrNull ?: true
    val sinceMinutes = params["since_minutes"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    val previewChars = (params["preview_chars"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(0, 1000)
    val tailCount = (params["include_last_messages"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 20)

    val rows = repo.getRecentConversationSummaries(
        assistantId = assistantFilter,
        limit = limit,
        excludeAgents = excludeAgents,
        excludeConversationId = currentConversationId.takeIf { excludeSelf },
        sinceMillis = sinceMinutes?.let { Instant.now().minusSeconds(it * 60L).toEpochMilli() },
        tailMessages = maxOf(tailCount, if (previewChars > 0) 1 else 0),
    )
    val assistantNames = assistants.associate { it.first.toString() to it.second }

    put("action", "recent")
    put("scope", assistantFilter?.let { assistantNames[it.toString()] ?: it.toString() } ?: "all assistants")
    put("ordered_by", "last_activity_desc")
    put("results", buildJsonArray {
        rows.forEach { row ->
            add(buildJsonObject {
                put("conversation_id", row.id)
                put("title", row.title.ifBlank { "Untitled" })
                put("assistant", assistantNames[row.assistantId] ?: row.assistantId)
                put("last_active_at", row.updateAt.toLocalDateTimeString())
                put("message_count", row.messageCount)
                if (row.isPinned) put("pinned", true)
                if (row.isAgent) {
                    put("is_agent_conversation", true)
                    put("agent_template", row.agentTemplateId)
                    put("agent_status", row.agentStatus)
                }
                if (row.id == currentConversationId.toString()) put("is_current_conversation", true)
                val last = row.tailMessages.lastOrNull()
                if (last != null && previewChars > 0) {
                    put("last_message_role", last.role.name.lowercase())
                    put("last_message_at", last.sentAtString())
                    last.device?.let { put("last_message_device", it) }
                    put("last_message_preview", last.toSearchText().take(previewChars))
                }
                if (tailCount > 0 && row.tailMessages.isNotEmpty()) {
                    put("last_messages", buildJsonArray {
                        row.tailMessages.takeLast(tailCount).forEach { message ->
                            add(buildJsonObject { putMessage(message, index = null, text = message.toSearchText()) })
                        }
                    })
                }
            })
        }
    })
    put(
        "hint",
        "Timestamps and devices are structured fields here — never parse them out of message text. " +
            "Use action=fetch (mode=tail) for more messages of one conversation, action=search for keywords."
    )
}

// ------------------------------------------------------------------ search

private suspend fun runSearch(
    repo: ConversationRepository,
    params: kotlinx.serialization.json.JsonObject,
    currentAssistantId: Uuid,
    assistantsProvider: () -> List<Pair<Uuid, String>>,
) = buildJsonObject {
    val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: error("query is required for action=search")
    val conversationIdFilter = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }
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
    val assistants = assistantsProvider()
    val assistantFilter = resolveAssistantFilter(
        raw = params["assistant"]?.jsonPrimitive?.contentOrNull ?: "all",
        currentAssistantId = currentAssistantId,
        assistants = assistants,
    )

    val perConversationCounts = mutableMapOf<String, Int>()
    var totalChars = 0
    var resultCount = 0
    var truncated = false
    put("action", "search")
    put("query", query)
    put("results", buildJsonArray {
        // 会话/日期过滤已下推到 FTS 查询；per_conversation_limit 仍在应用层生效，
        // 故多取一些候选，避免单个话痨会话把配额吃光后无结果可补。
        val fetchLimit = (limit * perConversationLimit).coerceIn(limit, 200)
        val rawResults = repo.searchMessages(
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

            val conversation = runCatching {
                repo.getConversationById(Uuid.parse(result.conversationId))
            }.getOrNull()
            if (assistantFilter != null && conversation?.assistantId != assistantFilter) continue
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
                put(
                    "conversation_title",
                    result.title.ifBlank { conversation?.title?.ifBlank { "Untitled" } ?: "Untitled" })
                put("message_id", result.messageId)
                put("message_index", messageIndex)
                put("role", message?.role?.name?.lowercase().orEmpty())
                put("sent_at", message?.sentAtString() ?: result.updateAt.toLocalDateTimeString())
                message?.device?.let { put("device", it) }
                put("snippet", snippet)
            })
        }
    })
    put("truncated", truncated)
    put(
        "hint",
        "Snippets only. Read more with action=fetch (conversation_id + message_id, mode=around_message)."
    )
}

// ------------------------------------------------------------------ fetch

private suspend fun runFetch(
    repo: ConversationRepository,
    params: kotlinx.serialization.json.JsonObject,
) = buildJsonObject {
    val conversationIdRaw = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: error("conversation_id is required for action=fetch")
    val conversation = repo.getConversationById(Uuid.parse(conversationIdRaw))
        ?: error("conversation not found: $conversationIdRaw")
    val messages = conversation.currentMessages
    val mode = params["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "tail"
    val maxChars = (params["max_chars"]?.jsonPrimitive?.intOrNull ?: 12_000).coerceIn(500, 50_000)
    val lastIndex = messages.lastIndex.coerceAtLeast(0)
    val range = when (mode) {
        "full" -> messages.indices
        "range" -> {
            val start = (params["start_index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, lastIndex)
            val end = (params["end_index"]?.jsonPrimitive?.intOrNull ?: start).coerceIn(start, lastIndex)
            start..end
        }

        "around_message" -> {
            val messageId = params["message_id"]?.jsonPrimitive?.contentOrNull
            val center = messages.indexOfFirst { it.id.toString() == messageId }.takeIf { it >= 0 } ?: 0
            val before = (params["before"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(0, 20)
            val after = (params["after"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(0, 20)
            (center - before).coerceAtLeast(0)..(center + after).coerceAtMost(lastIndex)
        }
        // tail：直接给最后 N 条。旧工具没这个模式，调用方只能先 start_index=999999
        // 探一次末尾 index 再回头取区间 —— 白烧一次工具调用。
        else -> {
            val count = (params["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            (messages.size - count).coerceAtLeast(0)..lastIndex
        }
    }

    var usedChars = 0
    var truncated = false
    put("action", "fetch")
    put("conversation_id", conversation.id.toString())
    put("title", conversation.title.ifBlank { "Untitled" })
    put("created_at", conversation.createAt.toLocalDateTimeString())
    put("last_active_at", conversation.updateAt.toLocalDateTimeString())
    put("message_count", messages.size)
    put("mode", if (mode in setOf("full", "range", "around_message")) mode else "tail")
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
            add(buildJsonObject { putMessage(message, index, clipped) })
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

// ------------------------------------------------------------------ helpers

/**
 * assistant 参数解析：`current`（默认）/ `all`（=null，不过滤）/ 助手名或 id。
 * 名字匹配大小写不敏感且允许部分匹配 —— 模型手里通常只有个中文助手名。
 */
private fun resolveAssistantFilter(
    raw: String?,
    currentAssistantId: Uuid,
    assistants: List<Pair<Uuid, String>>,
): Uuid? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return currentAssistantId
    return when (value.lowercase()) {
        "all", "*", "any" -> null
        "current", "self", "this" -> currentAssistantId
        else -> runCatching { Uuid.parse(value) }.getOrNull()
            ?: assistants.firstOrNull { it.second.equals(value, ignoreCase = true) }?.first
            ?: assistants.firstOrNull { it.second.contains(value, ignoreCase = true) }?.first
            ?: currentAssistantId
    }
}

private fun JsonObjectBuilder.putMessage(message: UIMessage, index: Int?, text: String) {
    if (index != null) put("index", index)
    put("message_id", message.id.toString())
    put("role", message.role.name.lowercase())
    put("sent_at", message.sentAtString())
    message.device?.let { put("device", it) }
    put("text", text)
}

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 消息自身的发送时间（本地时区，秒级）。历史上工具只给到「日」，查岗根本没法用。 */
private fun UIMessage.sentAtString(): String = runCatching {
    createdAt.toJavaLocalDateTime().format(MESSAGE_TIME_FORMATTER)
}.getOrDefault("")

private fun Instant.toLocalDateTimeString(): String =
    atZone(ZoneId.systemDefault()).format(MESSAGE_TIME_FORMATTER)

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
    if (isNullOrBlank()) null else LocalDate.parse(this).plusDays(1).atStartOfDay(ZoneId.systemDefault())
        .minusNanos(1).toInstant()
}.getOrNull()
