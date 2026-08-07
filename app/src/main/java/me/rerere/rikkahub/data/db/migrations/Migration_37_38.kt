package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 记忆图注册表（v37 → v38，方案 2026-08-07「记忆图多图体系与动态挂载」阶段一）：
 *
 * 1. 新建 memory_graph —— 图注册表，id 就是节点表已有的 scope，**不搬一条节点数据**；
 * 2. 回填两类内置图：全局图（`__global__`）+ 每个已出现过的 assistant scope；
 * 3. ConversationEntity 增列 memory_graph_bindings（对话级绑定，''=继承助手配置）。
 *
 * 崩库防御（review2 §一.1 —— 这条是阻塞级）：
 * - slug 上有 UNIQUE 索引，`'assistant_'||substr(scope,1,8)` 这种截断写法一旦两个 uuid
 *   前 8 位相同就 INSERT 失败 → 整个 migration 抛异常 → 老用户升级即崩且无法回滚。
 *   故迁移期 slug **直接写 scope 全值**（天然唯一），可读 slug 交给应用层
 *   MemoryGraphRegistry.ensureAssistantGraph() 懒规范化；
 * - 所有 INSERT 用 OR IGNORE，所有数据迁移套 runCatching（Migration_36_37 的既有姿势）；
 * - 建表语句与 Room 实体生成结构逐列一致（含 NOT NULL / 无 DEFAULT），避免 TableInfo 校验崩库。
 *
 * autoExtractTarget 回填规则：ASSISTANT 图 = 1，GLOBAL 图 = 0。
 * 现状 MemoryGraphExtractor 恒定写 assistant.id，这样回填后行为对老配置逐字等价，
 * 不会出现「开了 allowEditGlobalGraph 就把提炼目标静默翻到全局图」的静默数据改写。
 */
val Migration_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 注册表建表（逐列与 MemoryGraphEntity 生成结构一致）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_graph (
                id TEXT PRIMARY KEY NOT NULL,
                slug TEXT NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                kind TEXT NOT NULL,
                bound_assistant_id TEXT,
                emoji TEXT,
                builtin INTEGER NOT NULL,
                created_by TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                auto_extract_target INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memory_graph_slug ON memory_graph (slug)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_graph_kind ON memory_graph (kind)")

        // 2. 对话级绑定列：'' = 未设置（继承助手），'[]' = 明确关闭全部图，非空数组 = 显式绑定。
        //    nullable 语义靠空串区分，避免 lorebook 那套「开了对话覆盖开关就瞬间清空绑定」的陷阱。
        runCatching {
            db.execSQL("ALTER TABLE ConversationEntity ADD COLUMN memory_graph_bindings TEXT NOT NULL DEFAULT ''")
        }.onFailure {
            Log.w(TAG, "add ConversationEntity.memory_graph_bindings failed (may already exist)", it)
        }

        // 3. 回填内置图
        runCatching {
            val now = System.currentTimeMillis()
            // 全局图
            db.execSQL(
                """
                INSERT OR IGNORE INTO memory_graph
                    (id, slug, name, description, kind, bound_assistant_id, emoji, builtin,
                     created_by, sort_order, auto_extract_target, created_at, updated_at)
                VALUES ('__global__', 'global', '全局记忆图', '跨助手共享的全局记忆图', 'GLOBAL', NULL, NULL, 1,
                        'USER', 0, 0, $now, $now)
                """.trimIndent()
            )
            // 每个已有 assistant scope 一条（从节点表反查，不依赖 prefs；slug = scope 保证唯一）
            db.execSQL(
                """
                INSERT OR IGNORE INTO memory_graph
                    (id, slug, name, description, kind, bound_assistant_id, emoji, builtin,
                     created_by, sort_order, auto_extract_target, created_at, updated_at)
                SELECT DISTINCT scope, scope, '助手记忆图', '该助手专属的记忆图', 'ASSISTANT', scope, NULL, 1,
                       'USER', 0, 1, $now, $now
                FROM memory_graph_node
                WHERE scope <> '__global__'
                """.trimIndent()
            )
            Log.i(TAG, "memory_graph registry backfilled: rows=${countRows(db, "memory_graph")}")
        }.onFailure {
            // 注册表为空也能跑：ensureAssistantGraph / ensureGlobalGraph 会在首次使用时懒补
            Log.e(TAG, "Failed to backfill memory_graph registry; registry will be lazily rebuilt", it)
        }
    }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
}

private const val TAG = "Migration_37_38"
