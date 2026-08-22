package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * workspace 挂载从助手级下沉到对话级（v48 → v49，2026-08-22）。
 *
 * 之前 `Assistant.workspaceId` 是全局的：在任意对话里改挂载，该助手所有对话一起被改。
 * 现在新增 `ConversationEntity.workspace_id TEXT NOT NULL DEFAULT ''`：
 *   ''   = 未设置，继承 assistant.workspaceId（升级后逐字等价于旧行为）
 *   非空 = 该对话显式绑定的 workspaceUuid，与助手解耦
 *
 * 升级时**不做数据搬迁**：老行天然是空串 → 行为与升级前完全一致。
 * 用户第一次在对话里切换工作区时由 UI 写入显式 Uuid，此后该对话独立。
 *
 * hasColumn 判断沿用前几条 migration 的理由：历史上出过「改 Entity 没 bump version」，
 * 部分设备可能已被 createAllTables 建出该列，无脑 ALTER 会 duplicate column 崩库。
 */
val Migration_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasColumn("ConversationEntity", "workspace_id")) {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN workspace_id TEXT NOT NULL DEFAULT ''")
        }
        Log.i("Migration_48_49", "ConversationEntity.workspace_id added")
    }
}
