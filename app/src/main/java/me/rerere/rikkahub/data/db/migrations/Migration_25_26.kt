package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            ALTER TABLE workspaces
            ADD COLUMN external_mounts TEXT NOT NULL DEFAULT '[]'
            """.trimIndent()
        )
    }
}
