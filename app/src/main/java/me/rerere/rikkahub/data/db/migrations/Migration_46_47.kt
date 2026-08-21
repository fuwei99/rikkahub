package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * MCP server 挂载下沉对话级（v46 → v47，2026-08-21）。
 *
 * 背景：`assistant.mcpServers` 是助手级 Set<Uuid>，UI 上那排开关一动就作用于该助手的
 * **所有对话**（用户原话：「为啥现在 mcp 的开启关闭不是随着对话，而是全局的」）。
 * 新增 `mcp_servers` 列承载对话级覆盖，助手上那份退化为「新对话默认值」，
 * 与 enabled_skills / local_tools / mcp_tools 完全同一套三态约定：
 *   ''   = 未设置，继承 assistant.mcpServers
 *   '[]' = 本对话明确一个都不挂
 *   非空 = 显式挂载集合
 *
 * `NOT NULL DEFAULT ''` 必须与 Entity 的 `defaultValue = ""` 逐字一致，否则 Room 的
 * TableInfo 校验会在下次打开时崩库。老行天然是空串 → 行为与升级前逐字等价。
 *
 * hasColumn 判断沿用 Migration_45_46 的理由：本仓库历史上出过「改 Entity 没 bump version」，
 * 部分设备的库可能已被 createAllTables 建出该列，无脑 ALTER 会 duplicate column 崩溃。
 */
val Migration_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("ConversationEntity", "mcp_servers")) {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN mcp_servers TEXT NOT NULL DEFAULT ''")
        }
        Log.i("Migration_46_47", "ConversationEntity.mcp_servers added")
    }
}
