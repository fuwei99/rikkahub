package me.rerere.rikkahub.data.ai.schedule

import java.util.Calendar

/**
 * 定时触发时刻计算（纯函数，可单测；PLAN_SCHEDULE_WINDOWS §3）。
 *
 * 老算法只有一行 `now + intervalMinutes`，病在拿「闹钟实际响的那一刻」当新起点：
 * - 21:03:47 改了配置，从此全天卡死在 :03:47 / :23:47 这种鬼时间上，**没有标杆**；
 * - Doze / 冷启动每次延迟几秒都被吃进下一轮起点，**误差累积**；
 * - 窗口外照样每 N 分钟醒一次再 return，**白耗唤醒**。
 *
 * 新算法三层，取最近者触发：
 * 1. [ScheduleAgentTemplate.dailyTimes] —— 每天固定时刻（多点），硬保底；
 * 2. [ScheduleAgentTemplate.windows] —— 时间段 + 段内周期，**格子从窗口 start 起算**
 *    （`start + k * interval`），所以触发点永远钉在整点刻度上，改配置/系统延迟都不漂；
 * 3. 都没配 → 回落 `now + intervalMinutes`（老行为，兼容）。
 *
 * 窗口外时，闹钟直接排到「下一个窗口的 start」，中间一次都不醒。
 */
object ScheduleTimePlanner {

    /** 定时点容差：闹钟晚响这么多分钟内仍算命中该定时点。 */
    const val DAILY_TIME_GRACE_MINUTES = 5

    /** 往后最多找几天（防全 days 配空集时死循环）。 */
    private const val LOOKAHEAD_DAYS = 8

    /**
     * 下一次触发。返回 null 表示模板压根排不出闹钟（windows/dailyTimes 都配了但全是空集）。
     */
    fun nextFire(template: ScheduleAgentTemplate, now: Long = System.currentTimeMillis()): ScheduleFire {
        val candidates = buildList {
            template.dailyTimes.forEach { daily -> nextDailyFire(daily, now)?.let { add(it) } }
            template.windows.forEach { window -> nextWindowFire(window, now)?.let { add(it) } }
        }
        val best = candidates.minByOrNull { it.atMillis }
        if (best != null) return best
        // 兜底：老行为
        return ScheduleFire(atMillis = now + template.safeIntervalMinutes * MINUTE_MILLIS)
    }

    /** 某个固定时刻在未来最近的一次。 */
    private fun nextDailyFire(daily: ScheduleDailyTime, now: Long): ScheduleFire? {
        val at = daily.atMinutes
        for (offset in 0 until LOOKAHEAD_DAYS) {
            val dayStart = startOfDay(now) + offset * DAY_MINUTES * MINUTE_MILLIS
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            if (!daily.matchesDay(cal.isoDayOfWeek())) continue
            val fireAt = dayStart + at * MINUTE_MILLIS
            if (fireAt > now) {
                return ScheduleFire(atMillis = fireAt, tag = daily.tag)
            }
        }
        return null
    }

    /**
     * 某个窗口在未来最近的一次触发：
     * - now 落在窗口内 → 取窗口内 **下一个对齐格子**（`start + k*interval`）；
     * - 格子越过窗口尾 / now 在窗口外 → 取该窗口下一次 start。
     *
     * offset 从 -1 起：跨午夜窗口（如 23:30-01:00）此刻可能属于「昨天开始」的那一场。
     */
    private fun nextWindowFire(window: ScheduleWindow, now: Long): ScheduleFire? {
        val interval = window.safeInterval * MINUTE_MILLIS
        val duration = window.durationMinutes * MINUTE_MILLIS
        val today = startOfDay(now)
        for (offset in -1 until LOOKAHEAD_DAYS) {
            val dayStart = today + offset * DAY_MINUTES * MINUTE_MILLIS
            val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
            if (!window.matchesDay(cal.isoDayOfWeek())) continue
            val start = dayStart + window.startMinutes * MINUTE_MILLIS
            val end = start + duration
            if (now < start) {
                // 窗口还没开 → 排到开场那一刻
                return ScheduleFire(atMillis = start, windowName = window.name)
            }
            if (now < end) {
                // 窗口内 → 下一个对齐格子（严格大于 now）
                val elapsed = now - start
                val k = elapsed / interval + 1
                val tick = start + k * interval
                if (tick <= end) {
                    return ScheduleFire(atMillis = tick, windowName = window.name)
                }
                // 本场格子用尽，继续找下一场
            }
        }
        return null
    }

    /**
     * now 是否落在任一窗口内（含窗口尾时刻，方便收卷那一发）。
     */
    fun isWithinAnyWindow(template: ScheduleAgentTemplate, now: Long = System.currentTimeMillis()): ScheduleWindow? {
        val today = startOfDay(now)
        template.windows.forEach { window ->
            val duration = window.durationMinutes * MINUTE_MILLIS
            for (offset in -1..0) {
                val dayStart = today + offset * DAY_MINUTES * MINUTE_MILLIS
                val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
                if (!window.matchesDay(cal.isoDayOfWeek())) continue
                val start = dayStart + window.startMinutes * MINUTE_MILLIS
                if (now in start..(start + duration)) return window
            }
        }
        return null
    }

    /**
     * now 是否刚好命中某个固定时刻（允许晚 [DAILY_TIME_GRACE_MINUTES] 分钟，
     * 因为 AlarmManager 在 Doze 下会推迟）。
     */
    fun matchedDailyTime(template: ScheduleAgentTemplate, now: Long = System.currentTimeMillis()): ScheduleDailyTime? {
        val today = startOfDay(now)
        val grace = DAILY_TIME_GRACE_MINUTES * MINUTE_MILLIS
        template.dailyTimes.forEach { daily ->
            for (offset in -1..0) {
                val dayStart = today + offset * DAY_MINUTES * MINUTE_MILLIS
                val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
                if (!daily.matchesDay(cal.isoDayOfWeek())) continue
                val at = dayStart + daily.atMinutes * MINUTE_MILLIS
                if (now in at..(at + grace)) return daily
            }
        }
        return null
    }

    /**
     * 本次触发是否放行（Runner 用）。
     *
     * 配了 windows / dailyTimes → 只在窗口内或命中定时点时执行（闹钟被 Doze 推迟到
     * 窗口外、或 BOOT 重排踩到奇怪时刻时，这里兜住）；
     * 都没配 → 老行为，永远放行。
     */
    fun resolveTrigger(template: ScheduleAgentTemplate, now: Long = System.currentTimeMillis()): TriggerContext? {
        if (template.windows.isEmpty() && template.dailyTimes.isEmpty()) {
            return TriggerContext(windowName = "", tag = "")
        }
        matchedDailyTime(template, now)?.let {
            return TriggerContext(windowName = isWithinAnyWindow(template, now)?.name.orEmpty(), tag = it.tag)
        }
        isWithinAnyWindow(template, now)?.let {
            return TriggerContext(windowName = it.name, tag = "")
        }
        return null
    }

    /** 本轮触发的上下文，展开进 taskPrompt 的 `{window}` / `{tag}` 占位符。 */
    data class TriggerContext(
        val windowName: String,
        val tag: String,
    )
}
