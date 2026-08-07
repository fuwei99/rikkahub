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
        D1Statement("CREATE INDEX IF NOT EXISTS idx_bundles_updated ON bundles(updated_at)"),
    )

    /** 幂等建表；在启用 D1 同步 / SyncEngine 初始化前调用一次 */
    suspend fun ensure(client: D1Client) {
        if (statements.isNotEmpty()) {
            client.batch(statements)
        }
        ensureConversationColumns(client)
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
}
