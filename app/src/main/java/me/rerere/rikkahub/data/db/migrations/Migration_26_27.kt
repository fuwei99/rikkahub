package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v26 -> v27：新增云锚点同步的两张本地账簿表。
 * 注意：仓库未出库 26.json schema（见 app/schemas 目录，最新只到 25.json），
 * 因此本迁移必须手写，不能使用 AutoMigration。
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_outbox` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `kind` TEXT NOT NULL,
                `ref_key` TEXT NOT NULL,
                `op` TEXT NOT NULL,
                `base_updated_at` INTEGER NOT NULL,
                `created_at` INTEGER NOT NULL,
                `retry_count` INTEGER NOT NULL,
                `last_error` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_kind` ON `sync_outbox` (`kind`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_created_at` ON `sync_outbox` (`created_at`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_state` (
                `key` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`key`)
            )
            """.trimIndent()
        )
    }
}
