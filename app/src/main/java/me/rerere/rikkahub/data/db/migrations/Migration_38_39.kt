package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * agent 会话编排表（v38 → v39，方案 2026-08-07「对话即 Agent」Step 1）。
 *
 * 纯新增一张表，不动任何已有表/列：agent 会话本体就是普通 Conversation，
 * 这里只落"父子关系 / 深度 / 状态 / 预算 / 执行快照"。
 *
 * 迁移约束（沿用 36_37 / 37_38 的既有经验）：
 * - 建表语句与 Room 实体生成结构逐列一致（NOT NULL、无 DEFAULT），避免 TableInfo 校验崩库；
 * - @Entity 声明的索引必须与建表 SQL 完全一致。
 */
val Migration_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_session (
                child_id TEXT PRIMARY KEY NOT NULL,
                parent_id TEXT NOT NULL,
                root_id TEXT NOT NULL,
                template_id TEXT NOT NULL,
                depth INTEGER NOT NULL,
                status TEXT NOT NULL,
                task_brief TEXT NOT NULL,
                report_mode TEXT NOT NULL,
                peers TEXT NOT NULL,
                turns_with_parent INTEGER NOT NULL,
                total_tokens INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                finished_at INTEGER,
                last_summary TEXT NOT NULL,
                profile_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_session_parent_id ON agent_session (parent_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_session_root_id ON agent_session (root_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_session_status ON agent_session (status)")
    }
}
