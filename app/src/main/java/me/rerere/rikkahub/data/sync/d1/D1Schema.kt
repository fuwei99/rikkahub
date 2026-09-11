package me.rerere.rikkahub.data.sync.d1

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * D1 云端 schema（云锚点同步的文本事实源）。
 *
 * 三张表：
 * - [conversations]：一行 = 一个完整会话（含 message_node 树 JSON），
 *   利用现有 "updateConversation 整会话重写" 原子语义做会话级乐观并发；
 *   `last_device` 记录最后写入者，供 [ConversationMerger] 短路与分叉裁决
 * - [conv_nodes]：P3 node 级增量（S2）。一行 = 会话内一个 MessageNode，
 *   推送粒度从「整个会话」降到「一条消息」；双写期与 conversations.data
 *   并存，nodeOnlyPush 开启后成为唯一上行通道
 * - [bundles]：小而杂统一 KV（settings / settings.display / memory / favorites /
 *   folders / genmedia / schedules:<uuid> / subagents:<id> / sync:*）
 *
 * locks 表已废弃（原 P2 会话互斥锁）：单人多设备场景下它只带来每次发送
 * 2~3 次额外往返和误报占用，已改为事后前缀快进合并。不主动 DROP：
 * 老版本客户端可能仍在写它，留着不影响新逻辑。
 *
 * diff 轻量查询：
 *   SELECT id,title,updated_at,sha,deleted FROM conversations;
 *   SELECT k,updated_at,sha,deleted FROM bundles;
 * 即 manifest；全量数据按需 GET data 列。
 */
object D1Schema {

    val statements: List<D1Statement> = listOf(
        D1Statement(
            """
            CREATE TABLE IF NOT EXISTS conversations(
              id         TEXT PRIMARY KEY,
              title      TEXT,
              updated_at INTEGER NOT NULL,
              deleted    INTEGER NOT NULL DEFAULT 0,
              sha        TEXT NOT NULL,
              data       TEXT NOT NULL
            )
            """.trimIndent()
        ),
        D1Statement(
            """
            CREATE TABLE IF NOT EXISTS conv_nodes(
              conv_id      TEXT NOT NULL,
              node_id      TEXT NOT NULL,
              idx          INTEGER NOT NULL,
              select_index INTEGER NOT NULL DEFAULT 0,
              updated_at   INTEGER NOT NULL,
              deleted      INTEGER NOT NULL DEFAULT 0,
              sha          TEXT NOT NULL,
              data         TEXT NOT NULL,
              last_device  TEXT NOT NULL DEFAULT '',
              PRIMARY KEY(conv_id, node_id)
            )
            """.trimIndent()
        ),
        D1Statement(
            """
            CREATE TABLE IF NOT EXISTS bundles(
              k          TEXT PRIMARY KEY,
              updated_at INTEGER NOT NULL,
              deleted    INTEGER NOT NULL DEFAULT 0,
              sha        TEXT NOT NULL,
              data       TEXT
            )
            """.trimIndent()
        ),
        D1Statement("CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at)"),
        D1Statement("CREATE INDEX IF NOT EXISTS idx_conv_nodes_conv ON conv_nodes(conv_id, updated_at)"),
        // 方案 B（node 通道终局）：idx 不再是排序依据，但仍保留索引供调试与兼容读取。
        // 注意：这里**故意不加** UNIQUE(conv_id, idx)。多端并发追加时两端必然算出相同
        // idx，加了唯一约束只会让后到的 UPSERT 直接报错失败（比排序错乱更糟）。
        // 真正的修法是把排序基准从 idx 换成确定性排序键，见 [conv_nodes.seq_key]。
        D1Statement("CREATE INDEX IF NOT EXISTS idx_bundles_updated ON bundles(updated_at)"),
    )

    /** 幂等建表；在启用 D1 同步 / SyncEngine 初始化前调用一次 */
    suspend fun ensure(client: D1Client) {
        if (statements.isNotEmpty()) {
            client.batch(statements)
        }
        ensureConversationColumns(client)
        ensureConvNodeColumns(client)
        ensureBundleColumns(client)
    }

    /**
     * 方案 B：conv_nodes 新增 `seq_key` 列 —— **跨端确定性排序键**。
     *
     * ## 为什么 idx 必须被抛弃
     *
     * `idx` 是推送时节点在**本地列表里的下标**。两端各自在第 10 条后面追加一条，
     * 都会算出 `idx = 10`，于是云端同一会话出现两行 idx=10。pull 端
     * `sortedBy { it.idx }` 对相等的 key **不保证顺序**（取决于 D1 返回行序），
     * 两台设备拼出的消息顺序就不一样 —— 这就是「幽灵分支」在 node 通道侧的根因。
     *
     * ## seq_key 的构造
     *
     * `seq_key = <节点最早消息的 UTC 毫秒时间戳，16 位零填充> + ':' + <nodeId>`
     *
     * - 时间戳决定主序：谁先发的消息排前面，符合直觉
     * - nodeId 字典序兑底：同毫秒也能定下唯一顺序
     * - 零填充为了让**字符串排序 == 数值排序**，于是 `ORDER BY seq_key` 在 SQLite 侧
     *   直接就是正确顺序，不需要拉回本地再排
     *
     * 关键性质：**计算只依赖节点自身内容，与「谁推的」「什么时候推的」「本地有多少条」
     * 全部无关**。两台设备对同一个节点算出的 seq_key 恒等，因此不管以什么顺序写入、
     * 同步多少轮，最终排序结果完全一致 —— 这是收敛性的前提。
     *
     * 旧行 `seq_key` 为空：pull 侧对空值回退到 `idx` 排序，下一次该节点被推送时自动补齐。
     */
    private suspend fun ensureConvNodeColumns(client: D1Client) {
        val cols = client.query("PRAGMA table_info(conv_nodes)").results
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        if ("seq_key" !in cols) {
            client.query("ALTER TABLE conv_nodes ADD COLUMN seq_key TEXT NOT NULL DEFAULT ''")
        }
    }

    /** 合并时代新增 last_device；对旧库幂等补列 */
    private suspend fun ensureConversationColumns(client: D1Client) {
        val cols = client.query("PRAGMA table_info(conversations)").results
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        if ("last_device" !in cols) {
            client.query("ALTER TABLE conversations ADD COLUMN last_device TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * settings 分片化新增 `hlc` / `kind` 两列（大统一重构 v2 §2.6）。
     *
     * - `hlc`：该行内容的 packed HLC 水位。**只用于冲突裁决**，
     *   与 `updated_at`（传输水位，pull 的 `WHERE updated_at > ?` 靠它全局单调）
     *   是两个独立时钟，绝不可互相替代。
     * - `kind`：`legacy`（整包 settings）/ `shard`（分片 envelope）。
     *   有了它，pull 端不必靠 key 名猜行的格式。
     *
     * 默认值刻意选 `0` 和 `'legacy'`：
     * 老版本写的行没有这两列的概念，读出来 `hlc == 0` 正好等于「unknown」
     * （§2.5：不赢不输），`kind == legacy` 正好描述它的真实格式。
     * 这就是「阶段 A 双写期老版本无感」在云端侧的落点。
     */
    private suspend fun ensureBundleColumns(client: D1Client) {
        val cols = client.query("PRAGMA table_info(bundles)").results
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        if ("hlc" !in cols) {
            client.query("ALTER TABLE bundles ADD COLUMN hlc INTEGER NOT NULL DEFAULT 0")
        }
        if ("kind" !in cols) {
            client.query("ALTER TABLE bundles ADD COLUMN kind TEXT NOT NULL DEFAULT 'legacy'")
        }
    }
}
