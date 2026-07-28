package me.rerere.rikkahub.data.sync.d1

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "D1Client"

/** 单条 SQL 与其位置参数（仅支持 String/Number/Boolean/null） */
data class D1Statement(
    val sql: String,
    val params: List<Any?> = emptyList(),
)

@Serializable
data class D1Meta(
    val changes: Long = 0,
    @SerialName("last_row_id") val lastRowId: Long = 0,
    @SerialName("rows_read") val rowsRead: Long = 0,
    @SerialName("rows_written") val rowsWritten: Long = 0,
)

@Serializable
data class D1StatementResult(
    val success: Boolean = false,
    /** 查询行；DML 语句为空数组。每行是 {列名: 值} 的 JsonObject */
    val results: List<JsonObject> = emptyList(),
    val meta: D1Meta? = null,
) {
    /** 受影响行数；用于 CAS/乐观写的冲突判决（0 = 条件未命中） */
    val changes: Long get() = meta?.changes ?: 0L
    val lastRowId: Long get() = meta?.lastRowId ?: 0L
}

@Serializable
private data class D1ApiMessage(
    val code: Int = 0,
    val message: String = "",
)

@Serializable
private data class D1ApiEnvelope(
    val success: Boolean = false,
    val errors: List<D1ApiMessage> = emptyList(),
    val result: List<D1StatementResult> = emptyList(),
)

class D1Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Cloudflare D1 REST API 客户端（App 直连，无需 Worker）。
 *
 * - 认证：Account 级作用域 API Token（Bearer）
 * - 单语句：`query()`；多语句：`batch()` 按顺序逐条执行，避免 D1 对手工拼接多语句
 *   与扁平化 params 的兼容性/结果顺序风险。
 * - 每条语句在 D1 侧原子执行；全局单写者 → CAS/锁语义见 SyncLockManager（P2）
 *
 * 风格对齐 [me.rerere.rikkahub.data.sync.s3.S3Client]：按 config 现用现构造。
 */
class D1Client(
    private val config: D1Config,
    private val httpClient: HttpClient,
) {
    /** 解析 Cloudflare 响应必须用宽松模式：meta 等字段集合随版本变化 */
    private val responseJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** 连通性自检（等价 S3Sync.testS3） */
    suspend fun test() {
        query("SELECT 1").also {
            Log.i(TAG, "test: connection successful")
        }
    }

    suspend fun query(statement: D1Statement): D1StatementResult =
        batch(listOf(statement)).first()

    suspend fun query(sql: String, params: List<Any?> = emptyList()): D1StatementResult =
        query(D1Statement(sql, params))

    suspend fun batch(statements: List<D1Statement>): List<D1StatementResult> =
        withContext(Dispatchers.IO) {
            if (statements.isEmpty()) return@withContext emptyList()
            if (statements.size == 1) {
                // postRaw 本身返回 List<D1StatementResult>，不能再包 listOf（会变 List<List<...>>）
                return@withContext postRaw(statements[0].sql, statements[0].params)
            }
            statements.chunked(BATCH_CHUNK).flatMap { chunk ->
                val sql = chunk.joinToString(";\n") { it.sql.trim().removeSuffix(";") }
                val params = chunk.flatMap { it.params }
                val merged = postRaw(sql, params, expectResults = chunk.size)
                if (merged.size == chunk.size) {
                    merged
                } else {
                    // 兼容某些版本对多语句合并返回的异常：退化为逐条执行
                    Log.w(TAG, "batch: merged result count ${merged.size} != ${chunk.size}, fallback to sequential")
                    chunk.flatMap { postRaw(it.sql, it.params) }
                }
            }
        }

    // MARK: - HTTP

    private suspend fun postRaw(
        sql: String,
        params: List<Any?>,
        expectResults: Int = 1,
    ): List<D1StatementResult> {
        // Use /query because the app models rows as JSON objects. /raw returns row arrays,
        // which loses column names and makes PRAGMA/SELECT parsing fail as a fake
        // connectivity error in the UI.
        val url = config.endpoint("query")
        val body = buildJsonObject {
            put("sql", sql)
            put("params", JsonArray(params.map { it.toJsonPrimitive() }))
        }.toString()

        val response: HttpResponse = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            Log.e(TAG, "postRaw failed: ${response.status} - $text")
            throw D1Exception("D1 HTTP ${response.status}: $text")
        }

        val envelope = runCatching { responseJson.decodeFromString<D1ApiEnvelope>(text) }
            .getOrElse { throw D1Exception("Failed to parse D1 response: ${it.message}", it) }

        if (!envelope.success || envelope.errors.isNotEmpty()) {
            val detail = envelope.errors.joinToString("; ") { "[${it.code}] ${it.message}" }
            throw D1Exception("D1 API error: $detail")
        }

        val statementResults = envelope.result.onEach { r ->
            if (!r.success) throw D1Exception("D1 statement failed: $sql")
        }
        if (statementResults.size != expectResults) {
            Log.w(TAG, "postRaw: expected $expectResults results, got ${statementResults.size}")
        }
        return statementResults
    }

    private fun Any?.toJsonPrimitive(): JsonElement = when (this) {
        null -> JsonNull
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Char -> JsonPrimitive(this.toString())
        else -> JsonPrimitive(this.toString())
    }

    companion object {
        /** 保留给未来官方 /batch endpoint 使用；当前 batch() 为保序逐条执行。 */
        const val BATCH_CHUNK = 90
    }
}
