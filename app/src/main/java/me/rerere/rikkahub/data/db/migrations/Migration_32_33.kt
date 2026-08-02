package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ConversationEntity 增加 model_id（对话级模型绑定）。
 *
 * 防御性处理：上一版本代码曾在未 bump @Database version 的情况下引入 model_id，
 * 少数设备的库里可能已经存在该列（或由 createAllTables 直接建出来），
 * 所以这里先检查列是否存在，避免 duplicate column 直接崩库。
 */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("ConversationEntity", "model_id")) {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN model_id TEXT NOT NULL DEFAULT ''")
        }
    }
}

internal fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}
