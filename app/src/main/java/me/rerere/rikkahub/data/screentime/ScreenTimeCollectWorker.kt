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
 * OneTime 链式自调度，每 10 分钟一发，落在 :09/:19/:29/:39/:49/:59 ——
 * 查岗在 :10/:20/:30/:40/:50/:00，推拉卡在查岗前一分钟收工。
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

        /**
         * 推拉节奏：每 [PUSH_EVERY_MINUTES] 分钟一发，落在分钟数个位为
         * [PUSH_OFFSET_MINUTES] 的时刻，即 :09/:19/:29/:39/:49/:59。
         *
         * 为什么是这几个点：查岗 Agent 在 :10/:20/:30/:40/:50/:00 各查一次，
         * 推拉卡在查岗前一分钟收工 —— 查岗读 Room 时拿到的就是刚同步下来的最新值。
         */
        private const val PUSH_EVERY_MINUTES = 10
        private const val PUSH_OFFSET_MINUTES = 9

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
                .setInitialDelay(millisUntilNextPush(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * 距下一个推拉点的毫秒数。
         *
         * 推拉点 = 每 [PUSH_EVERY_MINUTES] 分钟一个、落在分钟数个位为
         * [PUSH_OFFSET_MINUTES] 的时刻，即 :09/:19/:29/:39/:49/:59。
         *
         * 保底 1 秒，避免刚好卡在推拉点边界算出 0 导致忙循环。
         */
        private fun millisUntilNextPush(): Long {
            val cal = Calendar.getInstance()
            val now = cal.timeInMillis
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val targetMinute =
                (cal.get(Calendar.MINUTE) / PUSH_EVERY_MINUTES) * PUSH_EVERY_MINUTES + PUSH_OFFSET_MINUTES
            cal.set(Calendar.MINUTE, targetMinute)
            if (cal.timeInMillis <= now) cal.add(Calendar.MINUTE, PUSH_EVERY_MINUTES)
            return (cal.timeInMillis - now).coerceAtLeast(1_000L)
        }
    }
}
