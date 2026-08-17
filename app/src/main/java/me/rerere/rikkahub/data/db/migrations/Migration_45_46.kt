package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 对话级能力覆盖（v45 → v46，2026-08-18 重构）：
 * ConversationEntity 增 7 列，让「思维链 / 联网 / skills / 本地工具 / 工作区工具 /
 * MCP 工具 / 记忆选项」从**助手级持久**下沉为**对话级持久**。
 *
 * 全部 `NOT NULL DEFAULT ''`，与 Entity 上的 `defaultValue = ""` 逐字一致，
 * 否则 Room 的 TableInfo 校验会在下次打开时崩库。
 * 空串 = 未设置（继承助手默认），老行天然全是空串 → 行为与升级前完全一致。
 *
 * 逐列 hasColumn 判断：本仓库历史上出现过「改了 Entity 没 bump version」的情况
 * （见 Migration_32_33 注释），部分设备的库里可能已被 createAllTables 建出这些列，
 * 无脑 ALTER 会 duplicate column 直接崩。
 */
val Migration_45_46 = object : Migration(45, 46) {
    private val columns = listOf(
        "reasoning_level",
        "enable_web_search",
        "enabled_skills",
        "local_tools",
        "workspace_tools",
        "mcp_tools",
        "memory_options",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        columns.forEach { column ->
            if (!db.hasColumn("ConversationEntity", column)) {
                db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
            }
        }
        Log.i("Migration_45_46", "ConversationEntity per-conversation capability columns added")
    }
}
