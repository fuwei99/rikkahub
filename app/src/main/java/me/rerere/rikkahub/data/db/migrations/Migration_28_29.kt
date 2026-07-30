package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Asset index refactor.
 *
 * User explicitly does not need old attachment compatibility. Recreate managed_files with
 * UUID primary keys and reserved asset metadata fields; old managed file rows are discarded.
 * Conversations themselves are untouched, so messages still load. Old non-asset attachment
 * URLs are handled by the resolver/UI as unavailable.
 */
val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS managed_files")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS managed_files (
                id TEXT NOT NULL PRIMARY KEY,
                folder TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                r2_key TEXT,
                r2_acct TEXT,
                external_url TEXT,
                sha256 TEXT,
                prompt TEXT,
                description TEXT,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_managed_files_relative_path ON managed_files(relative_path)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_folder ON managed_files(folder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_r2_key_r2_acct ON managed_files(r2_key, r2_acct)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_external_url ON managed_files(external_url)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_managed_files_sha256 ON managed_files(sha256)")
    }
}
