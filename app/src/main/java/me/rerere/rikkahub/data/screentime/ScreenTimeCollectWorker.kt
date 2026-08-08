package me.rerere.rikkahub.data.screentime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 跨设备屏幕时间采集 Worker（方案 2026-08-09）。
 *
 * WorkManager 的 PeriodicWorkRequest 最短间隔 15 分钟，不满足「每 10 分钟采集」，
 * 故用 OneTime 链式自调度：doWork 末尾 enqueue 下一发（10 分钟后）。
 * - [start]：App 启动时调用，KEEP 策略（已有挂起链节则不重复建链）
 * - 链内续发：REPLACE（当前链节已完成，安全替换）
 *
 * WorkManager 受 Doze/电池优化影响可能延迟执行 → 10 分钟是 best-effort；
 * 数据靠「每次运行把今天 0 点至今整体重算」兜底，延迟只会晚到不会漏算。
 */
class ScreenTimeCollectWorker(
    context: Context,
    params: WorkerParameters,
    private val collector: ScreenTimeCollector,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { collector.collectToday() }
            .onFailure { Log.w(TAG, "collect failed", it) }
        enqueueNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "ScreenTimeCollectWorker"
        private const val UNIQUE_NAME = "rikkahub_screen_time_collect"
        private const val INTERVAL_MINUTES = 10L

        /** App 启动时启动采集链：立即采一发（不延迟），并保证 10 分钟链存在 */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScreenTimeCollectWorker>()
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private fun enqueueNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScreenTimeCollectWorker>()
                .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
