package me.rerere.rikkahub.data.sync.backend

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.sync.d1.D1Client
import me.rerere.rikkahub.data.sync.d1.D1Config
import me.rerere.rikkahub.data.sync.d1.D1ProxyConfig
import me.rerere.rikkahub.data.sync.d1.D1Schema
import me.rerere.rikkahub.data.sync.d1.D1Statement

/**
 * Cloudflare D1 后端（多后端抽象 · Step 1）。
 *
 * 把现有 [D1Client] 包一层，实现 [StorageBackend] 语义接口。SQL 全部照抄自
 * `SyncEngine` / `ConversationNodeDiff`，**不加新语义**。
 *
 * ## ⚠️ 与现有 SyncEngine 的差异（Step G 切换时必须逐项核对）
 *
 * 现有 `SyncEngine.pushConversation` / `pushBundle` 用的是**客户端 CAS 三段式**：
 * ```
 * ① UPDATE ... WHERE k = ? AND updated_at = <本地基线>   ← 命中就直接省掉后面
 * ② 未命中且基线为 0 → INSERT OR IGNORE
 * ③ 都没命中 → 读回远端，走合并 / 强推
 * ```
 * 本类改成**单条 UPSERT + 服务端 LWW 守卫**（和 `ConversationNodeDiff` 一样、
 * 和 Supabase 侧 `jf_upsert_*` 一样）：
 * ```
 * INSERT ... ON CONFLICT DO UPDATE ... WHERE <LWW 守卫>
 * ```
 *
 * **差别有两点，都得记着：**
 * 1. **往返数**：三段式一轮 1~3 次公网往返；单条 UPSERT 恒定 1 次。这是省额度/省时间的地方。
 * 2. **冲突策略**：三段式是「CAS 未命中 → 拉回来做前缀快进合并（[me.rerere.rikkahub.data.sync.core.ConversationMerger]）」，
 *    能保住双方各自新增的消息；单条 LWW 是「新的赢，旧的整行被盖」。
 *    **对 `conversations` 整包而言这是语义降级** —— 快进合并的活儿在 Step G 必须搬到
 *    调用层（先拉、合并、再推），不能指望 UPSERT 帮忙。
 *
 * ## 为什么 Step D 本身没有风险
 *
 * 本类**当前无人引用**。写进来只是把 D1 那一侧的插头先做好，Step G 才真正切流量。
 * 在那之前，现有 `SyncEngine` 的三段式一行没动。
 */
class D1Backend(
    private val config: StorageBackendConfig.D1,
    httpClient: HttpClient,
) : StorageBackend {

    override val backendId: String get() = config.id
    override val displayName: String get() = "Cloudflare D1"

    private val client = D1Client(
        config = D1Config(
            enabled = true,
            accountId = config.accountId,
            databaseId = config.databaseId,
            apiToken = config.apiToken,
        ),
        httpClient = httpClient,
        proxyConfig = if (config.proxyConfigured) {
            D1ProxyConfig(
                enabled = true,
                baseUrl = config.proxyUrl,
                secret = config.proxySecret,
                fallbackToRest = config.proxyFallbackToRest,
                maxBatchSize = config.proxyMaxBatchSize,
                timeoutMs = config.proxyTimeoutMs,
            )
        } else {
            D1ProxyConfig.DISABLED
        },
    )

    /** 代理是否接管了流量。设置页「测试连接」用它显示实际走的是哪条路 */
    val proxyActive: Boolean get() = client.proxyEnabled

    // MARK: - 自检

    override suspend fun testConnection(): BackendInfo = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            client.query("SELECT 1 AS ok")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw StorageBackendException("D1 不可达：${e.message}", e)
        }
        BackendInfo(
            label = displayName,
            latencyMs = System.currentTimeMillis() - t0,
            detail = if (proxyActive) "via sync-proxy" else "direct REST",
        )
    }

    override suspend fun ensureSchema() {
        D1Schema.ensure(client)
    }

    // MARK: - 会话

    override suspend fun pullConversationManifest(since: Long?): List<ConversationManifestRow> {
        val sql: String
        val params: List<Any?>
        if (since == null) {
            sql = "SELECT id, updated_at, sha, deleted FROM conversations ORDER BY updated_at ASC"
            params = emptyList()
        } else {
            sql = "SELECT id, updated_at, sha, deleted FROM conversations " +
                "WHERE updated_at > ? ORDER BY updated_at ASC"
            params = listOf(since)
        }
        return client.query(sql, params).results.mapNotNull { row ->
            val id = row.str("id") ?: return@mapNotNull null
            ConversationManifestRow(
                id = id,
                updatedAt = row.lng("updated_at") ?: 0L,
                sha = row.str("sha") ?: "",
                deleted = row.int("deleted") ?: 0,
            )
        }
    }

    override suspend fun pullConversationData(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val ph = ids.joinToString(",") { "?" }
        return client.query(
            "SELECT id, data FROM conversations WHERE id IN ($ph)",
            ids,
        ).results.mapNotNull { row ->
            val id = row.str("id") ?: return@mapNotNull null
            id to (row.str("data") ?: "")
        }.toMap()
    }

    // MARK: - 节点

    override suspend fun pullNodeManifest(convId: String, since: Long?): List<NodeManifestRow> {
        // 排序交给调用方（NodePullReconciler 要按 seq_key 回退 idx 复合排），SQL 层不排
        val cols = "SELECT conv_id, node_id, idx, select_index, seq_key, updated_at, deleted, sha FROM conv_nodes"
        val sql: String
        val params: List<Any?>
        if (since == null) {
            sql = "$cols WHERE conv_id = ?"
            params = listOf(convId)
        } else {
            sql = "$cols WHERE conv_id = ? AND updated_at > ?"
            params = listOf(convId, since)
        }
        return client.query(sql, params).results.mapNotNull { row ->
            val nodeId = row.str("node_id") ?: return@mapNotNull null
            NodeManifestRow(
                convId = row.str("conv_id") ?: convId,
                nodeId = nodeId,
                idx = row.int("idx") ?: 0,
                selectIndex = row.int("select_index") ?: 0,
                seqKey = row.str("seq_key") ?: "",
                updatedAt = row.lng("updated_at") ?: 0L,
                deleted = row.int("deleted") ?: 0,
                sha = row.str("sha") ?: "",
            )
        }
    }

    override suspend fun pullNodeData(convId: String, nodeIds: List<String>): Map<String, String> {
        if (nodeIds.isEmpty()) return emptyMap()
        val ph = nodeIds.joinToString(",") { "?" }
        return client.query(
            "SELECT node_id, data FROM conv_nodes WHERE conv_id = ? AND node_id IN ($ph)",
            listOf(convId) + nodeIds,
        ).results.mapNotNull { row ->
            val nodeId = row.str("node_id") ?: return@mapNotNull null
            nodeId to (row.str("data") ?: "")
        }.toMap()
    }

    override suspend fun pullNodeManifests(convIds: List<String>): Map<String, List<NodeManifestRow>> {
        if (convIds.isEmpty()) return emptyMap()
        val grouped = HashMap<String, MutableList<NodeManifestRow>>(convIds.size)
        // 分块：SQLite 位置参数上限 999，一次别塞太多 conv_id
        convIds.chunked(MAX_CONVS_PER_MANIFEST_BATCH).forEach { chunk ->
            val ph = chunk.joinToString(",") { "?" }
            client.query(
                "SELECT conv_id, node_id, idx, select_index, seq_key, updated_at, deleted, sha " +
                    "FROM conv_nodes WHERE conv_id IN ($ph) ORDER BY conv_id, seq_key, idx",
                chunk,
            ).results.forEach { row ->
                val cid = row.str("conv_id") ?: return@forEach
                val nodeId = row.str("node_id") ?: return@forEach
                grouped.getOrPut(cid) { mutableListOf() } += NodeManifestRow(
                    convId = cid,
                    nodeId = nodeId,
                    idx = row.int("idx") ?: 0,
                    selectIndex = row.int("select_index") ?: 0,
                    seqKey = row.str("seq_key") ?: "",
                    updatedAt = row.lng("updated_at") ?: 0L,
                    deleted = row.int("deleted") ?: 0,
                    sha = row.str("sha") ?: "",
                )
            }
        }
        return grouped
    }

    // MARK: - bundles

    override suspend fun pullBundleMeta(keys: List<String>): List<BundleMetaRow> {
        if (keys.isEmpty()) return emptyList()
        val ph = keys.joinToString(",") { "?" }
        return client.query(
            "SELECT k, updated_at, sha, hlc FROM bundles WHERE k IN ($ph)",
            keys,
        ).results.mapNotNull { row ->
            val k = row.str("k") ?: return@mapNotNull null
            BundleMetaRow(
                k = k,
                updatedAt = row.lng("updated_at") ?: 0L,
                sha = row.str("sha") ?: "",
                hlc = row.lng("hlc") ?: 0L,
            )
        }
    }

    override suspend fun pullBundleData(keys: List<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val ph = keys.joinToString(",") { "?" }
        return client.query(
            "SELECT k, data FROM bundles WHERE k IN ($ph)",
            keys,
        ).results.mapNotNull { row ->
            val k = row.str("k") ?: return@mapNotNull null
            val data = row.str("data") ?: return@mapNotNull null
            k to data
        }.toMap()
    }

    // MARK: - 上行

    override suspend fun pushConversations(rows: List<ConversationPushRow>): Int {
        if (rows.isEmpty()) return 0
        return rows.chunked(MAX_ROWS_PER_BATCH).sumOf { chunk ->
            val stmts = chunk.map { r ->
                D1Statement(
                    UPSERT_CONVERSATION_SQL,
                    listOf(r.id, r.title, r.updatedAt, r.deleted, r.sha, r.data, r.lastDevice),
                )
            }
            client.batch(stmts).sumOf { it.changes }
        }.toInt()
    }

    override suspend fun pushNodes(rows: List<NodePushRow>): Int {
        if (rows.isEmpty()) return 0
        return rows.chunked(MAX_ROWS_PER_BATCH).sumOf { chunk ->
            val stmts = chunk.map { r ->
                D1Statement(
                    UPSERT_NODE_SQL,
                    listOf(
                        r.convId, r.nodeId, r.idx, r.seqKey, r.selectIndex,
                        r.updatedAt, r.deleted, r.sha, r.data, r.lastDevice,
                    ),
                )
            }
            client.batch(stmts).sumOf { it.changes }
        }.toInt()
    }

    override suspend fun pushBundles(rows: List<BundlePushRow>): Int {
        if (rows.isEmpty()) return 0
        return rows.chunked(MAX_ROWS_PER_BATCH).sumOf { chunk ->
            val stmts = chunk.map { r ->
                D1Statement(
                    UPSERT_BUNDLE_SQL,
                    listOf(r.k, r.updatedAt, r.deleted, r.sha, r.data, r.hlc, r.kind),
                )
            }
            client.batch(stmts).sumOf { it.changes }
        }.toInt()
    }

    override suspend fun observeShardClocks(): Map<String, Long> =
        client.query("SELECT k, hlc FROM bundles WHERE kind = 'shard' AND hlc > 0")
            .results.mapNotNull { row ->
                val k = row.str("k") ?: return@mapNotNull null
                k to (row.lng("hlc") ?: 0L)
            }.toMap()

    // MARK: - SQL

    private companion object {

        /** 单批语句上限。D1 位置参数有上限，一条 7~10 个参数，100 条留足余量 */
        const val MAX_ROWS_PER_BATCH = 100

        /** 批量节点清单的单批会话数上限（每会话一个 `?`，SQLite 参数上限 999） */
        const val MAX_CONVS_PER_MANIFEST_BATCH = 100

        /**
         * 会话整包 UPSERT + LWW 守卫。
         *
         * 守卫三条，顺序即优先级：
         * 1. `sha` 没变 → 整条跳过（**配额闸门，必须放最前**）
         * 2. 墓碑不可复活（删除是不可逆动作）
         * 3. 新赢旧；同毫秒用 `last_device` 字典序兜底（保证两端算出同一赢家）
         */
        val UPSERT_CONVERSATION_SQL = """
            INSERT INTO conversations(id, title, updated_at, deleted, sha, data, last_device)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              title = excluded.title,
              updated_at = excluded.updated_at,
              deleted = excluded.deleted,
              sha = excluded.sha,
              data = excluded.data,
              last_device = excluded.last_device
            WHERE conversations.sha != excluded.sha
              AND NOT (conversations.deleted = 1 AND excluded.deleted = 0)
              AND (excluded.updated_at > conversations.updated_at
                   OR (excluded.updated_at = conversations.updated_at
                       AND excluded.last_device > conversations.last_device))
        """.trimIndent()

        /**
         * 节点 UPSERT + LWW 守卫（照抄 [me.rerere.rikkahub.data.sync.core.ConversationNodeDiff]）。
         *
         * ★ `idx` **只在 INSERT 出现**，UPDATE 分支刻意不碰它 —— idx 是「推送方本地下标」，
         *   是位置量不是身份量，两端各自 append 必然撞车。排序基准已由 `seq_key` 承担。
         *   这是 2026-09-18 结构分叉根因的修补，改它等于把那场事故重演一遍。
         */
        val UPSERT_NODE_SQL = """
            INSERT INTO conv_nodes(conv_id, node_id, idx, seq_key, select_index, updated_at, deleted, sha, data, last_device)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(conv_id, node_id) DO UPDATE SET
              seq_key = excluded.seq_key,
              select_index = excluded.select_index,
              updated_at = excluded.updated_at,
              deleted = excluded.deleted,
              sha = excluded.sha,
              data = excluded.data,
              last_device = excluded.last_device
            WHERE conv_nodes.sha != excluded.sha
              AND NOT (conv_nodes.deleted = 1 AND excluded.deleted = 0)
              AND (excluded.updated_at > conv_nodes.updated_at
                   OR (excluded.updated_at = conv_nodes.updated_at
                       AND excluded.last_device > conv_nodes.last_device))
        """.trimIndent()

        /**
         * bundles UPSERT + LWW 守卫。
         *
         * 与 Supabase 侧 `jf_upsert_bundles` 同款。⚠️ 这套规则是**暂定**的：
         * 现有 D1 三段式用的是 CAS(updated_at) + HLC 水位两套时钟，
         * 语义更细（见 `SyncEngine.writeShardRow`）。切流前必须与 `SyncCrdt.kt` 对齐。
         */
        val UPSERT_BUNDLE_SQL = """
            INSERT INTO bundles(k, updated_at, deleted, sha, data, hlc, kind)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(k) DO UPDATE SET
              updated_at = excluded.updated_at,
              deleted = excluded.deleted,
              sha = excluded.sha,
              data = excluded.data,
              hlc = excluded.hlc,
              kind = excluded.kind
            WHERE bundles.sha != excluded.sha
              AND (excluded.hlc > bundles.hlc
                   OR (excluded.hlc = bundles.hlc AND excluded.updated_at > bundles.updated_at))
        """.trimIndent()
    }
}

// ---- D1 返回行是 JSON 对象，取列的小工具 ----

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.lng(key: String): Long? =
    str(key)?.toLongOrNull()

private fun JsonObject.int(key: String): Int? =
    str(key)?.toIntOrNull()
