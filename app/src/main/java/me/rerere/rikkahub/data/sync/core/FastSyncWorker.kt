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
 * 快同步 · 定期拉取（2026-09-19）。
 *
 * ## 它拉什么
 *
 * 两条走 Worker + R2 的快通道，共用一轮 5 分钟：
 * 1. **监督锁事件日志**（`sup/`）—— 锁要的是「一端锁上、另一端立刻看见」
 * 2. **定时通知**（`sched/`）—— 原先挂 D1，纯属给写额度雪上加霜
 *
 * ## 只拉不推
 *
 * 推是**事件驱动**的：
 * - 监督事件产生即推，见 [me.rerere.rikkahub.data.datastore.SettingsStore.appendSupervisionEvent]
 * - 定时通知增删改即推，见 [me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager]
 *
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
 * 就错过了窗口。这两条快通道不挑时刻，5 分钟没拉成、下一轮拉也一样 ——
 * 链断了由 App 下次启动时的 [start] 接上即可。
 */
class FastSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val supervisionSyncClient: SupervisionSyncClient,
    private val scheduledNotificationSyncClient: ScheduledNotificationSyncClient,
    private val configStore: SyncAdvancedConfigStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!configStore.current.isQuickSyncUsable) {
            Log.i(TAG, "quick sync off/unconfigured, skip this round")
            return Result.success()
        }

        // 两条互不依赖，各自炸了不影响另一条
        runCatching { supervisionSyncClient.pullAndMerge(applicationContext) }
            .onFailure { Log.w(TAG, "supervision pull failed", it) }
        runCatching { scheduledNotificationSyncClient.pullAndMerge(applicationContext) }
            .onFailure { Log.w(TAG, "scheduled notification pull failed", it) }

        scheduleNext()
        return Result.success()
    }

    /** 跑完把自己按固定间隔重新排上，维持自续链 */
    private fun scheduleNext() {
        enqueue(applicationContext, PULL_INTERVAL_MS, ExistingWorkPolicy.REPLACE)
    }

    companion object {
        private const val TAG = "FastSyncWorker"

        /** 自续链的唯一名 */
        private const val CHAIN_NAME = "fast_sync_chain"

        /** 拉取间隔：需求定的 5 分钟 */
        private const val PULL_INTERVAL_MS = 5 * 60 * 1000L

        /** App 启动时调用：把链拉起来（第一轮立刻跑） */
        fun start(context: Context) {
            enqueue(context, delayMillis = 0L, ExistingWorkPolicy.REPLACE)
        }

        /** 配置变更后调用：立即重排 */
        fun reschedule(context: Context) {
            enqueue(context, delayMillis = 0L, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueue(context: Context, delayMillis: Long, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<FastSyncWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN_NAME, policy, request)
        }
    }
}
