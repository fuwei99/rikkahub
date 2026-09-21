package me.rerere.rikkahub.data.sync.backend

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "SupabaseBackend"

/**
 * Supabase / PostgREST 后端（多后端抽象 · Step 2）。
 *
 * ## 调用形状
 *
 * 全程走 HTTPS，**不碰 Postgres 线协议**：
 * - 读：`GET  {restBase}/{table}?select=...&{col}=eq.{v}`
 * - 写：`POST {restBase}/rpc/jf_upsert_{nodes,conversations,bundles}`，body 为
 *   `{"payload": [ ... ]}`，LWW 守卫全部固化在 Postgres 函数里。
 *
 * ## 为什么 LWW 放在服务端
 *
 * D1 那边是「客户端拼 SQL，服务端只转发」，守卫写在 SQL 的 `WHERE` 里；
 * PostgREST 的 upsert **没有 `WHERE` 子句**，那套守卫只能在 Postgres 函数里表达。
 * 这反而更干净：逻辑固化在库侧，多端不可能写出不一致的守卫。
 *
 * ## 权限
 *
 * 表只 `GRANT` 给 `service_role`，`anon` 零权限（实测读表返 `42501 permission denied`）。
 * 所以 [StorageBackendConfig.Supabase.serviceKey] 必须是 `service_role` / `sb_secret_*`，
 * 而它**由用户手填、存本地、标 LOCAL 不同步、绝不编译进 APK** —— 与 `d1Config.apiToken` 同款。
 *
 * ## 可选加速
 *
 * 主路径是客户端直连。实测瓶颈是 TLS 握手（1~2 s），握手后 TTFB 只加 0.5~0.7 s。
 * 配了 `proxyUrl` 就由 Worker 终结 TLS、复用连接；没配就直连，功能完全一致。
 * **本类目前只实现直连路径**，代理路径待 Worker 侧就绪后接入。
 */
class SupabaseBackend(
    private val config: StorageBackendConfig.Supabase,
    private val httpClient: HttpClient,
    private val requestTimeoutMs: Long = 30_000L,
) : StorageBackend {

    override val backendId: String get() = config.id
    override val displayName: String get() = "Supabase"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val apiKey: String get() = config.serviceKey

    // MARK: - 自检

    override suspend fun testConnection(): BackendInfo = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val resp: HttpResponse = try {
            httpClient.get("${config.restBase}/") {
                header("apikey", apiKey)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                timeout { requestTimeoutMillis = requestTimeoutMs }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw StorageBackendException("Supabase 不可达：${e.message}", e)
        }
        val text = resp.bodyAsText()
        val rtt = System.currentTimeMillis() - t0
        if (!resp.status.isSuccess()) {
            throw StorageBackendException("Supabase HTTP ${resp.status}：${text.take(300)}")
        }
        val title = runCatching {
            ((json.parseToJsonElement(text) as? JsonObject)?.get("info") as? JsonObject)
                ?.get("title")?.jsonPrimitive?.content
        }.getOrNull() ?: "postgrest"
        BackendInfo(label = displayName, latencyMs = rtt, detail = title)
    }

    /**
     * Supabase 侧的表由 `schema.sql` 在库侧一次性建好（PostgREST 不能跑 DDL）。
     * 这里只调 `jf_health()` 做存在性校验 —— 函数在，则 schema 在。
     */
    override suspend fun ensureSchema() {
        rpcRaw("jf_health", null)
    }

    // MARK: - 会话

    override suspend fun pullConversationManifest(since: Long?): List<ConversationManifestRow> {
        val q = buildString {
            append("select=id,updated_at,sha,deleted&order=updated_at.asc")
            if (since != null) append("&updated_at=gt.$since")
        }
        return getList("conversations", q)
    }

    override suspend fun pullConversationData(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        return getList<ConversationDataRow>("conversations", "select=id,data&id=in.(${inList(ids)})")
            .associate { it.id to it.data }
    }

    override suspend fun pullConversationRows(ids: List<String>): List<ConversationRemoteRow> {
        if (ids.isEmpty()) return emptyList()
        return getList(
            "conversations",
            "select=id,updated_at,sha,data,last_device,deleted&id=in.(${inList(ids)})",
        )
    }

    // MARK: - 节点

    override suspend fun pullNodeManifest(convId: String, since: Long?): List<NodeManifestRow> {
        val q = buildString {
            append("select=conv_id,node_id,idx,select_index,seq_key,updated_at,deleted,sha")
            append("&conv_id=eq.${enc(convId)}")
            if (since != null) append("&updated_at=gt.$since")
            append("&order=seq_key.asc,node_id.asc")
        }
        return getList("conv_nodes", q)
    }

    override suspend fun pullNodeData(convId: String, nodeIds: List<String>): Map<String, String> {
        if (nodeIds.isEmpty()) return emptyMap()
        return getList<NodeDataRow>(
            "conv_nodes",
            "select=node_id,data&conv_id=eq.${enc(convId)}&node_id=in.(${inList(nodeIds)})",
        ).associate { it.nodeId to it.data }
    }

    override suspend fun pullNodeManifests(convIds: List<String>): Map<String, List<NodeManifestRow>> {
        if (convIds.isEmpty()) return emptyMap()
        // PostgREST 的 in.(...) 一次性表达「跨会话批量」，不需要像 D1 那样分块
        return getList<NodeManifestRow>(
            "conv_nodes",
            "select=conv_id,node_id,idx,select_index,seq_key,updated_at,deleted,sha" +
                "&conv_id=in.(${inList(convIds)})" +
                "&order=conv_id.asc,seq_key.asc,node_id.asc",
        ).groupBy { it.convId }
    }

    // MARK: - bundles

    override suspend fun pullBundleMeta(keys: List<String>): List<BundleMetaRow> {
        if (keys.isEmpty()) return emptyList()
        return getList("bundles", "select=k,updated_at,sha,hlc&k=in.(${inList(keys)})")
    }

    override suspend fun pullBundleData(keys: List<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        return getList<BundleDataRow>("bundles", "select=k,data&k=in.(${inList(keys)})")
            .mapNotNull { row -> row.data?.let { row.k to it } }
            .toMap()
    }

    // MARK: - 上行

    override suspend fun pushConversations(rows: List<ConversationPushRow>): Int =
        pushRows("jf_upsert_conversations", rows) { json.encodeToJsonElement(it) }

    override suspend fun pushNodes(rows: List<NodePushRow>): Int =
        pushRows("jf_upsert_nodes", rows) { json.encodeToJsonElement(it) }

    /**
     * 无守卫水位上行 → 库侧 `jf_bump_conversation_meta`。
     *
     * 为什么不能用 `jf_upsert_conversations`：它的第一道闸是
     * `conversations.sha <> excluded.sha`，而 node-only 模式下 sha 恒为空串，
     * 第二次推送起就被挡死、水位再也不动。
     *
     * 为什么不能用 PostgREST 的 `resolution=merge-duplicates`：`sha` / `data` 是
     * `not null` **且无默认值**，插入路径必然违反约束。
     */
    override suspend fun bumpConversationMeta(rows: List<ConversationMetaRow>): Int =
        pushRows("jf_bump_conversation_meta", rows) { json.encodeToJsonElement(it) }

    /**
     * 无守卫整行强推 → PostgREST 的 `resolution=merge-duplicates` upsert。
     *
     * 这条路**不需要库侧函数**：`merge-duplicates` 生成的 `ON CONFLICT DO UPDATE`
     * 没有 `WHERE`，正是「无守卫」的定义；而 `ConversationPushRow` 把
     * `id/title/updated_at/deleted/sha/data/last_device/storage` 全带齐了，
     * 插入路径也不会撞 `not null`。
     */
    override suspend fun forceOverwriteConversations(rows: List<ConversationPushRow>): Int =
        upsertRows("conversations", "id", rows)

    /**
     * 打墓碑 → 一次 PATCH。
     *
     * PostgREST 的 PATCH 是「一个对象应用到**所有**匹配行」，这里恰好是想要的：
     * 这批节点要写的是同一组值。守卫写进 query，与 D1 侧
     * `WHERE ... AND deleted = 0 AND updated_at < ?` 逐字对应。
     * **不碰 `data`** —— 正文原地保留。
     */
    override suspend fun tombstoneNodes(convId: String, nodeIds: List<String>, updatedAt: Long): Int {
        if (nodeIds.isEmpty()) return 0
        return patchCount(
            table = "conv_nodes",
            query = "conv_id=eq.${enc(convId)}" +
                "&node_id=in.(${inList(nodeIds)})" +
                "&deleted=eq.0" +
                "&updated_at=lt.$updatedAt",
            body = buildJsonObject {
                put("deleted", 1)
                put("updated_at", updatedAt)
                put("sha", "tombstone")
            },
        )
    }

    override suspend fun pushBundles(rows: List<BundlePushRow>): Int =
        pushRows("jf_upsert_bundles", rows) { json.encodeToJsonElement(it) }

    override suspend fun observeShardClocks(): Map<String, Long> =
        getList<ShardClockRow>("bundles", "select=k,hlc&kind=eq.shard&hlc=gt.0")
            .associate { it.k to it.hlc }

    // MARK: - 内部

    /**
     * 空批次直接返回 0，**不为空批次花一次往返** —— 这是客户端侧的第一道写放大闸门。
     * 服务端函数里还有第二道（sha 未变 → 不写）。两道都要有，缺一条额度就白省。
     */
    private suspend fun <T> pushRows(
        fn: String,
        rows: List<T>,
        encode: (List<T>) -> JsonElement,
    ): Int {
        if (rows.isEmpty()) return 0
        val out = rpcRaw(fn, encode(rows))
        return out?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    }

    /** 调一个参数为 `jsonb` 的 RPC；[payload] 为 null 时发空对象（无参函数） */
    private suspend fun rpcRaw(fn: String, payload: JsonElement?): JsonElement? =
        withContext(Dispatchers.IO) {
            val url = "${config.restBase}/rpc/$fn"
            val body = if (payload == null) "{}"
            else buildJsonObject { put("payload", payload) }.toString()
            val t0 = System.currentTimeMillis()
            val resp: HttpResponse = try {
                httpClient.post(url) {
                    header("apikey", apiKey)
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    timeout { requestTimeoutMillis = requestTimeoutMs }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw StorageBackendException("Supabase RPC $fn 失败：${e.message}", e)
            }
            val text = resp.bodyAsText()
            Log.i(TAG, "rpc $fn -> ${resp.status} ${System.currentTimeMillis() - t0}ms")
            if (!resp.status.isSuccess()) {
                throw StorageBackendException("Supabase RPC $fn HTTP ${resp.status}：${text.take(300)}")
            }
            runCatching { json.parseToJsonElement(text) }
                .getOrElse { throw StorageBackendException("Supabase RPC 响应解析失败：${it.message}", it) }
        }

    private suspend inline fun <reified T> getList(table: String, query: String): List<T> =
        withContext(Dispatchers.IO) {
            val url = "${config.restBase}/$table?$query"
            val resp: HttpResponse = try {
                httpClient.get(url) {
                    header("apikey", apiKey)
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header("Accept", "application/json")
                    timeout { requestTimeoutMillis = requestTimeoutMs }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw StorageBackendException("Supabase GET $table 失败：${e.message}", e)
            }
            val text = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                throw StorageBackendException("Supabase GET $table HTTP ${resp.status}：${text.take(300)}")
            }
            runCatching { json.decodeFromString<List<T>>(text) }
                .getOrElse { throw StorageBackendException("Supabase 响应解析失败：${it.message}", it) }
        }

    /**
     * PostgREST 的**无条件 upsert**：`POST ?on_conflict=<col>` +
     * `Prefer: resolution=merge-duplicates`。生成的 `ON CONFLICT DO UPDATE` **没有 `WHERE`**。
     *
     * `return=representation` 让 PostgREST 回吐受影响的行 —— 没有它只剩一个 `204`，
     * 「写放大闸门到底有没有生效」就失去唯一证据。
     */
    private suspend fun <T> upsertRows(table: String, onConflict: String, rows: List<T>): Int {
        if (rows.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val url = "${config.restBase}/$table?on_conflict=$onConflict"
            val body = json.encodeToJsonElement(rows).toString()
            val resp: HttpResponse = try {
                httpClient.post(url) {
                    header("apikey", apiKey)
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header("Prefer", "resolution=merge-duplicates,return=representation")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    timeout { requestTimeoutMillis = requestTimeoutMs }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw StorageBackendException("Supabase upsert $table 失败：${e.message}", e)
            }
            val text = resp.bodyAsText()
            Log.i(TAG, "upsert $table -> ${resp.status} rows=${rows.size}")
            if (!resp.status.isSuccess()) {
                throw StorageBackendException("Supabase upsert $table HTTP ${resp.status}：${text.take(300)}")
            }
            runCatching { json.parseToJsonElement(text).jsonArray.size }
                .getOrElse { throw StorageBackendException("Supabase upsert 响应解析失败：${it.message}", it) }
        }
    }

    /** PATCH 一批行，返回**实际被改的行数**（靠 `return=representation` 数出来）。 */
    private suspend fun patchCount(
        table: String,
        query: String,
        body: JsonElement,
    ): Int = withContext(Dispatchers.IO) {
        val url = "${config.restBase}/$table?$query"
        val resp: HttpResponse = try {
            httpClient.patch(url) {
                header("apikey", apiKey)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                timeout { requestTimeoutMillis = requestTimeoutMs }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw StorageBackendException("Supabase PATCH $table 失败：${e.message}", e)
        }
        val text = resp.bodyAsText()
        Log.i(TAG, "patch $table -> ${resp.status}")
        if (!resp.status.isSuccess()) {
            throw StorageBackendException("Supabase PATCH $table HTTP ${resp.status}：${text.take(300)}")
        }
        runCatching { json.parseToJsonElement(text).jsonArray.size }
            .getOrElse { throw StorageBackendException("Supabase PATCH 响应解析失败：${it.message}", it) }
    }

    /** `in.(...)` 过滤器参数；值用双引号包住，避免逗号/括号被解析成语法 */
    private fun inList(values: List<String>): String =
        values.joinToString(",") { "\"${it.replace("\"", "")}\"" }

    /** PostgREST 的 `eq.` 值里不能出现裸逗号/空格，做个最小转义 */
    private fun enc(value: String): String =
        value.replace(",", "%2C").replace(" ", "%20")
}

// ---------------------------------------------------------------------------
// 只在本文件用到的读模型（字段名必须与库侧列名逐字一致）
// ---------------------------------------------------------------------------

@Serializable
private data class ConversationDataRow(
    val id: String,
    val data: String = "",
)

@Serializable
private data class NodeDataRow(
    @SerialName("node_id") val nodeId: String,
    val data: String = "",
)

@Serializable
private data class BundleDataRow(
    val k: String,
    val data: String? = null,
)

@Serializable
private data class ShardClockRow(
    val k: String,
    val hlc: Long = 0,
)
