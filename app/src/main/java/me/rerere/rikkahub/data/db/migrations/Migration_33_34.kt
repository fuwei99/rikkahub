package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 相册 Tag 体系（S2）：
 * 1. managed_files 增加 content_sha256 / name_zh / name_en 三列。
 * 2. 新建 asset_label_ref 关联表（一图多分类 + 一图多标签）。
 *
 * 三列全部可空、无默认值，纯 ADD COLUMN，不动存量行。
 * hasColumn 判断沿用 Migration_32_33 里那套防御逻辑：这个库历史上出现过
 * 「加了列但没 bump version」的情况，部分设备的表结构会提前长出目标列，
 * 直接 ALTER 会 duplicate column 崩库。
 */
val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("content_sha256", "name_zh", "name_en").forEach { column ->
            if (!db.hasColumn("managed_files", column)) {
                db.execSQL("ALTER TABLE managed_files ADD COLUMN $column TEXT")
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_content_sha256 ON managed_files (content_sha256)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS asset_label_ref (
                asset_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                value TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (asset_id, kind, value),
                FOREIGN KEY (asset_id) REFERENCES managed_files(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_label_ref_asset_id ON asset_label_ref (asset_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_asset_label_ref_kind_value ON asset_label_ref (kind, value)")
    }
}
