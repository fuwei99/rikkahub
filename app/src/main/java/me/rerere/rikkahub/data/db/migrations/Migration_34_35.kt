package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记忆图 Phase 1（v34 → v35）：
 * 新建 memory_link 表（有向加权边 + 时间列预埋）。
 *
 * 纯增量：不动存量表、不加非空列，对现有数据零破坏。
 * 用 CREATE TABLE IF NOT EXISTS 防御：本仓库历史上出现过
 * 「表结构已提前长出但版本号没 bump」的情况（见 Migration_32_33 注释）。
 *
 * ⚠️ 建表语句必须与 Room 实体生成的结构逐列一致（含 NOT NULL / 无 DEFAULT）：
 * Room 打开库时会做 TableInfo 完整性校验，多余的 DEFAULT 会触发
 * "Migration didn't properly handle" 崩溃。
 */
val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_link (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                type TEXT NOT NULL,
                weight REAL NOT NULL,
                description TEXT NOT NULL,
                scope TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                valid_from INTEGER,
                valid_until INTEGER,
                superseded_by_id INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_link_scope ON memory_link (scope)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_link_source_id ON memory_link (source_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_link_target_id ON memory_link (target_id)")
    }
}
