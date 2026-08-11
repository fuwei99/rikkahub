package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 屏幕时间小时粒度（v44 → v45，2026-08-11 bug 修复 BUG-3）：
 * screen_time_day 增 hourly_json 列（24 个数字的 JSON 数组，本地时区 hour-of-day 分布）。
 *
 * 老行没有小时数据 → NOT NULL DEFAULT ''，与 ScreenTimeDayEntity 的
 * `@ColumnInfo("hourly_json", defaultValue = "")` 逐字一致，避免 TableInfo 校验崩库。
 */
val Migration_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE screen_time_day ADD COLUMN hourly_json TEXT NOT NULL DEFAULT ''")
        Log.i("Migration_44_45", "screen_time_day.hourly_json added")
    }
}
