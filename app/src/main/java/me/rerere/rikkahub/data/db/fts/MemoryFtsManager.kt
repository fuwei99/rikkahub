package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity

/**
 * 记忆全文检索（记忆图 Phase 2 关键词路）。
 *
 * 与 message_fts 同款方案：FTS5 虚拟表 + jieba simple tokenizer（libsimple 扩展，
 * 见 DataSourceModule onOpen），AND 命中不足时降级 OR 重跑，靠 BM25 rank 排序。
 * 中文无需 trigram，直接复用现有 jieba 分词，检索质量更好。
 *
 * 一致性兜底：onOpen 建表后执行 `rebuild` 全量重建（幂等、毫秒级），
 * 增量钩子（MemoryRepository 写路径）保证本地写入即时可见；
 * 云端 bundle 应用路径即使漏挂增量，下次开库也会被 rebuild 拉齐。
 */
class MemoryFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    data class MemorySearchResult(
        val memoryId: Int,
        val assistantId: String,
        val content: String,
        /** FTS5 BM25 rank：越小越相关（负数，越接近 0 越相关） */
        val rank: Float,
    )

    /** 全量重建（幂等）。记忆量小，开库/云端应用后可安全调用。 */
    suspend fun rebuild() = withContext(Dispatchers.IO) {
        runCatching {
            db.execSQL("INSERT INTO memory_fts(memory_fts) VALUES('rebuild')")
        }.onFailure {
            Log.w(TAG, "memory_fts rebuild failed", it)
        }
    }

    /** 增量 upsert 单条记忆（先删后插，幂等） */
    suspend fun upsert(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        if (memory.content.isBlank()) return@withContext
        runCatching {
            db.execSQL("DELETE FROM memory_fts WHERE memory_id = ?", arrayOf(memory.id.toString()))
            db.execSQL(
                "INSERT INTO memory_fts(content, memory_id, assistant_id) VALUES (?, ?, ?)",
                arrayOf(memory.content, memory.id.toString(), memory.assistantId)
            )
        }.onFailure {
            Log.w(TAG, "memory_fts upsert failed: id=${memory.id}", it)
        }
    }

    suspend fun delete(memoryId: Int) = withContext(Dispatchers.IO) {
        runCatching {
            db.execSQL("DELETE FROM memory_fts WHERE memory_id = ?", arrayOf(memoryId.toString()))
        }
    }

    suspend fun deleteOfScope(assistantId: String) = withContext(Dispatchers.IO) {
        runCatching {
            db.execSQL("DELETE FROM memory_fts WHERE assistant_id = ?", arrayOf(assistantId))
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        runCatching {
            db.execSQL("DELETE FROM memory_fts")
        }
    }

    /**
     * 关键词检索（BM25）。scope 为空搜全部；否则限定单个 scope（assistant id 或全局 id）。
     * AND 命中不足自动降级 OR（同 MessageFtsManager）。
     */
    suspend fun search(
        keyword: String,
        scope: String? = null,
        limit: Int = 20,
    ): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val safeLimit = limit.coerceIn(1, 200)
        val strict = queryOnce(keyword, scope, safeLimit, useOr = false)
        if (strict.size >= OR_FALLBACK_THRESHOLD) return@withContext strict

        val orExpr = buildOrMatchExpr(keyword)
        val loose = if (orExpr != null) queryOnce(orExpr, scope, safeLimit, useOr = true) else emptyList()
        if (loose.isEmpty()) return@withContext strict

        val merged = LinkedHashMap<Int, MemorySearchResult>(strict.size + loose.size)
        strict.forEach { merged[it.memoryId] = it }
        loose.forEach { merged.putIfAbsent(it.memoryId, it) }
        merged.values.take(safeLimit).toList()
    }

    private fun queryOnce(
        keyword: String,
        scope: String?,
        limit: Int,
        useOr: Boolean,
    ): List<MemorySearchResult> {
        val where = if (useOr) "content MATCH ?" else "content MATCH jieba_query(?)"
        val args = mutableListOf<Any>(keyword)
        val whereClause = buildString {
            append(where)
            if (scope != null) {
                append(" AND assistant_id = ?")
                args.add(scope)
            }
        }
        args.add(limit)

        val cursor = runCatching {
            db.query(
                """
                SELECT memory_id, assistant_id, content, rank,
                       simple_snippet(memory_fts, 0, '[', ']', '...', 30) AS snippet
                FROM memory_fts
                WHERE $whereClause
                ORDER BY rank
                LIMIT ?
                """.trimIndent(),
                args.toTypedArray()
            )
        }.getOrElse {
            // MATCH 表达式非法（例如输入里含裸 FTS 运算符）时不崩，返回空
            Log.w(TAG, "memory_fts search failed: keyword=$keyword", it)
            return emptyList()
        }
        val results = mutableListOf<MemorySearchResult>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MemorySearchResult(
                        memoryId = it.getInt(0),
                        assistantId = it.getString(1),
                        content = it.getString(2),
                        rank = it.getFloat(3),
                    )
                )
            }
        }
        return results
    }

    companion object {
        private const val TAG = "MemoryFtsManager"

        /** AND 命中条数达到该阈值就不再降级 OR（同 message_fts 口径） */
        private const val OR_FALLBACK_THRESHOLD = 3

        /** 把 query 按空白拆成 token，用 OR 连接（jieba_query 走 AND，这里做宽松兜底） */
        private fun buildOrMatchExpr(keyword: String): String? {
            val tokens = keyword.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size < 2) return null
            return tokens.joinToString(" OR ") { "\"$it\"" }
        }
    }
}
