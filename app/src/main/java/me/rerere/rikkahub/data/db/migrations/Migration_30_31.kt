package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_upload_outbox (
                asset_id TEXT NOT NULL PRIMARY KEY,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                next_attempt_at INTEGER NOT NULL,
                retry_count INTEGER NOT NULL,
                last_error TEXT
            )
            """.trimIndent()
        )
    }
}
