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
import me.rerere.rikkahub.data.sync.core.SyncEngine
import java.util.concurrent.TimeUnit

/**
 * 跨设备屏幕时间采集 Worker（方案 2026-08-09；2026-09-15 加固）。
 *
 * 采集节奏：OneTime 链式自调度，doWork 末尾 enqueue 下一发（[INTERVAL_MINUTES] 分钟后）。
 * - [start]：App 启动时调用，KEEP 策略（已有挂起链节则不重复建链）
 * - 链内续发：REPLACE（当前链节已完成，安全替换）
 *
 * ⚠️ OneTime 自续链的致命缺陷（2026-09-15 定位）：整条链的存活依赖「每一节都在
 * 结束前成功 enqueue 下一节」。一旦某一节在 doWork 途中被系统掐死（华为 EMUI
 * 后台冻结是重灾区），下一节永远不会被建，采集链**永久归零**，除非重启 App。
 * 现场表现就是「屏幕时间半小时才过来一次」——其实不是慢，是它压根没在跑。
 *
 * 加固：额外注册一条 [BACKSTOP_INTERVAL_MINUTES] 分钟的 [PeriodicWorkRequest] 兜底
 * （[BACKSTOP_NAME]）。PeriodicWork 由 WorkManager 自己持久化调度，不依赖 App 再入队，
 * 即使一次性链断掉，最迟一个兜底周期内也会被重新接上。两条腿走路，唯一名互不覆盖。
 *
 * WorkManager 受 Doze/电池优化影响可能延迟执行 → 分钟级是 best-effort；
 * 数据靠「每次运行把最近几天整体重算」兜底，延迟只会晚到不会漏算。
 */
class ScreenTimeCollectWorker(
    context: Context,
    params: WorkerParameters,
    private val collector: ScreenTimeCollector,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val changed = runCatching { collector.collectToday() }
            .onFailure { Log.w(TAG, "collect failed", it) }
            .getOrDefault(false)
        // S1：采集完直接推——Worker 是合法后台执行上下文，不依赖前台监听器。
        // 解决「rikkahub 在后台时屏幕时间永远不上云」的硬伤。
        if (changed) {
            runCatching { syncEngine.pushOnly() }
                .onFailure { Log.w(TAG, "screen time push failed", it) }
        }
        enqueueNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "ScreenTimeCollectWorker"
        private const val UNIQUE_NAME = "rikkahub_screen_time_collect"
        private const val BACKSTOP_NAME = "rikkahub_screen_time_collect_backstop"

        /** 一次性链的采集间隔（分钟） */
        private const val INTERVAL_MINUTES = 5L

        /** 兜底周期（分钟）。PeriodicWork 最短 15 分钟，取最小值。 */
        private const val BACKSTOP_INTERVAL_MINUTES = 15L

        /** App 启动时启动采集链：立即采一发（不延迟），并保证续发链与兜底周期都存在 */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScreenTimeCollectWorker>()
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            enqueueBackstop(context)
        }

        /**
         * 注册兜底周期。KEEP 策略：已存在则不重复注册（避免每次启动重置周期计时）。
         * 与一次性链共用同一个 Worker 类，但唯一名不同，互不覆盖。
         */
        private fun enqueueBackstop(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScreenTimeCollectWorker>(
                BACKSTOP_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(BACKSTOP_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
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
