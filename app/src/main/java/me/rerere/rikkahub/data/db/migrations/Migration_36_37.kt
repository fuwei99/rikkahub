package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记忆图与全量记忆分离（v36 → v37）：
 *
 * 1. 新建 memory_graph_node —— 独立图谱节点表（scope = assistantId 或 __global__）；
 * 2. 新建 memory_graph_link —— 独立图谱边表（source/target 只引用 memory_graph_node.id）；
 * 3. 数据迁移：把上一版本耦合在 memoryentity 上的图谱节点(title/importance/credibility/folder_path)
 *    与 memory_link 边复制到独立图空间，保证老用户已有的图数据无缝过渡；
 *    老 legacy 记忆表数据保持不动（全量注入链路继续使用）。
 *
 * 迁移约束（与 Migration_35_36 同款经验）：
 * - 建表语句与 Room 实体生成结构逐列一致（含 NOT NULL/DEFAULT），避免 TableInfo 校验崩库；
 * - 实体 @Entity 声明的索引必须与建表 SQL 一致；
 * - 所有新列均带默认值，兼容老库空数据。
 */
val Migration_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 独立图谱节点表（建表不带 DEFAULT，与 Room 实体生成结构逐列一致，避免 TableInfo 校验崩库）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_graph_node (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                scope TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                importance REAL NOT NULL,
                credibility REAL NOT NULL,
                folder_path TEXT,
                source_conversation_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_node_scope ON memory_graph_node (scope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_node_scope_title ON memory_graph_node (scope, title)")

        // 2. 独立图谱边表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_graph_link (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                scope TEXT NOT NULL,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                weight REAL NOT NULL,
                description TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_link_scope ON memory_graph_link (scope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_link_source_id ON memory_graph_link (source_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_link_target_id ON memory_graph_link (target_id)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_graph_link_scope_source_target_type " +
                "ON memory_graph_link (scope, source_id, target_id, type)"
        )

        // 4. 兼容：v36 旧库候选表可能缺 processing_at / retry_count 列（v35→v36 建表时无这两列），
        //    这里兜底补列；v35 库经 Migration_35_36 已补过则跳过。
        runCatching { db.execSQL("ALTER TABLE memory_auto_save_candidate ADD COLUMN processing_at INTEGER") }
        runCatching { db.execSQL("ALTER TABLE memory_auto_save_candidate ADD COLUMN retry_count INTEGER") }

        // 5. 数据迁移：旧耦合图谱节点/边 → 独立图空间（老 legacy 记忆表原样保留）
        runCatching {
            db.execSQL(
                """
                INSERT INTO memory_graph_node (scope, title, content, importance, credibility, folder_path, created_at, updated_at)
                SELECT assistant_id,
                       COALESCE(title, substr(content, 1, instr(content || char(10), char(10)) - 1)),
                       content,
                       COALESCE(importance, 0.5),
                       COALESCE(credibility, 0.5),
                       folder_path,
                       COALESCE(strftime('%s','now'), 0) * 1000,
                       COALESCE(strftime('%s','now'), 0) * 1000
                FROM memoryentity
                WHERE title IS NOT NULL OR importance IS NOT NULL OR credibility IS NOT NULL OR folder_path IS NOT NULL
                """.trimIndent()
            )
            // 边迁移：新旧节点 id 通过 (scope, title) 关联
            db.execSQL(
                """
                INSERT INTO memory_graph_link (scope, source_id, target_id, type, weight, description, created_at, updated_at)
                SELECT l.scope,
                       ns.id,
                       nt.id,
                       l.type,
                       l.weight,
                       l.description,
                       COALESCE(l.created_at, strftime('%s','now') * 1000),
                       COALESCE(l.created_at, strftime('%s','now') * 1000)
                FROM memory_link l
                JOIN memory_graph_node ns ON ns.scope = l.scope AND ns.title = COALESCE((SELECT title FROM memoryentity WHERE id = l.source_id), '')
                JOIN memory_graph_node nt ON nt.scope = l.scope AND nt.title = COALESCE((SELECT title FROM memoryentity WHERE id = l.target_id), '')
                """.trimIndent()
            )
        }
    }
}
