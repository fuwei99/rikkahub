package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 每日 SQLite 一致性快照 Worker（P4）。
 *
 * 利用 DatabaseSnapshotHelper (wal_checkpoint + VACUUM INTO) 生成干净无 -wal/-shm 的 DB 快照，
 * 并上推至 R2 snapshots/daily/backup_YYYYMMDD.db。
 */
class SnapshotWorker(
    context: Context,
    params: WorkerParameters,
    private val database: AppDatabase,
    private val r2MediaStore: R2MediaStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!r2MediaStore.isConfigured()) return Result.success()

        val dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val tempFile = File(applicationContext.cacheDir, "snapshot_daily_$dateStr.db")
        return try {
            if (!DatabaseSnapshotHelper.createSnapshot(applicationContext, database, tempFile)) {
                Log.w(TAG, "Snapshot creation failed")
                return Result.failure()
            }
            val bytes = tempFile.readBytes()
            val r2Key = "snapshots/daily/backup_$dateStr.db"
            r2MediaStore.uploadWithKey(r2Key, bytes, "application/x-sqlite3").getOrThrow()
            Log.i(TAG, "Snapshot uploaded to R2: $r2Key (${bytes.size}B)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SnapshotWorker failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    companion object {
        private const val TAG = "SnapshotWorker"
        private const val UNIQUE_NAME = "rikkahub_daily_snapshot"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SnapshotWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
