package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE GenMediaEntity ADD COLUMN original_asset_id TEXT")
        db.execSQL("ALTER TABLE GenMediaEntity ADD COLUMN preview_asset_id TEXT")
    }
}
