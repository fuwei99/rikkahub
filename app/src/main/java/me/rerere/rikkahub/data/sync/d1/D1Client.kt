package me.rerere.rikkahub.data.sync.d1

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import me.rerere.common.android.SyncPerfLog
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

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
    /**
     * 语句级失败原文（D1 / 代理返回的 error 字段）。成功时为 null。
     *
     * 2026-09-17 事故：旧实现丢弃该字段，上层只能看到一句 SQL 片段，
     * 把「配额耗尽」误判为永久失败。任何语句级失败都必须带上它。
     */
    val error: String? = null,
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
 * 代理链路专用异常：**表示「代理这条路不通」，而非「SQL 有问题」**。
 *
 * 这个区分是自动降级的判据。网络不通 / 401 / 502 属于链路故障，回落 REST 直连
 * 就能救；而某条 SQL 语法错误在直连上照样会错，重试只是白白多花一次往返，
 * 所以后者一律以 [D1Exception] 抛出，不触发降级。
 */
class D1ProxyUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Cloudflare D1 REST API 客户端（App 直连，无需 Worker）。
 *
 * - 认证：Account 级作用域 API Token（Bearer）
 * - 单语句：`query()`；多语句：`batch()` 按顺序逐条执行，避免 D1 对手工拼接多语句
 *   与扁平化 params 的兼容性/结果顺序风险。
 * - 每条语句在 D1 侧原子执行；并发写冲禁由 ConversationMerger 事后合并
 *
 * ## Sync Proxy Worker（可选加速通道）
 *
 * 直连 REST API 时**每条 SQL 都是一次公网往返**（实测 ~1.0s/条，而 D1 侧真实执行
 * 只要 0.13ms —— 99.98% 的时间烧在网络上）。一轮 pullAll 有 17+ 条语句，串行下来
 * 20~30 秒，慢到会把「两端基线不一致」的窗口撑开，进而诱发误判分叉。
 *
 * 配置了 [D1ProxyConfig] 后，[batch] 改走自建 Worker 的 `/batch` 端点：整批 SQL
 * 一次 POST 送过去，Worker 侧用 D1 binding（同机房调用）并发跑完一次性返回。
 * 实测 23.4s → 1.0s。
 *
 * **代理是纯粹的加速通道，不是数据通道**：它挂掉时（[D1ProxyConfig.fallbackToRest]
 * 为 true）自动回落直连，除了变慢没有任何行为差异。
 *
 * 风格对齐 [me.rerere.rikkahub.data.sync.s3.S3Client]：按 config 现用现构造。
 */
class D1Client(
    private val config: D1Config,
    private val httpClient: HttpClient,
    /** 代理配置；[D1ProxyConfig.DISABLED]（默认）表示只走 REST 直连 */
    private val proxyConfig: D1ProxyConfig = D1ProxyConfig.DISABLED,
) {
    /** 解析 Cloudflare 响应必须用宽松模式：meta 等字段集合随版本变化 */
    private val responseJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** 代理是否处于可用状态（仅看配置，不含运行期探测） */
    val proxyEnabled: Boolean get() = proxyConfig.usable

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

    /**
     * 批量执行语句（保序）。
     *
     * D1 的 /query 端点不允许「多语句 + 非空 params」（HTTP 400, code 7400:
     * "params with multiple statements is not supported"），所以：
     * - 所有语句均无参（建表/索引）→ 合并成单个多语句请求，一次往返；
     * - 任一语句带参 → 逐条执行，各自携带自己的 params。
     *
     * 逐条执行没有事务性：中途失败时前面的语句已生效。本库调用方（node diff /
     * schema ensure）的语句均幂等（UPSERT ... WHERE sha != excluded.sha /
     * CREATE TABLE IF NOT EXISTS / tombstone），失败重试安全。
     */
    suspend fun batch(statements: List<D1Statement>): List<D1StatementResult> =
        withContext(Dispatchers.IO) {
            if (statements.isEmpty()) return@withContext emptyList()

            if (proxyConfig.usable) {
                try {
                    return@withContext batchViaProxy(statements)
                } catch (e: D1ProxyUnavailableException) {
                    if (!proxyConfig.fallbackToRest) throw e
                    // 代理不可用但允许降级：这轮改走直连，只慢不错。
                    // 用 warn 而非 error —— 它是预期内的容错路径，不是故障。
                    Log.w(TAG, "sync proxy unavailable, falling back to REST: ${e.message}")
                }
            }

            if (statements.size == 1) {
                // postRaw 本身返回 List<D1StatementResult>，不能再包 listOf（会变 List<List<...>>）
                return@withContext postRaw(statements[0].sql, statements[0].params)
            }
            if (statements.all { it.params.isEmpty() }) {
                // 无参多语句 D1 接受：建表路径一次往返（schema ensure 每进程仅一次）
                val sql = statements.joinToString(";\n") { it.sql.trim().removeSuffix(";") }
                return@withContext postRaw(sql, emptyList(), expectResults = statements.size)
            }
            // 带参多语句：官方 REST API 无 batch 端点，逐条保序执行。
            // 曾错误地合并发送（扁平化 params），被 D1 以 7400 拒绝，
            // 导致会话上传反复失败进隔离区。
            // postRaw 本身返回 List<D1StatementResult>，必须 flatMap 拍平。
            statements.flatMap { postRaw(it.sql, it.params) }
        }

    // MARK: - Sync Proxy

    /**
     * 经 Worker 批量执行。超过 [D1ProxyConfig.maxBatchSize] 自动分块，
     * 块之间仍是顺序发送（保证写语句的先后关系），但每块内部由 Worker 并发跑完。
     */
    private suspend fun batchViaProxy(statements: List<D1Statement>): List<D1StatementResult> {
        val chunkSize = proxyConfig.maxBatchSize.coerceAtLeast(1)
        if (statements.size <= chunkSize) return postProxyChunk(statements)
        return statements.chunked(chunkSize).flatMap { postProxyChunk(it) }
    }

    private suspend fun postProxyChunk(statements: List<D1Statement>): List<D1StatementResult> {
        val startedAt = System.currentTimeMillis()
        val payload = buildJsonObject {
            putJsonArray("statements") {
                statements.forEach { stmt ->
                    addJsonObject {
                        put("sql", stmt.sql)
                        put("params", JsonArray(stmt.params.map { it.toJsonPrimitive() }))
                    }
                }
            }
        }.toString()

        val response: HttpResponse = try {
            httpClient.post(proxyConfig.endpoint("batch")) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${proxyConfig.secret}")
                setBody(payload)
                timeout { requestTimeoutMillis = proxyConfig.timeoutMs }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是生命周期事件（切后台/杀进程），不是代理故障。
            // 当成故障会触发一次毫无意义的 REST 重试，而且那次重试同样会被取消。
            throw e
        } catch (e: Throwable) {
            throw D1ProxyUnavailableException("proxy request failed: ${e.message}", e)
        }

        val text = response.bodyAsText()
        // 性能打点：via=proxy，用来验证代理是否真的接管了流量。
        // 若日志里 rest 计数不为 0，说明存在静默降级，代理白配。
        SyncPerfLog.request(
            via = "proxy",
            statements = statements.size,
            ms = System.currentTimeMillis() - startedAt,
            bytes = text.length,
            sqlHint = statements.firstOrNull()?.sql?.replace('\n', ' ')?.take(90).orEmpty() +
                if (statements.size > 1) " (+${statements.size - 1} more)" else "",
        )
        if (!response.status.isSuccess()) {
            // 4xx/5xx 一律视作链路问题：401 是 token 配错，5xx 是 Worker 侧异常，
            // 两者直连都能绕过去。
            throw D1ProxyUnavailableException("proxy HTTP ${response.status}: ${text.take(200)}")
        }

        val envelope = runCatching { responseJson.decodeFromString<D1ApiEnvelope>(text) }
            .getOrElse { throw D1ProxyUnavailableException("proxy response unparseable: ${it.message}", it) }

        if (!envelope.success) {
            val detail = envelope.errors.joinToString("; ") { "[${it.code}] ${it.message}" }
            throw D1ProxyUnavailableException("proxy rejected batch: $detail")
        }

        // 到这里链路是通的。个别语句失败属于 SQL 层问题，抛 D1Exception ——
        // 它在直连上同样会失败，降级重试没有意义。
        //
        // ⚠️ 必须把 D1 的原始错误原文带出去（2026-09-17 事故）：只抛 SQL 片段会把
        // 「配额超限 / 限流 / 约束冲突」这些真因全部吞掉，上层分类器只能看到
        // "D1 statement failed" 四个字，于是把「配额耗尽」这种次日即恢复的瞬时错误
        // 误判成永久失败 —— 额度回来了 outbox 也不会自愈。
        envelope.result.forEachIndexed { idx, r ->
            if (!r.success) {
                val sql = statements.getOrNull(idx)?.sql?.replace('\n', ' ')?.take(120)
                val detail = r.error?.take(400)
                throw D1Exception(
                    if (detail != null) "D1 statement failed via proxy: $detail | sql=$sql"
                    else "D1 statement failed via proxy: $sql"
                )
            }
        }
        if (envelope.result.size != statements.size) {
            // 数量对不上意味着结果无法按下标对齐调用方的期望，继续用下去会张冠李戴。
            throw D1ProxyUnavailableException(
                "proxy returned ${envelope.result.size} results for ${statements.size} statements"
            )
        }
        return envelope.result
    }

    /** 代理健康探针；供设置页「测试」按钮使用。返回往返耗时描述。 */
    suspend fun probeProxy(): String = withContext(Dispatchers.IO) {
        if (!proxyConfig.usable) throw D1ProxyUnavailableException("proxy not configured")
        val t0 = System.currentTimeMillis()
        val results = postProxyChunk(listOf(D1Statement("SELECT 1 AS ok")))
        val rtt = System.currentTimeMillis() - t0
        if (results.firstOrNull()?.success != true) throw D1ProxyUnavailableException("probe query failed")
        "${rtt}ms"
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

        val startedAt = System.currentTimeMillis()
        val response: HttpResponse = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${config.apiToken}")
            setBody(body)
        }
        val text = response.bodyAsText()
        // 性能打点：via=rest（直连 Cloudflare）。这条出现得越多，代理越形同虚设。
        SyncPerfLog.request(
            via = "rest",
            statements = expectResults,
            ms = System.currentTimeMillis() - startedAt,
            bytes = text.length,
            sqlHint = sql.replace('\n', ' ').take(90),
        )
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
            if (!r.success) {
                // 同 postProxyChunk：直连路径也必须保留 D1 原始错误原文，否则分类器失真。
                val detail = r.error?.take(400)
                val sqlHint = sql.replace('\n', ' ').take(120)
                throw D1Exception(
                    if (detail != null) "D1 statement failed: $detail | sql=$sqlHint"
                    else "D1 statement failed: $sqlHint"
                )
            }
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
        // 官方 REST API 没有 /batch 端点；带参多语句只能逐条执行。
        // 未来若开放批处理端点，可在这里恢复合并以省往返。
    }
}
