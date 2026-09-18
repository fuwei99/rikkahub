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
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 跨设备屏幕时间采集 + 同步 Worker。
 *
 * ## 2026-09-19 改版：D1 → 独立 Worker（R2）
 *
 * 原先采集完把 `screen_time:<deviceId>` 塞进 D1 outbox，再由 SyncEngine 推/拉。
 * 问题是一轮 pull 要串行几十条 SQL（实测几十秒），而查岗 Agent 要的是「另一台设备
 * 此刻在干嘛」，这个延迟不可接受；何况 D1 免费额度按行写入计费，屏幕时间整包重推
 * 也是烧额度的大户。
 *
 * 现在改成三步走：
 * 1. `collectRecent()` 先采集 —— 保证推的是刚算出来的，不是上一次的旧快照
 * 2. `pushOwn()` 把最近几天整包 POST 给 [ScreenTimeSyncClient]
 * 3. `pullAndMerge()` 拉别的设备最近几天，LWW 写回本地 Room
 *
 * 查岗用的 `get_screen_time` tool 照旧读本地 Room，查询侧零改动。
 *
 * ## 调度
 *
 * OneTime 链式自调度，每一发对齐到每小时的第 [ALIGN_MINUTE] 分钟（:09）——
 * 查岗在 :10/:20/:30/:40/:50/:00，错开一分钟免得撞上。
 *
 * 另挂一条 [BACKSTOP_INTERVAL_MINUTES] 分钟 Periodic 兜底：OneTime 自续链一旦
 * 某一节在 doWork 途中被系统掐死（华为 EMUI 后台冻结是重灾区）就会永久断链，
 * 兜底周期由 WorkManager 自己持久化调度，不依赖 App 再入队。
 *
 * WorkManager 受 Doze/电池优化影响可能延迟执行 → 分钟级是 best-effort；
 * 数据靠「每次运行把最近几天整体重算」兜底，延迟只会晚到不会漏算。
 */
class ScreenTimeCollectWorker(
    context: Context,
    params: WorkerParameters,
    private val collector: ScreenTimeCollector,
    private val syncClient: ScreenTimeSyncClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 1) 先采集：推送的数据必须是此刻刚算出来的，不能拿上一次的快照糊弄。
        runCatching { collector.collectRecent() }
            .onFailure { Log.w(TAG, "collect failed", it) }

        // 2) 推自己的（最近 N 天）到 Worker
        runCatching { syncClient.pushOwn(applicationContext) }
            .onFailure { Log.w(TAG, "push failed", it) }

        // 3) 拉别人的写回 Room —— 查岗 get_screen_time 读的就是这张表
        runCatching { syncClient.pullAndMerge(applicationContext) }
            .onFailure { Log.w(TAG, "pull failed", it) }

        enqueueNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "ScreenTimeCollectWorker"
        private const val UNIQUE_NAME = "rikkahub_screen_time_collect"
        private const val BACKSTOP_NAME = "rikkahub_screen_time_collect_backstop"

        /** 对齐推送的分钟数：每小时第 9 分钟（查岗在 :10/:20/...，错开一分钟） */
        private const val ALIGN_MINUTE = 9

        /** 兜底周期（分钟）。PeriodicWork 最短 15 分钟，取最小值。 */
        private const val BACKSTOP_INTERVAL_MINUTES = 15L

        /** App 启动时启动采集链：立即跑一发，并保证续发链与兜底周期都存在 */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScreenTimeCollectWorker>().build()
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
                .setInitialDelay(millisUntilNextAlignedMinute(ALIGN_MINUTE), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * 距下一个「每小时第 [minute] 分钟」的毫秒数。
         *
         * 用于把推送对齐到 :09，避开 :10/:20/:30/:40/:50/:00 的查岗点。
         * 保底 1 秒，避免刚好卡在整点边界算出 0 导致忙循环。
         */
        private fun millisUntilNextAlignedMinute(minute: Int): Long {
            val cal = Calendar.getInstance()
            val now = cal.timeInMillis
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= now) cal.add(Calendar.HOUR_OF_DAY, 1)
            return (cal.timeInMillis - now).coerceAtLeast(1_000L)
        }
    }
}
