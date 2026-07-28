package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** ON_STOP 触发的兜底同步任务：进程被杀后由 WorkManager 完成推/拉 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!engine.isConfigured()) return Result.success()
        return runCatching { engine.syncOnce() }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.e(TAG, "doWork failed", it)
                if (runAttemptCount < 4) Result.retry() else Result.success()
            }
        )
    }

    companion object {
        private const val TAG = "AutoSyncWorker"
        private const val UNIQUE_NAME = "rikkahub_auto_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
