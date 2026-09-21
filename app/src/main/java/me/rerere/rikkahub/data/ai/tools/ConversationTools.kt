package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.takeLastSafe
import me.rerere.ai.util.takeSafe
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.time.Instant
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
    /**
     * 发起方助手 / 会话。**可空 = 没有会话上下文**（HTTP `/api/tools` 那条路，
     * 调用方不是某个对话里的模型）。
     *
     * 为 null 时：
     * - `assistant` 参数的默认档从 `current` 退化成「不过滤」（否则会拿一个不存在的
     *   id 去筛，结果恒为空 —— 静默返回空集比报错更难查）；
     * - `conversation_id` 不参与「排除自身」，因为没有自身可排。
     */
    assistantId: Uuid?,
    conversationId: Uuid?,
    /** 全部助手 (id, name)，用于 assistant 参数解析与回显助手名 */
    assistantsProvider: () -> List<Pair<Uuid, String>> = { emptyList() },
): List<Tool> = listOf(
    Tool(
        name = "chat_history",
        description = """
            Read the user's past conversations.

            - action=recent — conversations ordered by LAST ACTIVITY (newest first; pinned is NOT boosted).
              Precise timestamps, the sending device, message counts, and optionally the tail of each.
            - action=search — keyword search over message text; returns bounded snippets.
            - action=fetch — read one conversation: mode=tail | around | full.

            Message text is ALWAYS clipped to `chars` with the MIDDLE elided (`…[省略 N 字]…`), never a
            head-only cut — replies often open with a tool call and end with the conclusion. Need a message
            in full? That is what action=fetch is for.

            Every message carries `sent_at` (local time, second precision) and, for user messages, `device`
            (absent on older messages — treat missing as unknown, not as evidence). Never parse timestamps
            out of message text.
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
                    // 设计约束：**每个键只有一个含义**。
                    // 从前 `limit` 一名三义（会话数/命中数/消息数，三套默认值三套上限），
                    // 是这套 schema 读不懂的根源 —— 阅读者必须先判断 action 才能知道这个数是什么。
                    // 现在改成「条数上限」单义，名词差异交给 description 一句话说清。
                    // action 决定哪些键有意义；传了不相关的键会被忽略，不会报错。

                    // —— 所有 action 共用 ——
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Max items returned. recent = conversations (default 10), " +
                                "search = hits (default 8), fetch = messages (default 15). Max 30."
                        )
                    })
                    put("chars", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Char budget per message (default 400, max 4000), head+tail with the middle " +
                                "elided. recent: 0 = metadata only, no text."
                        )
                    })
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "search: restrict to one conversation. fetch: which one to read.")
                    })

                    // —— recent / search ——
                    put("assistant", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "recent/search: 'current' (recent default), 'all', or an assistant name/id"
                        )
                    })

                    // —— recent ——
                    put("scope", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("user")
                            add("all")
                        })
                        put(
                            "description",
                            "recent: 'user' (default) excludes agent conversations (sub-agents, scheduled " +
                                "tasks, supervision) and your own; 'all' includes everything."
                        )
                    })
                    put("minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "recent: only conversations active within the last N minutes")
                    })
                    put("messages", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "recent: also return the last N messages of each conversation (default 0, max 20)"
                        )
                    })

                    // —— search ——
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "search: keywords to look for in past messages")
                    })
                    put("days", buildJsonObject {
                        put("type", "integer")
                        put("description", "search: only look back N days")
                    })

                    // —— fetch ——
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("tail")
                            add("around")
                            add("full")
                        })
                        put(
                            "description",
                            "fetch: 'tail' (default) = last `limit` messages | 'around' = around `anchor` | " +
                                "'full' = whole conversation"
                        )
                    })
                    put("anchor", buildJsonObject {
                        put("type", "string")
                        put("description", "fetch + mode=around: the message id to center on (from search)")
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
    currentAssistantId: Uuid?,
    currentConversationId: Uuid?,
    assistantsProvider: () -> List<Pair<Uuid, String>>,
) = buildJsonObject {
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
    val assistants = assistantsProvider()
    val assistantFilter = resolveAssistantFilter(
        raw = params["assistant"]?.jsonPrimitive?.contentOrNull,
        currentAssistantId = currentAssistantId,
        assistants = assistants,
    )
    // scope 一个键管两件事（agent 会话 + 自己），顶掉从前的 exclude_agents / exclude_self。
    val includeEverything = params["scope"]?.jsonPrimitive?.contentOrNull?.lowercase() == "all"
    val excludeAgents = !includeEverything
    val excludeSelf = !includeEverything
    val sinceMinutes = params["minutes"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    // 一个 chars 管「最后一条预览」+「附带消息」，不再分 preview_chars / tail_message_chars。
    // 0 = 只给 message_id / role / 时间，不给正文。
    val chars = (params["chars"]?.jsonPrimitive?.intOrNull ?: 400).coerceIn(0, 4000)
    val tailCount = (params["messages"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 20)

    val rows = repo.getRecentConversationSummaries(
        assistantId = assistantFilter,
        limit = limit,
        excludeAgents = excludeAgents,
        excludeConversationId = currentConversationId.takeIf { excludeSelf },
        sinceMillis = sinceMinutes?.let { Instant.now().minusSeconds(it * 60L).toEpochMilli() },
        tailMessages = maxOf(tailCount, if (chars > 0) 1 else 0),
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
                if (currentConversationId != null && row.id == currentConversationId.toString()) {
                    put("is_current_conversation", true)
                }
                val last = row.tailMessages.lastOrNull()
                if (last != null && chars > 0) {
                    put("last_message_role", last.role.name.lowercase())
                    put("last_message_at", last.sentAtString())
                    last.device?.let { put("last_message_device", it) }
                    put("last_message_preview", last.toSearchText().headTailSafe(chars))
                }
                if (tailCount > 0 && row.tailMessages.isNotEmpty()) {
                    put("last_messages", buildJsonArray {
                        row.tailMessages.takeLast(tailCount).forEach { message ->
                            add(
                                buildJsonObject {
                                    putMessage(
                                        message,
                                        index = null,
                                        text = message.toSearchText().headTailSafe(chars),
                                    )
                                }
                            )
                        }
                    })
                }
            })
        }
    })
    put(
        "hint",
        "Timestamps and devices are structured fields here — never parse them out of message text. " +
            "Text is head+tail clipped; use action=fetch for a message in full, action=search for keywords."
    )
}

// ------------------------------------------------------------------ search

private suspend fun runSearch(
    repo: ConversationRepository,
    params: kotlinx.serialization.json.JsonObject,
    currentAssistantId: Uuid?,
    assistantsProvider: () -> List<Pair<Uuid, String>>,
) = buildJsonObject {
    val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: error("query is required for action=search")
    val conversationIdFilter = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf { it.isNotBlank() }
    // days 顶掉 from_date / to_date：实际用法 99% 是「最近 N 天」，两个日期键不值那份 schema 成本。
    val days = params["days"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 8).coerceIn(1, 30)
    val contextChars = (params["chars"]?.jsonPrimitive?.intOrNull ?: 400).coerceIn(120, 4000)
    // 下面两个从前是 schema 键，但没人真调过 —— 收成常量，schema 少两行，行为不变。
    val perConversationLimit = 2
    val maxTotalChars = 6000
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
            sort = MessageSearchSort.RELEVANCE,
            conversationId = conversationIdFilter,
            fromMillis = days?.let { Instant.now().minusSeconds(it * 86_400L).toEpochMilli() },
            toMillis = null,
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
                ?: result.snippet.takeSafe(contextChars)
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
        "Snippets only. Read more with action=fetch (conversation_id + mode=around + anchor=message_id)."
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
    val chars = (params["chars"]?.jsonPrimitive?.intOrNull ?: 400).coerceIn(100, 4000)
    // 单次调用的总字符上限。从前是 `max_chars` 键（默认 12000 / 上限 50000）。
    // 收成常量：一把 fetch 能吐多长是**护栏**不是旋钮 —— 没人有理由把它调到 5 万。
    val maxChars = 30_000
    val lastIndex = messages.lastIndex.coerceAtLeast(0)
    val range = when (mode) {
        "full" -> messages.indices
        "around" -> {
            val anchor = params["anchor"]?.jsonPrimitive?.contentOrNull
            val center = messages.indexOfFirst { it.id.toString() == anchor }.takeIf { it >= 0 } ?: 0
            // 前后条数是常量（3 / 5）。想精确控制范围应该用 search 定位，不是调旋钮。
            (center - 3).coerceAtLeast(0)..(center + 5).coerceAtMost(lastIndex)
        }
        // tail：直接给最后 N 条。旧工具没这个模式，调用方只能先 start_index=999999
        // 探一次末尾 index 再回头取区间 —— 白烧一次工具调用。range 模式随之删除。
        else -> {
            val count = (params["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 30)
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
    put("mode", if (mode in setOf("full", "around")) mode else "tail")
    put("messages", buildJsonArray {
        for (index in range) {
            val message = messages.getOrNull(index) ?: continue
            val text = message.toSearchText()
            val remaining = maxChars - usedChars
            if (remaining <= 0) {
                truncated = true
                break
            }
            // 每条先按 chars 做头尾保留（中间省略），再用剩余总预算兜一次底。
            val clipped = text.headTailSafe(chars).takeSafe(remaining)
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
        put(
            "hint",
            "Result hit the total char budget. Narrow it down: lower `limit`, use mode=around + anchor, " +
                "or raise `chars` to keep fewer messages in full."
        )
    }
}

// ------------------------------------------------------------------ helpers

/**
 * assistant 参数解析：`current`（默认）/ `all`（=null，不过滤）/ 助手名或 id。
 * 名字匹配大小写不敏感且允许部分匹配 —— 模型手里通常只有个中文助手名。
 */
private fun resolveAssistantFilter(
    raw: String?,
    /** null = 无会话上下文（HTTP 侧），`current` 档退化成「不过滤」。 */
    currentAssistantId: Uuid?,
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
                    append(input.takeSafe(600))
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

/**
 * 头尾保留：留前一半 + 后一半，中间挖空并标注省了多少字。
 *
 * 为什么不用 [takeSafe]（只留头）：会话消息开头常常是工具调用 / 思考块，**结论在末尾**。
 * 只留头 = 预览全是噪音 = 等于没预览；只留尾又会丢掉用户的开场提问。
 * 头尾都留、中间省略，要全文再 `action=fetch`。
 *
 * 代理对安全：头用 [takeSafe]、尾用 [takeLastSafe]，两侧都不会劈开 emoji
 * （见 `SurrogateSafe.kt` 里那次落库崩溃事故）。
 */
private fun String.headTailSafe(budget: Int): String {
    if (budget <= 0) return ""
    if (length <= budget) return this
    val head = budget / 2
    val tail = budget - head
    val h = takeSafe(head)
    val t = takeLastSafe(tail)
    val removed = length - h.length - t.length
    return "$h…[省略 $removed 字]…$t"
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
    var start = (hit - half).coerceIn(0, (length - maxChars).coerceAtLeast(0))
    var end = (start + maxChars).coerceAtMost(length)
    // 代理对安全：切口不许落在 emoji（代理对）中间，否则孤立代理会在落库时吞掉
    // 相邻的 JSON 转义反斜杠，导致整条会话反序列化崩溃。详见 ai/util/SurrogateSafe.kt
    if (start > 0 && this[start].isLowSurrogate()) start += 1
    if (end < length && this[end - 1].isHighSurrogate()) end -= 1
    return buildString {
        if (start > 0) append("...")
        append(this@snippetAround.substring(start, end))
        if (end < this@snippetAround.length) append("...")
    }
}

