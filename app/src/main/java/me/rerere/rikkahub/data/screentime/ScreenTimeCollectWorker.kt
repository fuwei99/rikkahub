package me.rerere.rikkahub.data.screentime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 快速同步 · 执行链（2026-09-19）。
 *
 * 只干屏幕时间这一件事：把本机屏幕时间推到独立 Worker + R2，再把对端的拉回来
 * 写进本地 Room。**不碰 D1**，因此不受 D1 配额熔断影响。
 *
 * ## 调度
 *
 * 不用 PeriodicWork —— 它的相位会随系统漂移，且改配置要重建。改为
 * **OneTime 自续链**：每轮跑完由 [scheduleNext] 按当前配置算出下一次延迟，
 * 用 `REPLACE` 把自己重新排上。改配置后调 [reschedule] 立刻生效。
 *
 * 具体节奏由 [QuickSyncScheduler] 从 [SyncAdvancedConfig] 的调度字段翻译而来，
 * 本类不写死任何时刻。
 *
 * ## 每轮三步，顺序固定
 *
 * 1. [ScreenTimeCollector.collectRecent] —— 先采集，保证推出去的是刚查过的
 * 2. [ScreenTimeSyncClient.pushOwn] —— 推本机
 * 3. [ScreenTimeSyncClient.pullAndMerge] —— 拉对端并 LWW 写回 Room
 *
 * 查询侧（`ScreenTimeTool`）零改动，照旧读本地 Room。
 */
class ScreenTimeCollectWorker(
    context: Context,
    params: WorkerParameters,
    private val collector: ScreenTimeCollector,
    private val syncClient: ScreenTimeSyncClient,
    private val configStore: SyncAdvancedConfigStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cfg = configStore.current
        if (!cfg.isQuickSyncUsable) {
            Log.i(TAG, "quick sync off/unconfigured, skip this round")
            return Result.success()
        }

        // 三步顺序固定；任何一步炸了都不影响后两步和后续排期
        runCatching { collector.collectRecent() }
            .onFailure { Log.w(TAG, "collectRecent failed", it) }
        runCatching { syncClient.pushOwn(applicationContext) }
            .onFailure { Log.w(TAG, "pushOwn failed", it) }
        runCatching { syncClient.pullAndMerge(applicationContext) }
            .onFailure { Log.w(TAG, "pullAndMerge failed", it) }

        scheduleNext()
        return Result.success()
    }

    /** 跑完把自己按当前配置重新排上，维持自续链 */
    private fun scheduleNext() {
        enqueueChain(applicationContext, nextDelayMillis(configStore.current), ExistingWorkPolicy.REPLACE)
    }

    companion object {
        private const val TAG = "ScreenTimeCollectWorker"

        /** 自续链的唯一名；[reschedule] / [runNow] 都靠它去重 */
        private const val CHAIN_NAME = "screen_time_collect_chain"

        /** 复活兜底的唯一名 */
        private const val BACKSTOP_NAME = "screen_time_collect_backstop"

        /** 兜底周期（分钟）。华为 EMUI 冻结后台可能掐断自续链，靠它把链重新接上 */
        private const val BACKSTOP_INTERVAL_MINUTES = 15L

        /** 配置非法（时间写错等）时的兜底间隔，别让链空转 */
        private const val FALLBACK_INTERVAL_MINUTES = 30L

        /**
         * 距下一次触发还有多久（毫秒）。
         *
         * 全部由配置决定，见 [QuickSyncScheduler]；配置非法时退回
         * [FALLBACK_INTERVAL_MINUTES]，绝不算出 0 或负数。
         */
        fun nextDelayMillis(cfg: SyncAdvancedConfig): Long {
            val fallback = TimeUnit.MINUTES.toMillis(FALLBACK_INTERVAL_MINUTES)
            if (!cfg.isQuickSyncUsable) return fallback
            return QuickSyncScheduler.millisUntilNextTrigger(
                now = LocalDateTime.now(),
                mode = cfg.quickSyncScheduleMode,
                windowStart = cfg.quickSyncWindowStart,
                windowEnd = cfg.quickSyncWindowEnd,
                intervalMinutes = cfg.quickSyncIntervalMinutes,
                fixedTimes = cfg.quickSyncFixedTimes,
            ) ?: fallback
        }

        /** App 启动时调用：拉起自续链 + 挂复活兜底 */
        fun start(context: Context) {
            enqueueBackstop(context)
            enqueueChain(context, delayMillis = 0L, ExistingWorkPolicy.REPLACE)
        }

        /** 用户改完配置后调用：按新配置重排下一次 */
        fun reschedule(context: Context, cfg: SyncAdvancedConfig) {
            enqueueBackstop(context)
            enqueueChain(context, nextDelayMillis(cfg), ExistingWorkPolicy.REPLACE)
        }

        /** 「立即同步一次」：马上跑一轮，跑完自动按配置排下一次 */
        fun runNow(context: Context) {
            enqueueChain(context, delayMillis = 0L, ExistingWorkPolicy.REPLACE)
        }

        /** 只负责把链重新接上；链还活着就什么都不做（KEEP） */
        internal fun reviveChainIfDead(context: Context, cfg: SyncAdvancedConfig) {
            enqueueChain(context, nextDelayMillis(cfg), ExistingWorkPolicy.KEEP)
        }

        private fun enqueueChain(context: Context, delayMillis: Long, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<ScreenTimeCollectWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CHAIN_NAME, policy, request)
        }

        private fun enqueueBackstop(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScreenTimeCollectWorker>(
                BACKSTOP_INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(BACKSTOP_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/**
 * 快速同步 · 复活兜底（2026-09-19）。
 *
 * **不联网、不采集**，唯一职责是：如果自续链被系统掐死了，把它重新接上。
 *
 * 为什么单独一个类而不是让 [ScreenTimeCollectWorker] 兼职：周期任务无法与
 * 配置里的时间窗对齐，兼职就变成「半夜也爬起来联网」。分开之后，兜底每 15 分钟
 * 醒一次只做 `KEEP` 判断 —— 链还活着就零成本返回，链死了才按配置重排。
 */
class QuickSyncBackstopWorker(
    context: Context,
    params: WorkerParameters,
    private val configStore: SyncAdvancedConfigStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cfg = configStore.current
        if (!cfg.isQuickSyncUsable) return Result.success()
        ScreenTimeCollectWorker.reviveChainIfDead(applicationContext, cfg)
        return Result.success()
    }
}
