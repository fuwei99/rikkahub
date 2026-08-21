package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 自动压缩对话级覆盖补持久化（v47 → v48，2026-08-21）。
 *
 * 背景：`Conversation.autoCompressOverride` 自 2026-08-08 引入起就是个**纯内存字段** ——
 * ConversationEntity 无列、Repository 不做双向映射，UI 又走只改内存的 updateConversationState。
 * 结果是用户在聊天面板拨开「自动压缩」，切走对话/重启 app 后配置静默消失；
 * 而 assistants.json 里助手默认 `autoCompress.enabled=false`，
 * `mergeOverride(null)` 原样返回默认 → `maybeAutoCompress` 第一行就 return，自动压缩**从未触发过**。
 *
 * 三态约定与 memory_options / mcp_servers 完全一致：
 *   ''   = 未设置，继承 assistant.autoCompress
 *   非空 = 本对话显式覆盖（AutoCompressOverride JSON）
 * 注意这里没有 '[]' 这种「明确全关」的中间态：AutoCompressOverride 自身
 * 每个字段都可为 null，`isEmpty` 为真时调用方会把整个 override 置 null → 落库即空串。
 *
 * `NOT NULL DEFAULT ''` 必须与 Entity 的 `defaultValue = ""` 逐字一致，否则 Room 的
 * TableInfo 校验会在下次打开时崩库。老行天然是空串 → 行为与升级前逐字等价。
 *
 * hasColumn 判断沿用 Migration_45_46/46_47 的理由：本仓库历史上出过「改 Entity 没 bump version」，
 * 部分设备的库可能已被 createAllTables 建出该列，无脑 ALTER 会 duplicate column 崩溃。
 */
val Migration_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("ConversationEntity", "auto_compress_override")) {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN auto_compress_override TEXT NOT NULL DEFAULT ''")
        }
        Log.i("Migration_47_48", "ConversationEntity.auto_compress_override added")
    }
}
