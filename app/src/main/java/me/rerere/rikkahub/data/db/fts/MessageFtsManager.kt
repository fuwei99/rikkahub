package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.MessageSearchSettings
import me.rerere.rikkahub.data.model.SearchIndexSource
import java.net.HttpURLConnection
import java.net.URL
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

/**
 * 消息全文检索管理器（2026-11-18 江峰改造：索引来源可切换 FTS / 外部端口）。
 *
 * 防崩红线（用户硬性要求）：
 * 1. source=PORT → **绝不**加载 / 建表 / 重建 message_fts；用户删了表也绝不复活。
 * 2. source=FTS 但本地无索引表 → **不贸然建表**；仅 autoRebuildIndex 开启时懒建表。
 * 3. 既无 FTS 表、source 又指向不可用外部服务 → 搜索返回空，**绝不崩**。
 *
 * 索引写入（indexConversation）与检索（search）共用同一出口，因此：
 * 搜索页、记忆 tool「参考历史聊天记录」、Web API 全部天然跟随此开关，不会再偷回 FTS。
 */
class MessageFtsManager(
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
) {

    private val db get() = database.openHelper.writableDatabase

    /** 同步读当前索引来源设置（settingsFlow 是 StateFlow，取 .value 即当前值）。 */
    private fun currentSettings(): MessageSearchSettings = settingsStore.settingsFlow.value.messageSearch

    /** message_fts 虚拟表是否已存在（不存在说明用户删了 / 从未建过）。 */
    private fun ftsTableExists(): Boolean = runCatching {
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='message_fts' LIMIT 1").use {
            it.moveToFirst()
        }
    }.getOrDefault(false)

    /** 懒建 message_fts 表。只在 source=FTS 且 autoRebuildIndex=true 的写入路径调用。 */
    private fun ensureFtsTable() {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                text,
                node_id UNINDEXED,
                message_id UNINDEXED,
                conversation_id UNINDEXED,
                title UNINDEXED,
                update_at UNINDEXED,
                tokenize = 'simple'
            )
            """.trimIndent()
        )
    }

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val cfg = currentSettings()
        // PORT 模式绝不碰本地 FTS 表。
        if (cfg.source != SearchIndexSource.FTS) return@withContext
        // FTS 模式：表不存在时不贸然建 —— 只有自动重建开启才懒建表。
        if (!ftsTableExists()) {
            if (!cfg.autoRebuildIndex) return@withContext
            runCatching { ensureFtsTable() }.onFailure {
                Log.w(TAG, "ensureFtsTable failed", it); return@withContext
            }
        }
        runCatching {
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
        }.onFailure { Log.w(TAG, "indexConversation failed: ${conversation.id}", it) }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        if (currentSettings().source != SearchIndexSource.FTS) return@withContext
        if (!ftsTableExists()) return@withContext
        runCatching {
            db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        }.onFailure { Log.w(TAG, "deleteConversation failed: $conversationId", it) }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        // PORT 模式绝不碰本地表；FTS 模式表不存在也无从删。
        if (currentSettings().source != SearchIndexSource.FTS) return@withContext
        if (!ftsTableExists()) return@withContext
        runCatching { db.execSQL("DELETE FROM message_fts") }
            .onFailure { Log.w(TAG, "deleteAll failed", it) }
    }

    /**
     * 全文搜索。按 [MessageSearchSettings.source] 分叉：
     * - PORT：POST 到外部检索服务，吃其返回结果；不可用则返回空。
     * - FTS：本地 FTS5 查询；表不存在直接返回空（不崩）。
     */
    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        conversationId: String? = null,
        fromMillis: Long? = null,
        toMillis: Long? = null,
        limit: Int = 50,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val cfg = currentSettings()
        when (cfg.source) {
            SearchIndexSource.PORT -> return@withContext searchViaPort(
                cfg = cfg,
                keyword = keyword,
                sort = sort,
                conversationId = conversationId,
                fromMillis = fromMillis,
                toMillis = toMillis,
                limit = limit,
            )

            SearchIndexSource.FTS -> {
                if (!ftsTableExists()) {
                    Log.i(TAG, "search: FTS source but no index table, return empty")
                    return@withContext emptyList()
                }
            }
        }
        searchViaFts(keyword, sort, conversationId, fromMillis, toMillis, limit)
    }

    /** 本地 FTS5 检索（原逻辑，未改）。 */
    private fun searchViaFts(
        keyword: String,
        sort: MessageSearchSort,
        conversationId: String?,
        fromMillis: Long?,
        toMillis: Long?,
        limit: Int,
    ): List<MessageSearchResult> {
        val safeLimit = limit.coerceIn(1, 200)
        Log.i(TAG, "search(fts): $keyword (conversation=$conversationId, limit=$safeLimit)")
        val strict = queryOnce(
            matchExpr = "jieba_query(?)",
            keyword = keyword,
            sort = sort,
            conversationId = conversationId,
            fromMillis = fromMillis,
            toMillis = toMillis,
            limit = safeLimit,
        )
        if (strict.size >= OR_FALLBACK_THRESHOLD) return strict

        // AND 命中不足，尝试 OR 降级：把分词后的 token 用 OR 连接。
        val orExpr = buildOrMatchExpr(keyword) ?: return strict
        val loose = queryOnce(
            matchExpr = "?",
            keyword = orExpr,
            sort = sort,
            conversationId = conversationId,
            fromMillis = fromMillis,
            toMillis = toMillis,
            limit = safeLimit,
        )
        if (loose.isEmpty()) return strict

        // 严格命中优先，其余按 OR 的 rank 顺序补在后面，按 messageId 去重。
        val merged = LinkedHashMap<String, MessageSearchResult>(strict.size + loose.size)
        strict.forEach { merged[it.messageId] = it }
        loose.forEach { result -> merged.putIfAbsent(result.messageId, result) }
        return merged.values.take(safeLimit).toList()
    }

    /**
     * 外部端口检索。协议：
     *   POST {portUrl}  Authorization: Bearer {portToken}（token 空则不带）
     *   body: {"query","sort","limit","conversationId","fromMillis","toMillis"}
     *   resp: {"results":[{"nodeId","messageId","conversationId","title","updateAt","snippet"}]}
     * 任何异常（无 url / 连不上 / 解析失败）→ 返回空，绝不抛到上层崩 UI。
     */
    private fun searchViaPort(
        cfg: MessageSearchSettings,
        keyword: String,
        sort: MessageSearchSort,
        conversationId: String?,
        fromMillis: Long?,
        toMillis: Long?,
        limit: Int,
    ): List<MessageSearchResult> {
        val urlStr = cfg.portUrl.trim()
        if (urlStr.isBlank()) {
            Log.i(TAG, "search(port): portUrl 为空，返回空")
            return emptyList()
        }
        return runCatching {
            val safeLimit = limit.coerceIn(1, 200)
            val body = buildJsonObject {
                put("query", keyword)
                put("sort", sort.name)
                put("limit", safeLimit)
                put("conversationId", conversationId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("fromMillis", fromMillis?.let { JsonPrimitive(it) } ?: JsonNull)
                put("toMillis", toMillis?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString()
            Log.i(TAG, "search(port): $keyword → $urlStr")

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (cfg.portToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${cfg.portToken}")
                }
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    Log.w(TAG, "search(port) HTTP $code: ${respText.take(200)}")
                    return emptyList()
                }
                if (respText.isBlank()) return emptyList()
                val arr = Json.parseToJsonElement(respText).jsonObject["results"]?.jsonArray
                    ?: return emptyList()
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    MessageSearchResult(
                        nodeId = o["nodeId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        messageId = o["messageId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        conversationId = o["conversationId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        updateAt = Instant.ofEpochMilli(o["updateAt"]?.jsonPrimitive?.longOrNull ?: 0L),
                        snippet = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrElse {
            Log.w(TAG, "search(port) failed: $urlStr", it)
            emptyList()
        }
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
