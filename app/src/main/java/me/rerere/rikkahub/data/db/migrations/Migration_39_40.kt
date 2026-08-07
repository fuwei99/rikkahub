package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * agent 收件箱表（v39 → v40，方案 2026-08-07「多 Agent 通信内核」落地 plan Step 1）。
 *
 * 纯新增一张表，不动任何已有表/列。收件箱是通信内核的唯一真相源（I2）：
 * 所有跨对话事件无条件先落库再谈调度，发送方永不因目标状态阻塞（I3）。
 *
 * 迁移约束（沿用 38_39 的既有经验）：
 * - 建表语句与 Room 实体生成结构逐列一致（NOT NULL、无 DEFAULT），避免 TableInfo 校验崩库；
 * - @Entity 声明的索引必须与建表 SQL 完全一致。
 */
val Migration_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_inbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                target_id TEXT NOT NULL,
                source TEXT NOT NULL,
                urgency TEXT NOT NULL,
                kind TEXT NOT NULL,
                sender_id TEXT,
                sender_title TEXT NOT NULL,
                template_id TEXT,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                read_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_inbox_target_id ON agent_inbox (target_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_inbox_target_id_read_at ON agent_inbox (target_id, read_at)")
    }
}
