package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    /**
     * 全文搜索。
     *
     * 过滤条件（会话、时间区间）全部下推到 SQL，避免「先 LIMIT 再过滤」导致
     * 高频词场景下过滤器形同虚设（命中数超出 limit 时，目标结果可能根本没被取出来）。
     *
     * 当 jieba 默认的 AND 查询命中过少时，自动降级为 OR 查询重跑一次，
     * 靠 FTS5 的 BM25 rank 把多词命中的结果自然排到前面。
     */
    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        conversationId: String? = null,
        fromMillis: Long? = null,
        toMillis: Long? = null,
        limit: Int = 50,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 200)
        Log.i(TAG, "search: $keyword (conversation=$conversationId, limit=$safeLimit)")
        val strict = queryOnce(
            matchExpr = "jieba_query(?)",
            keyword = keyword,
            sort = sort,
            conversationId = conversationId,
            fromMillis = fromMillis,
            toMillis = toMillis,
            limit = safeLimit,
        )
        if (strict.size >= OR_FALLBACK_THRESHOLD) return@withContext strict

        // AND 命中不足，尝试 OR 降级：把分词后的 token 用 OR 连接。
        val orExpr = buildOrMatchExpr(keyword) ?: return@withContext strict
        val loose = queryOnce(
            matchExpr = "?",
            keyword = orExpr,
            sort = sort,
            conversationId = conversationId,
            fromMillis = fromMillis,
            toMillis = toMillis,
            limit = safeLimit,
        )
        if (loose.isEmpty()) return@withContext strict

        // 严格命中优先，其余按 OR 的 rank 顺序补在后面，按 messageId 去重。
        val merged = LinkedHashMap<String, MessageSearchResult>(strict.size + loose.size)
        strict.forEach { merged[it.messageId] = it }
        loose.forEach { result -> merged.putIfAbsent(result.messageId, result) }
        merged.values.take(safeLimit).toList()
    }

    private fun queryOnce(
        matchExpr: String,
        keyword: String,
        sort: MessageSearchSort,
        conversationId: String?,
        fromMillis: Long?,
        toMillis: Long?,
        limit: Int,
    ): List<MessageSearchResult> {
        val where = StringBuilder("text MATCH $matchExpr")
        val args = mutableListOf<Any>(keyword)
        if (conversationId != null) {
            where.append(" AND conversation_id = ?")
            args.add(conversationId)
        }
        if (fromMillis != null) {
            where.append(" AND CAST(update_at AS INTEGER) >= ?")
            args.add(fromMillis)
        }
        if (toMillis != null) {
            where.append(" AND CAST(update_at AS INTEGER) <= ?")
            args.add(toMillis)
        }
        args.add(limit)

        val cursor = runCatching {
            db.query(
                """
                SELECT node_id, message_id, conversation_id, title, update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                WHERE $where
                ORDER BY ${sort.orderBy}
                LIMIT ?
                """.trimIndent(),
                args.toTypedArray()
            )
        }.getOrElse {
            // MATCH 表达式非法（例如输入里含裸的 FTS 运算符）时不要崩，返回空。
            Log.w(TAG, "search failed: keyword=$keyword", it)
            return emptyList()
        }
        val results = mutableListOf<MessageSearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        return results
    }

    /**
     * 取 jieba 分词结果改拼成 OR 表达式。
     * jieba_query 返回形如 `"a" "b"` 的串，按引号切出 token 即可。
     */
    private fun buildOrMatchExpr(keyword: String): String? {
        val tokenized = runCatching {
            db.query("SELECT jieba_query(?)", arrayOf(keyword)).use { c ->
                if (c.moveToNext()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val tokens = QUOTED_TOKEN.findAll(tokenized)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        // 单 token 时 OR 与 AND 等价，没必要重跑。
        if (tokens.size < 2) return null
        return tokens.joinToString(" OR ") { "\"$it\"" }
    }

    companion object {
        /** 严格命中数低于该阈值时才做 OR 降级。 */
        private const val OR_FALLBACK_THRESHOLD = 3
        private val QUOTED_TOKEN = Regex("\"([^\"]+)\"")
    }
}

/**
 * 参与索引的文本。
 *
 * 除正文外也收录工具调用的名称与入参：像「上次改了哪个文件」这类问题，
 * 关键信息（路径、命令）只存在于 tool input 里，不索引就永远搜不到。
 * 工具输出通常很长且噪声大，只取前若干字符。
 */
private fun UIMessage.extractFtsText(): String = buildString {
    parts.forEach { part ->
        val piece = when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Tool -> buildString {
                append(part.toolName)
                val input = part.input.trim()
                if (input.isNotBlank() && input != "{}") {
                    append('\n')
                    append(input.take(TOOL_INPUT_LIMIT))
                }
                val output = part.output
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .trim()
                if (output.isNotBlank()) {
                    append('\n')
                    append(output.take(TOOL_OUTPUT_LIMIT))
                }
            }

            else -> ""
        }
        if (piece.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append(piece)
        }
    }
}.take(FTS_TEXT_LIMIT)

private const val TOOL_INPUT_LIMIT = 600
private const val TOOL_OUTPUT_LIMIT = 400
private const val FTS_TEXT_LIMIT = 10_000
