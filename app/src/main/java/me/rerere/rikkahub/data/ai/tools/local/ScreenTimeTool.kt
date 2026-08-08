package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.screentime.SyncScreenTimeAppItem
import me.rerere.rikkahub.data.screentime.computeForegroundTime
import me.rerere.rikkahub.data.screentime.resolveAppName
import me.rerere.rikkahub.data.screentime.resolveLauncherPackages
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val screenTimeJson = Json { ignoreUnknownKeys = true }

/**
 * get_screen_time（方案 2026-08-09 跨设备屏幕时间）：
 * - 本机 = UsageStats 精确实时计算（原口径，无回归），granularity=precise
 * - 其他已同步设备 = Room 里 pull 下来的日聚合（按天近似，整日计入不按比例切分），granularity=daily
 * - 无 Usage access 权限时本机报 NO_PERMISSION，但对端数据照常返回
 *   （「AI 在 A 机看 B 机有没有摸鱼」的主场景不依赖本机权限）
 */
internal fun buildScreenTimeTool(
    context: Context,
    eventBus: AppEventBus,
    database: AppDatabase,
): Tool = Tool(
    name = "get_screen_time",
    description = """
        Get screen usage (screen time) of the current device AND other synced devices over a time range.
        Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week).
        Returns 'devices': an array with one entry per device, each tagged by
        device_id / device_label / is_current_device / granularity.
        - The current device is computed precisely from UsageStats ('granularity': 'precise').
        - Other devices come from D1-synced daily aggregates ('granularity': 'daily', kept for ~14 days);
          their days are counted whole (no proportional splitting), so numbers are approximate and may be incomplete.
        Top-level total_ms / total_minutes / apps are the CURRENT device only (backward compatible);
        use 'total_all_devices_ms' for the sum across devices.
        Requires the 'Usage access' special permission for the local device; if it is not granted,
        the system usage access settings page is opened automatically, local data is skipped
        (local_error=NO_PERMISSION) but other devices' data is still returned.
        The device timezone is '${ZoneId.systemDefault()}' (UTC offset ${OffsetDateTime.now().offset});
        times without an explicit offset are interpreted in this timezone.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("begin", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                            "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                            "When provided, 'range' is ignored."
                    )
                })
                put("end", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "End time (exclusive), same formats as 'begin'. Defaults to now."
                    )
                })
                put("range", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("today")
                            add("week")
                        }
                    )
                    put(
                        "description",
                        "Convenience preset, used only when 'begin' is omitted: today or week. Default today."
                    )
                })
                put("top", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum number of top apps to return per device, sorted by usage time. Default 10.")
                })
            }
        )
    },
    execute = {
        val params = it.jsonObject
        val top = params["top"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 50) ?: 10

        val now = ZonedDateTime.now()
        val zone = now.zone
        val beginRaw = params["begin"]?.jsonPrimitive?.contentOrNull
        val endRaw = params["end"]?.jsonPrimitive?.contentOrNull
        val rangePreset = params["range"]?.jsonPrimitive?.contentOrNull ?: "today"

        val startTime: ZonedDateTime
        val endTime: ZonedDateTime
        try {
            endTime = endRaw?.let { raw -> parseUsageTime(raw, zone) } ?: now
            startTime = if (beginRaw != null) {
                parseUsageTime(beginRaw, zone)
            } else when (rangePreset) {
                "week" -> now.minusDays(7)
                else -> now.toLocalDate().atStartOfDay(zone)
            }
        } catch (e: Exception) {
            val payload = buildJsonObject {
                put("error", "INVALID_TIME")
                put("message", e.message ?: "Invalid time format for begin/end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        if (!startTime.isBefore(endTime)) {
            val payload = buildJsonObject {
                put("error", "INVALID_RANGE")
                put("message", "begin must be earlier than end.")
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val isCustom = beginRaw != null || endRaw != null
        val localDeviceId = SyncLocalPrefs.deviceId(context)

        // 对端数据：Room 里 pull 下来的日聚合（请求区间按天过滤；日粒度近似，不按比例切分）
        val remoteRows = database.screenTimeDayDao().getBetween(
            startDate = startTime.toLocalDate().toString(),
            endDate = endTime.toLocalDate().toString(),
        ).filter { it.deviceId != localDeviceId }

        // 组装除本机外的 devices 数组（权限分支与正常分支共用）
        fun buildRemoteDevices(): JsonArray = buildJsonArray {
            remoteRows.groupBy { it.deviceId }.forEach { (deviceId, rows) ->
                val deviceTotalMs = rows.sumOf { it.totalMs }
                // package -> (appName, ms)；appName 取首次出现（同包多天同名）
                val appTotals = LinkedHashMap<String, Pair<String, Long>>()
                rows.forEach { row ->
                    runCatching { screenTimeJson.decodeFromString<List<SyncScreenTimeAppItem>>(row.appsJson) }
                        .getOrDefault(emptyList())
                        .forEach { item ->
                            val prev = appTotals[item.packageName]
                            appTotals[item.packageName] = if (prev == null) {
                                item.appName to item.ms
                            } else {
                                prev.first to (prev.second + item.ms)
                            }
                        }
                }
                add(buildJsonObject {
                    put("device_id", deviceId)
                    put("device_label", rows.first().deviceLabel)
                    put("is_current_device", false)
                    put("granularity", "daily")
                    put("days_with_data", rows.size)
                    put("data_start_date", rows.minOf { it.date })
                    put("data_end_date", rows.maxOf { it.date })
                    put("total_ms", deviceTotalMs)
                    put("total_minutes", deviceTotalMs / 60000)
                    put("apps", buildJsonArray {
                        appTotals.entries
                            .sortedWith(
                                compareByDescending<Map.Entry<String, Pair<String, Long>>> { it.value.second }
                                    .thenBy { it.key }
                            )
                            .take(top)
                            .forEach { (pkg, pair) ->
                                add(buildJsonObject {
                                    put("package", pkg)
                                    put("app_name", pair.first)
                                    put("total_ms", pair.second)
                                    put("total_minutes", pair.second / 60000)
                                })
                            }
                    })
                })
            }
        }

        // 本机无权限：不直接 return，对端数据照常返回
        if (!context.hasUsageStatsPermission()) {
            eventBus.emit(AppEvent.OpenUsageAccessSettings)
            val payload = buildJsonObject {
                put("range", if (isCustom) "custom" else rangePreset)
                put("start", startTime.withNano(0).toString())
                put("end", endTime.withNano(0).toString())
                put("local_error", "NO_PERMISSION")
                put(
                    "message",
                    "Usage access permission is not granted; the system settings page has been opened. " +
                        "Local screen time is unavailable, but data from other synced devices is still returned."
                )
                put("devices", buildRemoteDevices())
            }
            return@Tool listOf(UIMessagePart.Text(payload.toString()))
        }

        val endMs = endTime.toInstant().toEpochMilli()
        val startMs = startTime.toInstant().toEpochMilli()

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val pm = context.packageManager

        // 通过逐个前台/后台事件配对计算真实前台时长, 比 queryAndAggregateUsageStats
        // 更接近系统"屏幕使用时间", 避免统计桶溢出导致的范围偏差; 排除桌面 launcher.
        val launcherPackages = resolveLauncherPackages(pm)
        val foregroundMs = computeForegroundTime(usageStatsManager, startMs, endMs, launcherPackages)

        val sorted = foregroundMs.entries
            .filter { entry -> entry.value > 0 }
            .sortedByDescending { entry -> entry.value }

        val totalMs = sorted.sumOf { entry -> entry.value }
        val apps = sorted.take(top)

        val localApps = buildJsonArray {
            apps.forEach { entry ->
                add(buildJsonObject {
                    put("package", entry.key)
                    put("app_name", resolveAppName(pm, entry.key))
                    put("total_ms", entry.value)
                    put("total_minutes", entry.value / 60000)
                })
            }
        }
        val remoteTotalMs = remoteRows.groupBy { it.deviceId }.values.sumOf { rows -> rows.sumOf { it.totalMs } }

        val payload = buildJsonObject {
            put("range", if (isCustom) "custom" else rangePreset)
            put("start", startTime.withNano(0).toString())
            put("end", endTime.withNano(0).toString())
            // 顶层保留本机口径（向后兼容）；跨设备明细看 devices[]
            put("total_ms", totalMs)
            put("total_minutes", totalMs / 60000)
            put("apps", localApps)
            put("total_all_devices_ms", totalMs + remoteTotalMs)
            put("devices", buildJsonArray {
                add(buildJsonObject {
                    put("device_id", localDeviceId)
                    put("device_label", SyncLocalPrefs.deviceLabel(context))
                    put("is_current_device", true)
                    put("granularity", "precise")
                    put("total_ms", totalMs)
                    put("total_minutes", totalMs / 60000)
                    put("apps", localApps)
                })
                buildRemoteDevices().forEach { add(it) }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * 解析 begin/end 时间参数, 依次尝试: epoch 毫秒 -> 带偏移日期时间 -> Instant ->
 * 本地日期时间 -> 本地日期(当天 0 点). 全部失败时抛出异常.
 */
private fun parseUsageTime(raw: String, zone: ZoneId): ZonedDateTime {
    val text = raw.trim()
    text.toLongOrNull()?.let { return Instant.ofEpochMilli(it).atZone(zone) }
    runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
    runCatching { return Instant.parse(text).atZone(zone) }
    runCatching { return LocalDateTime.parse(text).atZone(zone) }
    runCatching { return LocalDate.parse(text).atStartOfDay(zone) }
    error("Invalid time format: '$raw'. Use ISO-8601 date/date-time or epoch milliseconds.")
}
