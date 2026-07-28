package me.rerere.rikkahub.data.sync.d1

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * D1 云端 schema（云锚点同步的文本事实源）。
 *
 * 三张表：
 * - [conversations]：一行 = 一个完整会话（含 message_node 树 JSON），
 *   利用现有 "updateConversation 整会话重写" 原子语义做会话级 LWW
 * - [bundles]：小而杂统一 KV（settings / settings.display / memory / favorites /
 *   folders / genmedia / schedules:<uuid> / subagents:<id> / sync:*）
 * - [locks]：会话互斥锁，单语句 CAS（P2 SyncLockManager 使用）
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
            CREATE TABLE IF NOT EXISTS bundles(
              k          TEXT PRIMARY KEY,
              updated_at INTEGER NOT NULL,
              deleted    INTEGER NOT NULL DEFAULT 0,
              sha        TEXT NOT NULL,
              data       TEXT
            )
            """.trimIndent()
        ),
        D1Statement(
            """
            CREATE TABLE IF NOT EXISTS locks(
              conv_id     TEXT PRIMARY KEY,
              device_id   TEXT NOT NULL,
              device_name TEXT NOT NULL DEFAULT '',
              op          TEXT NOT NULL DEFAULT '',
              acquired_at INTEGER NOT NULL DEFAULT 0,
              expires_at  INTEGER NOT NULL
            )
            """.trimIndent()
        ),
        D1Statement("CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated_at)"),
        D1Statement("CREATE INDEX IF NOT EXISTS idx_bundles_updated ON bundles(updated_at)"),
    )

    /** 幂等建表；在启用 D1 同步 / SyncEngine 初始化前调用一次 */
    suspend fun ensure(client: D1Client) {
        statements.forEach { client.query(it) }
        ensureLockColumns(client)
    }

    /** P0 版 locks 表只有 4 列，P2 起需要 device_name/acquired_at；对旧库幂等补列 */
    private suspend fun ensureLockColumns(client: D1Client) {
        val cols = client.query("PRAGMA table_info(locks)").results
            .mapNotNull { it["name"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        if ("device_name" !in cols) {
            client.query("ALTER TABLE locks ADD COLUMN device_name TEXT NOT NULL DEFAULT ''")
        }
        if ("acquired_at" !in cols) {
            client.query("ALTER TABLE locks ADD COLUMN acquired_at INTEGER NOT NULL DEFAULT 0")
        }
    }
}
