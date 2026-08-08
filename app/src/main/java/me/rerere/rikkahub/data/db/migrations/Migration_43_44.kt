package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 跨设备屏幕时间（v43 → v44，方案 2026-08-09）：
 * 新增 screen_time_day 表（按天 × 按 App 日聚合）。
 * 建表列与 ScreenTimeDayEntity 逐列一致（含 NOT NULL），唯一索引与实体声明一致，
 * 避免 TableInfo 校验崩库（沿用 Migration_41_42 同款经验）。
 */
val Migration_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS screen_time_day (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                device_id TEXT NOT NULL,
                device_label TEXT NOT NULL,
                date TEXT NOT NULL,
                total_ms INTEGER NOT NULL,
                apps_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_screen_time_day_device_id_date ON screen_time_day (device_id, date)"
        )
        Log.i("Migration_43_44", "screen_time_day table created")
    }
}
