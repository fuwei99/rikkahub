package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * sync_outbox 退避字段（v40 → v41）。
 *
 * 修 2026-08-08 故障：`retry_count` 只增不减，且 `pending()` 用
 * `WHERE retry_count < 5` 过滤，导致「一次离线连续几轮 flush」就能把待推会话
 * 永久踢出推送队列，网络恢复也不自愈（现场抓到 UnknownHostException 与
 * CancellationException 各一条，均非数据问题）。
 *
 * 新增两列，对齐 media_upload_outbox 的成熟做法：
 * - transient_attempt：瞬时失败连续次数，只驱动退避时长
 * - next_attempt_at：下次可尝试时间，退避期内不参与 pending
 *
 * 顺带把历史遗留的隔离项一次性复活：它们绝大多数是被瞬时错误误判的，
 * 升级后应当自动重新入队，而不是继续要求用户手动改会话来解救。
 */
val Migration_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_outbox ADD COLUMN transient_attempt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sync_outbox ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
        // 历史误判平反：清零重试计数，让升级后的第一次 flush 就能带上它们
        db.execSQL("UPDATE sync_outbox SET retry_count = 0, transient_attempt = 0, next_attempt_at = 0")
    }
}
