package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `Settings` 字段级因果版本表（v49 → v50，方案 2026-08-23 大统一重构 §2.3）。
 *
 * 新增 `sync_field_version(field PK, hlc, sha)`，用来终结「设置跨设备互相覆盖」：
 * 有了字段级 HLC，合并才能从「整包取云端骨架」改成「逐字段挑胜者」。
 *
 * ## 建表即空表，这是刻意的
 *
 * 升级后本表为空 = 全字段 `hlc == 0` = **unknown**（既不赢也不输，§2.5）。
 * 首次 pull 会全量采纳云端 —— 云上才是用户的真实配置；此后用户第一次改动
 * 某字段才给它打戳。
 *
 * **千万不要在这条 migration 里给当前 Settings 全字段打 `now()`**：
 * 两台设备升级时间差几分钟，晚升级那台的全部默认值（`Uuid.random()` 生成的
 * 各种 modelId、空 prompt）会带着更大的 HLC 上云，把先升级那台的真实配置
 * 全刷成默认值 —— 这就是「切换那天两台设备设置全变默认」的剧本。
 *
 * 建表列与 [me.rerere.rikkahub.data.db.entity.SyncFieldVersionEntity] 逐列一致
 * （含 NOT NULL 与 PK），避免 TableInfo 校验崩库（沿用 Migration_41_42 / 43_44 的经验）。
 */
val Migration_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_field_version (
                field TEXT NOT NULL,
                hlc INTEGER NOT NULL,
                sha TEXT NOT NULL,
                PRIMARY KEY(field)
            )
            """.trimIndent()
        )
        Log.i("Migration_49_50", "sync_field_version table created")
    }
}
