package me.rerere.rikkahub.data.screentime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScreenTimeDayEntity
import me.rerere.rikkahub.data.sync.core.SyncBundleEnqueuer
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "ScreenTimeCollector"

/**
 * 跨设备屏幕时间采集器（方案 2026-08-09）。
 *
 * 每 10 分钟（由 [ScreenTimeCollectWorker] 调度）把「今天 0 点 → now」的 UsageStats
 * 整体重算成日聚合并 upsert 到 [ScreenTimeDayEntity]；**内容无变化不写不传**：
 * - 不写 → 日行 sha 不变 → pushBundle 直接跳过，空闲设备零流量
 * - 只有 D1 配置好才入队（否则只是本地历史）
 *
 * 本机行以本地采集为准，pull 端不会用云端数据覆盖本机行（防回环）。
 */
class ScreenTimeCollector(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 采集本机「今天至今」的屏幕时间。返回是否发生了内容变化（写库/入队）。 */
    suspend fun collectToday(): Boolean = withContext(Dispatchers.IO) {
        if (!context.hasUsageStatsPermission()) return@withContext false
        runCatching {
            val deviceId = SyncLocalPrefs.deviceId(context)
            val deviceLabel = SyncLocalPrefs.deviceLabel(context)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val dayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val nowMs = System.currentTimeMillis()

            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val pm = context.packageManager
            val launcherPackages = resolveLauncherPackages(pm)
            val foregroundMs = computeForegroundTime(usageStatsManager, dayStartMs, nowMs, launcherPackages)
            // 排序必须确定性（ms 降序 + 包名升序兜底），否则同量级 App 顺序抖动会误判"内容变化"
            val sorted = foregroundMs.entries
                .filter { it.value > 0 }
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            val apps = sorted.take(SCREEN_TIME_TOP_APPS).map {
                SyncScreenTimeAppItem(
                    packageName = it.key,
                    appName = resolveAppName(pm, it.key),
                    ms = it.value,
                )
            }
            val totalMs = sorted.sumOf { it.value }
            val appsJson = json.encodeToString(apps)

            val dao = database.screenTimeDayDao()
            // 过期行清理与内容是否变化无关，无条件执行
            dao.pruneBefore(
                LocalDate.now(zone).minusDays(LOCAL_RETENTION_DAYS.toLong())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            )

            val existing = dao.get(deviceId, dateStr)
            val changed = existing == null ||
                existing.totalMs != totalMs ||
                existing.deviceLabel != deviceLabel ||
                existing.appsJson != appsJson
            if (!changed) return@withContext false

            dao.upsert(
                ScreenTimeDayEntity(
                    deviceId = deviceId,
                    deviceLabel = deviceLabel,
                    date = dateStr,
                    totalMs = totalMs,
                    appsJson = appsJson,
                    updatedAt = nowMs,
                )
            )
            // 内容已变化 → sha 必然变化；只有 D1 配置好才入队
            if (settingsStore.settingsFlow.value.d1Config.isConfigured) {
                SyncBundleEnqueuer.enqueue(SCREEN_TIME_BUNDLE_PREFIX + deviceId)
            }
            true
        }.getOrElse {
            Log.e(TAG, "collectToday failed", it)
            false
        }
    }
}
