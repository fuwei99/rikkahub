package me.rerere.rikkahub.data.ai.schedule

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * 时间段 + 段内周期（PLAN_SCHEDULE_WINDOWS §2.1）。
 *
 * 解决老 `intervalMinutes` 的两个硬伤：
 * 1. 一个模板只有一种节奏——白天想 10 分钟查一次、夜里睡觉 1 小时查一次只能拆两个模板，
 *    查岗上下文断成两截；
 * 2. 窗口外照样醒着空转（Runner 里 return 掉，但闹钟已经排了下一发）。
 *
 * 现在每个窗口自带 [intervalMinutes]，窗口外直接把闹钟排到「下一个窗口的 start」，
 * 一次都不多醒。
 *
 * 跨午夜：`start > end` 视为跨天窗口（如 23:30-01:00），[days] 指 **start 所在那天**。
 */
@Serializable
data class ScheduleWindow(
    /** 窗口名，仅用于日志与 `{window}` 占位符。 */
    val name: String = "",
    /** 生效星期（ISO：周一=1 … 周日=7）。空 = 每天。 */
    val days: List<Int> = emptyList(),
    /** 起始时刻 HH:mm。 */
    val start: String = "00:00",
    /** 结束时刻 HH:mm；小于 start 视为跨午夜到次日。 */
    val end: String = "23:59",
    /** 段内触发周期（分钟）。 */
    val intervalMinutes: Int = 10,
) {
    val startMinutes: Int get() = parseHhMm(start)
    val endMinutes: Int get() = parseHhMm(end)

    /** 跨午夜窗口（end 落在次日）。 */
    val crossesMidnight: Boolean get() = endMinutes <= startMinutes

    /** 窗口时长（分钟），跨午夜自动 +24h。 */
    val durationMinutes: Int
        get() = if (crossesMidnight) endMinutes + DAY_MINUTES - startMinutes else endMinutes - startMinutes

    val safeInterval: Int get() = intervalMinutes.coerceAtLeast(1)

    fun matchesDay(isoDay: Int): Boolean = days.isEmpty() || isoDay in days
}

/**
 * 每天固定时刻触发（PLAN_SCHEDULE_WINDOWS §2.2）。
 *
 * 用于「这个点必须查一次」的硬保底，如 08:30 确认起床、22:20 晚自习收卷。
 * 与 [ScheduleWindow] 正交：无论窗口怎么配，这些时刻一定会触发。
 */
@Serializable
data class ScheduleDailyTime(
    /** 触发时刻 HH:mm。 */
    val at: String,
    /** 生效星期（ISO：周一=1 … 周日=7）。空 = 每天。 */
    val days: List<Int> = emptyList(),
    /** 场景标签，展开进 taskPrompt 的 `{tag}` 占位符（如「起床确认」）。 */
    val tag: String = "",
) {
    val atMinutes: Int get() = parseHhMm(at)

    fun matchesDay(isoDay: Int): Boolean = days.isEmpty() || isoDay in days
}

/** 一次触发的来源，用于日志与 `{window}` / `{tag}` 占位符。 */
data class ScheduleFire(
    val atMillis: Long,
    val windowName: String = "",
    val tag: String = "",
)

internal const val DAY_MINUTES = 24 * 60
internal const val MINUTE_MILLIS = 60_000L

/** "HH:mm" → 当日分钟数；非法格式回落 0。 */
internal fun parseHhMm(raw: String?): Int {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return 0
    val parts = text.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    return hour * 60 + minute
}

/** Calendar.DAY_OF_WEEK（周日=1）→ ISO（周一=1 … 周日=7）。 */
internal fun Calendar.isoDayOfWeek(): Int {
    val raw = get(Calendar.DAY_OF_WEEK)
    return if (raw == Calendar.SUNDAY) 7 else raw - 1
}

/** 当天 00:00 的毫秒值。 */
internal fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
