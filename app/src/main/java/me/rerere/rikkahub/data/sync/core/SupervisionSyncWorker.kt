package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 监督锁 · 定期拉取（2026-09-19）。
 *
 * ## 只拉不推
 *
 * 推是**事件驱动**的：任意一端产生监督事件后立即调 [SupervisionSyncClient.pushOwn]
 * （见 `SettingsStore.appendSupervisionEvent`），不靠轮询。
 * 这个 Worker 只负责**拉** —— 把对端已经有的事实收回来。
 *
 * ## 为什么是自续链而不是 PeriodicWork
 *
 * `PeriodicWorkRequest` 的最小周期是 **15 分钟**，而需求是 5 分钟。
 * 所以沿用 [me.rerere.rikkahub.data.screentime.ScreenTimeCollectWorker] 那套
 * **OneTime 自续链**：每轮跑完按固定间隔把自己用 `REPLACE` 重新排上。
 *
 * ## 为什么不用 BACKSTOP
 *
 * 屏幕时间那条链有复活兜底，是因为它要跟「配置里的时间窗」对齐，链被系统掐了
 * 就错过了窗口。监督锁不挑时刻，5 分钟没拉成、下一轮拉也一样 —— 链断了由
 * App 下次启动时的 [start] 接上即可，不值得为它再挂一个周期任务。
 */
class SupervisionSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val syncClient: SupervisionSyncClient,
    private val configStore: SyncAdvancedConfigStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!configStore.current.isQuickSyncUsable) {
            Log.i(TAG, "quick sync off/unconfigured, skip this round")
            return Result.success()
        }

        runCatching { syncClient.pullAndMerge(applicationContext) }
            .onFailure { Log.w(TAG, "pullAndMerge failed", it) }

        scheduleNext()
        return Result.success()
    }

    /** 跑完把自己按固定间隔重新排上，维持自续链 */
    private fun scheduleNext() {
        enqueue(applicationContext, PULL_INTERVAL_MS, ExistingWorkPolicy.REPLACE)
    }

    companion object {
        private const val TAG = "SupervisionSyncWorker"

        /** 自续链的唯一名 */
        private const val CHAIN_NAME = "supervision_sync_chain"

        /** 拉取间隔：需求定的 5 分钟 */
        private const val PULL_INTERVAL_MS = 5 * 60 * 1000L

        /** App 启动时调用：把链拉起来（第一轮立刻跑） */
        fun start(context: Context) {
            enqueue(context, delayMillis = 0L, ExistingWorkPolicy.REPLACE)
        }

        /** 配置变更后调用：按新配置立即重排（当前无配置项，保留入口） */
        fun reschedule(context: Context) {
            enqueue(context, delayMillis = 0L, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueue(context: Context, delayMillis: Long, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<SupervisionSyncWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN_NAME, policy, request)
        }
    }
}
