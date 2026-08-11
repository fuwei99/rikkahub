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
 * 跨设备屏幕时间采集器（方案 2026-08-09，BUG-2/3 修复版）。
 *
 * WorkManager 每 10 分钟调度 → [collectRecent] 重算最近 N 天逐日 upsert；
 * 内容无变化不写不传（逐日 sha 比对，零流量）。
 */
class ScreenTimeCollector(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 兼容旧 Worker 的入口，委托给 [collectRecent] */
    suspend fun collectToday(): Boolean = collectRecent()

    /**
     * 采集最近 [SCREEN_TIME_COLLECT_LOOKBACK_DAYS] 天（含今天）的屏幕时间，逐日 upsert。
     * - 跨零点回补：今天 23:5x 采集 → 明天 00:0x 还会重算昨天，不丢最后几分钟
     * - Doze 补洞：如果设备一整天没跑 Worker，下次跑时补上那天的数据
     * - 内容无变化时跳过写库（逐日 changed 判断）
     */
    suspend fun collectRecent(): Boolean = withContext(Dispatchers.IO) {
        if (!context.hasUsageStatsPermission()) return@withContext false
        var anyChanged = false
        runCatching {
            val deviceId = SyncLocalPrefs.deviceId(context)
            val deviceLabel = SyncLocalPrefs.deviceLabel(context)
            val zone = ZoneId.systemDefault()
            val nowMs = System.currentTimeMillis()
            val today = LocalDate.now(zone)

            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val pm = context.packageManager
            val launcherPackages = resolveLauncherPackages(pm)

            val dao = database.screenTimeDayDao()

            // 过期行清理无条件执行一次
            dao.pruneBefore(
                today.minusDays(LOCAL_RETENTION_DAYS.toLong())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            )

            for (offset in 0 until SCREEN_TIME_COLLECT_LOOKBACK_DAYS) {
                val day = today.minusDays(offset.toLong())
                val dateStr = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEndMs = if (offset == 0) nowMs
                else day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

                val breakdown = computeForegroundBreakdown(
                    usageStatsManager = usageStatsManager,
                    startMs = dayStartMs,
                    endMs = dayEndMs,
                    excludedPackages = launcherPackages,
                    hourlyZone = zone,
                )

                val sorted = breakdown.perApp.entries
                    .filter { it.value > 0 }
                    .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                val totalMs = sorted.sumOf { it.value }
                val apps = sorted.take(SCREEN_TIME_TOP_APPS).map {
                    SyncScreenTimeAppItem(
                        packageName = it.key,
                        appName = resolveAppName(pm, it.key),
                        ms = it.value,
                    )
                }
                val appsJson = json.encodeToString(apps)
                val hourlyJson = if (breakdown.hourlyMs.isNotEmpty()) {
                    json.encodeToString(breakdown.hourlyMs)
                } else ""

                val existing = dao.get(deviceId, dateStr)
                val changed = existing == null ||
                    existing.totalMs != totalMs ||
                    existing.deviceLabel != deviceLabel ||
                    existing.appsJson != appsJson ||
                    existing.hourlyJson != hourlyJson
                if (!changed) continue

                dao.upsert(
                    ScreenTimeDayEntity(
                        deviceId = deviceId,
                        deviceLabel = deviceLabel,
                        date = dateStr,
                        totalMs = totalMs,
                        appsJson = appsJson,
                        hourlyJson = hourlyJson,
                        updatedAt = nowMs,
                    )
                )
                anyChanged = true
            }

            if (anyChanged && settingsStore.settingsFlow.value.d1Config.isConfigured) {
                SyncBundleEnqueuer.enqueue(SCREEN_TIME_BUNDLE_PREFIX + deviceId)
            }
            anyChanged
        }.getOrElse {
            Log.e(TAG, "collectRecent failed", it)
            false
        }
    }
}