package me.rerere.rikkahub.data.sync.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 存储后端语义接口（多后端抽象 · Step 1）。
 *
 * ## 为什么是「语义级」而不是「SQL 级」
 *
 * 现有 `SyncEngine` 是**深度 SQL 直连形状**：所有 push/pull 函数都直接拼 SQLite 方言的
 * SQL（`INSERT OR IGNORE` / `INSERT OR REPLACE` / `PRAGMA table_info` / `?` 位置参数）。
 * 而 Supabase 走的是 PostgREST，**根本不能跑裸 SQL**。
 *
 * 于是有三种选择：
 * 1. 在客户端写一个 SQLite → PostgREST 的 SQL 翻译层 —— 25 条语句各翻一遍，方言坑无底；
 * 2. **把接口提到语义级**（本文件），两个 driver 各自实现 —— ✅ 选它；
 * 3. 在 Postgres 里开一个 `jf_exec(sql)` 万能 RPC —— 等于把库钥匙插门上，否决。
 *
 * 语义级接口的额外好处：以后加第三个、第四个后端（TiDB / 自建 PG / 哪怕一个 U 盘）
 * 都是新增一个实现类，`SyncEngine` 一个字不改。
 *
 * ## 返回值的约定
 *
 * - `pushXxx` 返回**实际落库的行数**（不是入参长度）。LWW 守卫会在服务端把
 *   「内容没变 / 被更新的行盖住 / 墓碑」的条目全部过滤掉，所以返回值通常小于入参。
 *   **这个数字就是「写放大闸门」有没有生效的唯一证据**，务必打点。
 * - `pullXxx` 一律返回集合/映射，空结果返回空集合，**不返回 null**。
 */
interface StorageBackend {

    /** 与 [StorageBackendConfig.id] 对应 */
    val backendId: String

    /** 展示名，如 "Cloudflare D1" / "Supabase" */
    val displayName: String

    /** 连通性自检。失败抛 [StorageBackendException]，由 UI 展示原始错误 */
    suspend fun testConnection(): BackendInfo

    /**
     * 幂等建表 / 补列。
     *
     * D1 侧是 `CREATE TABLE IF NOT EXISTS` + `PRAGMA` 补列；
     * Supabase 侧的 schema 由 `schema.sql` 在库侧一次性建好，这里只做存在性校验。
     */
    suspend fun ensureSchema()

    // ---- 会话 ----

    /** 会话增量清单；[since] 为 null 表示全量。对应 `SELECT id,updated_at,sha,deleted ...` */
    suspend fun pullConversationManifest(since: Long?): List<ConversationManifestRow>

    /** 按 id 批量取会话全文（`data` 列） */
    suspend fun pullConversationData(ids: List<String>): Map<String, String>

    /**
     * 按 id 批量取**会话全行**（含 `sha` / `data` / `last_device` / `deleted`）。
     *
     * 冲突裁决（[me.rerere.rikkahub.data.sync.core.ConversationMerger] 那条路）要一次拿到
     * 「远端是什么、谁写的、什么水位」，manifest + data 两次往返在一轮里会被放大成
     * 上百次公网往返，而且两次之间远端还可能变。
     */
    suspend fun pullConversationRows(ids: List<String>): List<ConversationRemoteRow>

    // ---- 节点 ----

    suspend fun pullNodeManifest(convId: String, since: Long?): List<NodeManifestRow>

    /**
     * **跨会话批量**预取节点清单（消 N+1）。
     *
     * 一轮 pull 里几十个会话要读节点清单，逐个调 [pullNodeManifest] 就是几十次串行往返
     * —— 这是「拉取巨慢」的首要原因，不是单次延迟。
     *
     * 无 `since` 参数：预取场景一律取全量清单（每行几十字节），
     * 增量的活儿由调用方拿本地 sha 账簿过滤。
     */
    suspend fun pullNodeManifests(convIds: List<String>): Map<String, List<NodeManifestRow>>

    suspend fun pullNodeData(convId: String, nodeIds: List<String>): Map<String, String>

    // ---- bundles（settings 分片 / memory / favorites / folders / schedules …）----

    suspend fun pullBundleMeta(keys: List<String>): List<BundleMetaRow>

    suspend fun pullBundleData(keys: List<String>): Map<String, String>

    /**
     * bundles **全行**读取（含 `updated_at` / `sha` / `hlc` / `kind` / `data`）。
     *
     * 三段式 CAS 的「读回裁决」要用：拿到远端水位才能判断「该采纳云端还是强推」，
     * 而 [pullBundleMeta] + [pullBundleData] 两跳在裁决路径上正好是两次公网往返。
     */
    suspend fun pullBundleRows(keys: List<String>): List<BundleRemoteRow>

    // ---- 上行 ----

    suspend fun pushConversations(rows: List<ConversationPushRow>): Int

    /**
     * **无守卫**水位上行：只动 `title` / `updated_at` / `last_device`，**绝不碰 `sha` / `data`**。
     *
     * 对应现有 `SyncEngine.pushConversationMetaOnly` 里那条 UPDATE —— 它刻意没有任何
     * sha 守卫。原因：node-only 模式下 conversations 行的 `sha` 恒为空串，而
     * [pushConversations] 的第一道闸就是 `conversations.sha != excluded.sha`，
     * 于是**第二次推送起水位就再也推不动**（读侧靠 updated_at 找增量 → 永久漏拉）。
     */
    suspend fun bumpConversationMeta(rows: List<ConversationMetaRow>): Int

    /**
     * **无守卫**整行强推：覆盖 `title` / `updated_at` / `deleted=0` / `sha` / `data` / `last_device`。
     *
     * 对应现有 `SyncEngine.forcePushConversation`。⚠️ **调用方必须已把 `updated_at` bump 到
     * 严格大于远端**，否则这里就是「拿旧盖新」—— 本方法不做任何新旧比较，那是调用方的契约。
     */
    suspend fun forceOverwriteConversations(rows: List<ConversationPushRow>): Int

    suspend fun pushNodes(rows: List<NodePushRow>): Int

    /**
     * 给 [convId] 下这批节点打墓碑（`deleted = 1`, `sha = 'tombstone'`）。
     *
     * ★ 为什么必须是独立方法、不能借道 [pushNodes]：
     * [NodePushRow] 带 `data` 字段，upsert 的 UPDATE 分支会 `data = excluded.data` ——
     * 拿行去写墓碑就会**把节点正文一并抹成空**。而 `ConversationNodeDiff` 里那条墓碑语句
     * 只改 `deleted` / `updated_at` / `sha`，正文原地不动。独立方法才能表达这个差别。
     *
     * 幂等：只打 `deleted = 0 AND updated_at < ?` 的行，重复调用返 0。
     *
     * @param nodeIds `null` = 该会话下**全部**未删节点（整会话删除走这条，
     *   免得为了拿 id 先去拉一遍清单）；非空列表 = 只打这些节点。
     */
    suspend fun tombstoneNodes(convId: String, nodeIds: List<String>?, updatedAt: Long): Int

    suspend fun pushBundles(rows: List<BundlePushRow>): Int

    /**
     * **无守卫**整行覆盖 bundles 行 —— 三段式 CAS 的最后一步。
     *
     * 走到这里说明调用方已经自己做完新旧裁决（`remoteUp > base` 判过），
     * 并且把 `updated_at` bump 到了严格大于远端。后端再做一次守卫只会
     * 把「对端时钟回拨」这类场景卡成死循环（每轮判输、每轮重推）。
     */
    suspend fun forceOverwriteBundles(rows: List<BundlePushRow>): Int

    /** 分片 HLC 水位（`kind='shard' AND hlc>0`），供 settings 冲突裁决 */
    suspend fun observeShardClocks(): Map<String, Long>
}

/** 链路故障（网络/鉴权/5xx）。与「SQL 本身有问题」区分开，前者可重试/可降级 */
class StorageBackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** [StorageBackend.testConnection] 的结果 */
data class BackendInfo(
    val label: String,
    val latencyMs: Long,
    val detail: String = "",
)

// ---------------------------------------------------------------------------
// 行模型
//
// 字段名一律与库侧列名逐字对应（snake_case），因为 Supabase 侧这些对象会被
// 原样 JSON 序列化后喂给 Postgres 的 `jsonb_to_recordset`，一个字母都不能差。
// ---------------------------------------------------------------------------

@Serializable
data class ConversationManifestRow(
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val sha: String = "",
    val deleted: Int = 0,
)

/**
 * 会话行的「无守卫水位上行」载体 —— 见 [StorageBackend.bumpConversationMeta]。
 *
 * 故意**不含 `sha` / `data`**：字段不在，代码就不可能顺手把它们写没。
 */
@Serializable
data class ConversationMetaRow(
    val id: String,
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("last_device") val lastDevice: String = "",
)

/** bundles 全行读取载体 —— 见 [StorageBackend.pullBundleRows]。 */
@Serializable
data class BundleRemoteRow(
    val k: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    val deleted: Int = 0,
    val sha: String = "",
    val data: String? = null,
    val hlc: Long = 0,
    val kind: String = "legacy",
)

/** 会话全行读取载体 —— 见 [StorageBackend.pullConversationRows]。 */
@Serializable
data class ConversationRemoteRow(
    val id: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    val sha: String = "",
    val data: String? = null,
    @SerialName("last_device") val lastDevice: String = "",
    val deleted: Int = 0,
)

@Serializable
data class NodeManifestRow(
    @SerialName("conv_id") val convId: String,
    @SerialName("node_id") val nodeId: String,
    /** ⚠️ 仅作兼容读取与调试，**不是排序依据**（排序看 [seqKey]） */
    val idx: Int = 0,
    @SerialName("select_index") val selectIndex: Int = 0,
    @SerialName("seq_key") val seqKey: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    val deleted: Int = 0,
    val sha: String = "",
)

@Serializable
data class BundleMetaRow(
    val k: String,
    @SerialName("updated_at") val updatedAt: Long,
    val sha: String = "",
    val hlc: Long = 0,
)

@Serializable
data class ConversationPushRow(
    val id: String,
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Int = 0,
    val sha: String = "",
    val data: String = "",
    @SerialName("last_device") val lastDevice: String = "",
    val storage: String = "",
)

@Serializable
data class NodePushRow(
    @SerialName("conv_id") val convId: String,
    @SerialName("node_id") val nodeId: String,
    /**
     * ⚠️ 与 D1 侧 `ConversationNodeDiff` 保持一致：**只在首次 INSERT 时写入**，
     * LWW 的 UPDATE 分支故意不碰它 —— 这是 2026-09-18 结构分叉的根因修补。
     */
    val idx: Int = 0,
    @SerialName("select_index") val selectIndex: Int = 0,
    @SerialName("seq_key") val seqKey: String = "",
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Int = 0,
    val sha: String = "",
    @SerialName("last_device") val lastDevice: String = "",
    val data: String = "",
)

@Serializable
data class BundlePushRow(
    val k: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Int = 0,
    val sha: String = "",
    val data: String? = null,
    val hlc: Long = 0,
    val kind: String = "legacy",
)