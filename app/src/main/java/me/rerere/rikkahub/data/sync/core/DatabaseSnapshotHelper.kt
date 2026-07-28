package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File

private const val TAG = "DatabaseSnapshotHelper"

/**
 * 数据库一致性快照（P4）。
 *
 * 在 WAL 模式下：先执行 PRAGMA wal_checkpoint(TRUNCATE) 冲洗日志，再使用 VACUUM INTO
 * 生成包含全量已提交数据的单文件独立 SQLite DB。抛弃旧的 -wal / -shm 裸拷逻辑，防止恢复损坏。
 */
object DatabaseSnapshotHelper {

    fun createSnapshot(context: Context, database: AppDatabase, outputFile: File): Boolean {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val ok = runCatching {
            val db = database.openHelper.writableDatabase
            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            db.execSQL("VACUUM INTO '${outputFile.absolutePath}'")
            // 擦除机密表（workspaces 属于 R4 本地机密，绝不上云）
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    outputFile.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                ).use { tempDb ->
                    tempDb.execSQL("DELETE FROM workspaces")
                }
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "VACUUM INTO failed, fallback to checkpoint + file copy", e)
            runCatching {
                val dbFile = context.getDatabasePath("rikka_hub")
                if (dbFile.exists()) {
                    database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                    dbFile.copyTo(outputFile, overwrite = true)
                    runCatching {
                        android.database.sqlite.SQLiteDatabase.openDatabase(
                            outputFile.absolutePath,
                            null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                        ).use { tempDb ->
                            tempDb.execSQL("DELETE FROM workspaces")
                        }
                    }
                    true
                } else false
            }.getOrDefault(false)
        }
        return ok && outputFile.exists() && outputFile.length() > 0
    }
}
