package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记忆图 Phase 3（v35 → v36）：
 * 1. memoryentity 补结构化列（title/importance/credibility/folder_path/history）—— 抽取器写回用；
 * 2. 新建 memory_auto_save_candidate 表 —— 自动提炼候选攒批。
 *
 * ⚠️ 三个硬约束（P1 踩过的坑）：
 * - ALTER TABLE ADD COLUMN 不允许 NOT NULL 且无 DEFAULT 的列 → 新列全部可空，实体用可空类型；
 * - Room 打开库做 TableInfo 校验：新增列的 NULL 约束必须与实体生成结构逐列一致
 *   （实体 Float?/String? = 可空列，不能声明成非空否则校验失败崩库）；
 * - 建表语句不带 DEFAULT，与 Room 实体生成结构保持一致；
 * - 建了索引的表，实体 @Entity 必须同步声明 @Index（否则 TableInfo 校验 Found 有索引 != Expected 无索引崩库）。
 */
val Migration_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 记忆节点补列（均可空）
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN title TEXT")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN importance REAL")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN credibility REAL")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN folder_path TEXT")
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN history TEXT")

        // 2. 自动提炼候选表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_auto_save_candidate (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                assistant_id TEXT NOT NULL,
                chat_id TEXT NOT NULL,
                trigger_timestamp INTEGER NOT NULL,
                source_type TEXT NOT NULL,
                status TEXT NOT NULL,
                error TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_memory_auto_save_candidate_assistant_id " +
                "ON memory_auto_save_candidate (assistant_id)"
        )
        // 候选表补列：processing 超时恢复 / 重试上限（可空列 + 代码层 ?: 兜底，规避 NOT NULL 无 DEFAULT 的 ALTER 限制）
        db.execSQL("ALTER TABLE memory_auto_save_candidate ADD COLUMN processing_at INTEGER")
        db.execSQL("ALTER TABLE memory_auto_save_candidate ADD COLUMN retry_count INTEGER")
    }
}
