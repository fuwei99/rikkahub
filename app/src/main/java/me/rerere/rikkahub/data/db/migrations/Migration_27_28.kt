package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v27 -> v28：媒体资产云化（P3）——两张注册表补 R2 归属列，均为可空列，存量行无感。
 *
 * - genmediaentity：+ r2_key / r2_acct（镜像完成后回填）、+ original_url（渠道原 URL，
 *   过期后由 Coil 侧 r2 引用兜底）
 * - managed_files：+ r2_key / r2_acct（聊天附件上云后回填）
 *
 * 仓库缺 26/27.json，遵循手写迁移惯例（同 Migration_26_27）。
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `genmediaentity` ADD COLUMN `r2_key` TEXT")
        db.execSQL("ALTER TABLE `genmediaentity` ADD COLUMN `r2_acct` TEXT")
        db.execSQL("ALTER TABLE `genmediaentity` ADD COLUMN `original_url` TEXT")
        db.execSQL("ALTER TABLE `managed_files` ADD COLUMN `r2_key` TEXT")
        db.execSQL("ALTER TABLE `managed_files` ADD COLUMN `r2_acct` TEXT")
    }
}
