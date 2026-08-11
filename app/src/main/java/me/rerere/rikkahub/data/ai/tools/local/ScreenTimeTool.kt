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
import me.rerere.rikkahub.data.screentime.SCREEN_TIME_HOUR_BUCKETS
import me.rerere.rikkahub.data.screentime.SyncScreenTimeAppItem
import me.rerere.rikkahub.data.screentime.computeForegroundBreakdown
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
 * get_screen_time（方案 2026-08-09 跨设备屏幕时间，2026-08-11 小时粒度修复）：
 * - 本机 = UsageStats 精确实时计算（原口径，无回归），granularity=precise
 * - 其他已同步设备 = Room 里 pull 下来的日聚合 + 24 小时桶，按请求区间做小时级裁剪，
 *   granularity=hourly（对端有小时桶）/ daily（老数据只有整日总数）
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
        device_id / device_label / is_current_device / granularity, and each carrying
        'hourly_ms': a 24-slot array of milliseconds per hour-of-day (local time, index 0 = 00:00-01:00)
        plus 'late_night_ms' (00:00-06:00) so you can tell WHEN the usage happened.
        - The current device is computed precisely from UsageStats ('granularity': 'precise').
        - Other devices come from D1-synced aggregates (kept for ~14 days). If the remote device
          reported hourly buckets, its numbers are clipped to the requested interval at hour
          resolution ('granularity': 'hourly'); older rows without buckets count whole days
          ('granularity': 'daily', approximate). When a partial day is clipped, per-app numbers
          are scaled proportionally and 'apps_estimated' is true.
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
                // package -> (appName, ms)；appName 取首次出现（同包多天同名）
                val appTotals = LinkedHashMap<String, Pair<String, Long>>()
                val deviceHourly = LongArray(SCREEN_TIME_HOUR_BUCKETS)
                var deviceTotalMs = 0L
                var anyHourly = false
                var anyEstimated = false

                rows.forEach { row ->
                    val hourly = row.hourlyJson.takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { screenTimeJson.decodeFromString<List<Long>>(raw) }
                            .getOrNull()
                            ?.takeIf { it.size == SCREEN_TIME_HOUR_BUCKETS }
                    }
                    val dayStart = runCatching { LocalDate.parse(row.date).atStartOfDay(zone) }.getOrNull()

                    // 按小时桶把该日裁剪到请求区间；无桶或日期不可解析时退回整日计入（老口径）
                    val dayMs: Long = if (hourly != null && dayStart != null) {
                        anyHourly = true
                        var acc = 0L
                        hourly.forEachIndexed { hour, slotMs ->
                            if (slotMs <= 0) return@forEachIndexed
                            val slotStart = dayStart.plusHours(hour.toLong())
                            val slotEnd = slotStart.plusHours(1)
                            // 该小时槽与 [startTime, endTime) 完全无交集 → 丢弃
                            if (!slotEnd.isAfter(startTime) || !slotStart.isBefore(endTime)) return@forEachIndexed
                            val fullyInside = !slotStart.isBefore(startTime) && !slotEnd.isAfter(endTime)
                            if (fullyInside) {
                                acc += slotMs
                                deviceHourly[hour] += slotMs
                            } else {
                                // 边界小时：按重叠时长比例折算（桶内已无更细分布）
                                val from = maxOf(slotStart, startTime)
                                val until = minOf(slotEnd, endTime)
                                val overlapMs = until.toInstant().toEpochMilli() - from.toInstant().toEpochMilli()
                                val ratio = (overlapMs.toDouble() / 3_600_000.0).coerceIn(0.0, 1.0)
                                val part = (slotMs * ratio).toLong()
                                acc += part
                                deviceHourly[hour] += part
                                if (part != slotMs) anyEstimated = true
                            }
                        }
                        acc
                    } else {
                        row.totalMs
                    }
                    deviceTotalMs += dayMs

                    // per-app 按当日裁剪比例等比缩放（裁剪发生时才是估算值）
                    val scale = if (row.totalMs > 0 && dayMs != row.totalMs) {
                        anyEstimated = true
                        dayMs.toDouble() / row.totalMs.toDouble()
                    } else 1.0
                    if (dayMs <= 0) return@forEach
                    runCatching { screenTimeJson.decodeFromString<List<SyncScreenTimeAppItem>>(row.appsJson) }
                        .getOrDefault(emptyList())
                        .forEach { item ->
                            val scaled = if (scale == 1.0) item.ms else (item.ms * scale).toLong()
                            if (scaled <= 0) return@forEach
                            val prev = appTotals[item.packageName]
                            appTotals[item.packageName] = if (prev == null) {
                                item.appName to scaled
                            } else {
                                prev.first to (prev.second + scaled)
                            }
                        }
                }

                add(buildJsonObject {
                    put("device_id", deviceId)
                    put("device_label", rows.first().deviceLabel)
                    put("is_current_device", false)
                    put("granularity", if (anyHourly) "hourly" else "daily")
                    put("days_with_data", rows.size)
                    put("data_start_date", rows.minOf { it.date })
                    put("data_end_date", rows.maxOf { it.date })
                    put("total_ms", deviceTotalMs)
                    put("total_minutes", deviceTotalMs / 60000)
                    put("apps_estimated", anyEstimated)
                    if (anyHourly) {
                        put("hourly_ms", buildJsonArray { deviceHourly.forEach { add(it) } })
                        put("late_night_ms", (0 until 6).sumOf { deviceHourly[it] })
                    }
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
        val breakdown = computeForegroundBreakdown(
            usageStatsManager = usageStatsManager,
            startMs = startMs,
            endMs = endMs,
            excludedPackages = launcherPackages,
            hourlyZone = zone,
        )
        val foregroundMs = breakdown.perApp

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
        val remoteDevices = buildRemoteDevices()
        val remoteTotalMs = remoteDevices.sumOf { device ->
            device.jsonObject["total_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        }
        val localHourly = breakdown.hourlyMs

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
                    if (localHourly.size == SCREEN_TIME_HOUR_BUCKETS) {
                        put("hourly_ms", buildJsonArray { localHourly.forEach { add(it) } })
                        put("late_night_ms", (0 until 6).sumOf { localHourly[it] })
                    }
                    put("apps", localApps)
                })
                remoteDevices.forEach { add(it) }
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
