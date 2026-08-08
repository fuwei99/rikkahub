package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记忆图节点匹配资格分层（v41 → v42，方案 2026-08-08）：
 *
 * 1. memory_graph_node 增列 match_eligibility（0=常驻池 always，1=门控池 gated）：
 *    gated 节点默认不参与关键词/语义匹配，只有关联节点激活（邻居激活制）后才解锁参与；
 * 2. 删除 credibility 列（可信度暂不需要，更改语义走 update link）。
 *
 * 实现路径：SQLite 的 DROP COLUMN 需 3.35+（Android 13 以下内置版本不满足），
 * 故走「建新表 → 拷数据 → 删旧表 → 改名」的整表重建。memory_graph_link 无外键约束，
 * 只按 id 引用节点，重建不破坏边数据。
 *
 * 迁移约束（沿用 Migration_36_37 同款经验）：
 * - 建表语句与 Room 实体生成结构逐列一致（含 NOT NULL / DEFAULT），避免 TableInfo 校验崩库；
 * - 实体 @Entity 声明的索引必须与建表 SQL 一致。
 */
val Migration_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 新表（与 MemoryGraphNodeEntity 逐列一致；老数据 match_eligibility 一律按常驻池回填）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_graph_node_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                scope TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                importance REAL NOT NULL,
                match_eligibility INTEGER NOT NULL DEFAULT 0,
                folder_path TEXT,
                source_conversation_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // 2. 拷数据：丢弃 credibility，match_eligibility 回填 0（存量数据保持现状行为）
        db.execSQL(
            """
            INSERT INTO memory_graph_node_new
                (id, scope, title, content, importance, match_eligibility, folder_path, source_conversation_id, created_at, updated_at)
            SELECT
                id, scope, title, content, importance, 0, folder_path, source_conversation_id, created_at, updated_at
            FROM memory_graph_node
            """.trimIndent()
        )
        // 3. 换表 + 重建索引
        db.execSQL("DROP TABLE memory_graph_node")
        db.execSQL("ALTER TABLE memory_graph_node_new RENAME TO memory_graph_node")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_node_scope ON memory_graph_node (scope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_node_scope_title ON memory_graph_node (scope, title)")
        Log.i("Migration_41_42", "memory_graph_node rebuilt with match_eligibility, credibility dropped")
    }
}