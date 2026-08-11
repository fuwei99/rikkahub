package me.rerere.rikkahub.data.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// 计算屏幕时间时向前回看的窗口(12h), 用于还原区间开始时刻已在前台的 App;
// 取值需覆盖典型的一次连续使用时长, 过小会漏算开头, 过大只是多遍历些事件.
internal const val LOOKBACK_MS = 12L * 60 * 60 * 1000

/** 小时桶数量（一天 24 个 hour-of-day 桶） */
internal const val SCREEN_TIME_HOUR_BUCKETS = 24

/**
 * 前台时长明细：per-App 累计 + 按 hour-of-day 的 24 桶分布（后者仅在请求 zone 时非空）。
 */
internal data class ForegroundBreakdown(
    val perApp: Map<String, Long>,
    /** 长度 24 或 0；下标 = 本地时区的 hour-of-day，跨多天时同一小时会累加 */
    val hourlyMs: List<Long>,
)

/**
 * 用"全局单一前台"模型计算 [startMs, endMs) 区间内每个 App 的前台时长(毫秒).
 *
 * 任意时刻只有一个 App 处于计时状态: 新 App 进入前台时先结算上一个前台 App, 息屏时停止计时.
 * 这样各 App 时段串行不重叠, 不会出现 per-app 配对那种因前台时段重叠相加而偏大的问题, 结果
 * 与系统"屏幕使用时间"口径基本一致. 边界处理:
 * - 为正确处理"区间开始前已进入前台、区间内继续使用"的 App, 查询起点向前回看 [LOOKBACK_MS],
 *   据此还原区间开始时刻正在前台的 App; 结算时把累加区间裁剪到 [startMs, endMs], startMs
 *   之前的部分自动被裁掉, 既补回开头那段使用又不会高估.
 * - 区间结束时仍在前台的 App, 以 endMs 截断.
 * - [excludedPackages] 中的包(如桌面 launcher)不计入结果, 其停留时间视为"无 App 前台".
 *
 * 与 ScreenTimeTool 共用（2026-08-09 抽出），采集器与工具必须同一套口径。
 */
@Suppress(
    "DEPRECATION", // MOVE_TO_FOREGROUND/BACKGROUND 与 API29 的 ACTIVITY_RESUMED/PAUSED 值相同, 兼容 minSdk 26
    "NewApi" // SCREEN_NON_INTERACTIVE 是编译期常量, 低版本设备不会产生该事件, 引用安全
)
internal fun computeForegroundTime(
    usageStatsManager: UsageStatsManager,
    startMs: Long,
    endMs: Long,
    excludedPackages: Set<String>,
): Map<String, Long> = computeForegroundBreakdown(
    usageStatsManager = usageStatsManager,
    startMs = startMs,
    endMs = endMs,
    excludedPackages = excludedPackages,
    hourlyZone = null,
).perApp

/**
 * 与 [computeForegroundTime] 同一套口径，额外产出按 hour-of-day 的 24 桶分布。
 *
 * [hourlyZone] 为 null 时不统计小时桶（[ForegroundBreakdown.hourlyMs] 为空）；非 null 时按该时区
 * 的小时边界切片累加（用 truncatedTo(HOURS) 逐段推进，DST/偏移变更安全）。
 */
@Suppress("DEPRECATION", "NewApi")
internal fun computeForegroundBreakdown(
    usageStatsManager: UsageStatsManager,
    startMs: Long,
    endMs: Long,
    excludedPackages: Set<String>,
    hourlyZone: ZoneId? = null,
): ForegroundBreakdown {
    val foregroundMs = HashMap<String, Long>()
    val hourly = if (hourlyZone != null) LongArray(SCREEN_TIME_HOUR_BUCKETS) else null
    // 向前回看一段时间以捕获"区间开始前就进入前台"的事件; 累加时再裁剪回 [startMs, endMs]
    val events = usageStatsManager.queryEvents(startMs - LOOKBACK_MS, endMs)
    val event = UsageEvents.Event()

    // 当前正在计时的前台包及其起始时间; null 表示当前无 App 在前台(如停留桌面/息屏)
    var currentPkg: String? = null
    var currentStart = 0L

    // 结算当前前台段: 把 [currentStart, until) 与 [startMs, endMs] 的交集累加给 currentPkg
    fun settle(until: Long) {
        val pkg = currentPkg
        currentPkg = null
        if (pkg == null || pkg in excludedPackages) return // 排除的包不计入, 但仍清空计时状态
        val from = maxOf(currentStart, startMs) // 裁掉 startMs 之前的部分
        val duration = until - from
        if (duration > 0) {
            foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + duration
            if (hourly != null && hourlyZone != null) {
                // 按本地小时边界切片，累加到对应 hour-of-day 桶
                var cursor = from
                while (cursor < until) {
                    val zoned = Instant.ofEpochMilli(cursor).atZone(hourlyZone)
                    val nextHourMs = zoned.truncatedTo(ChronoUnit.HOURS)
                        .plusHours(1)
                        .toInstant()
                        .toEpochMilli()
                    val sliceEnd = minOf(nextHourMs, until)
                    hourly[zoned.hour] += sliceEnd - cursor
                    cursor = sliceEnd
                }
            }
        }
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (event.packageName != currentPkg) {
                    settle(event.timeStamp)         // 先结算上一个前台 App
                    currentPkg = event.packageName  // 再开始为新 App 计时
                    currentStart = event.timeStamp
                }
            }

            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                // 只结算当前正在计时的前台包; 其他包的 background 一律忽略(避免重叠/高估)
                if (event.packageName == currentPkg) {
                    settle(event.timeStamp)
                }
            }

            // 息屏: 停止计时, 系统在息屏期间同样不计入屏幕使用时间
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                settle(event.timeStamp)
            }
        }
    }
    // 区间结束时仍在前台的 App, 用 endMs 截断
    settle(endMs)
    return ForegroundBreakdown(
        perApp = foregroundMs,
        hourlyMs = hourly?.toList() ?: emptyList(),
    )
}

/**
 * 解析设备上所有桌面(HOME)应用的包名, 用于在屏幕使用时间里排除 launcher.
 * 查询所有响应 HOME intent 的 Activity, 覆盖默认及其他已安装的桌面应用.
 */
internal fun resolveLauncherPackages(pm: PackageManager): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}

internal fun resolveAppName(pm: PackageManager, packageName: String): String {
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
