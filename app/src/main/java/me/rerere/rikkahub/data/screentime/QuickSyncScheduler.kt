package me.rerere.rikkahub.data.screentime

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 快速同步调度器：把「配置」翻译成「下一次该在什么时候跑」。
 *
 * 纯函数、无副作用、不碰 Android API —— 方便单测与心智推演。
 * 所有参数由调用方从 [me.rerere.rikkahub.data.sync.core.SyncAdvancedConfig] 取出后传入，
 * 这里不认识配置类，也不认识 WorkManager。
 *
 * ## 两种模式
 *
 * - **window**：在 `[start, end]` 时间窗内，从起点起每 `interval` 分钟一次。
 *   例：08:00~22:40 每 10 分钟 → 08:00 / 08:10 / … / 22:40。
 * - **fixed**：每天按固定时刻表跑。例：`09:00,12:00,18:00,22:00`。
 *
 * 窗口跑完自动跳到次日起点；固定表跑完自动跳到次日首个时刻。
 */
object QuickSyncScheduler {

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** 解析 `HH:mm`，失败返回 null（不抛异常，配置是用户手输的） */
    fun parseHhMm(raw: String): LocalTime? =
        runCatching { LocalTime.parse(raw.trim(), HHMM) }.getOrNull()

    /** 解析逗号分隔的 `HH:mm` 列表：丢掉非法项、去重、升序 */
    fun parseFixedTimes(raw: String): List<LocalTime> =
        raw.split(',', '，', '\n', ' ')
            .mapNotNull { parseHhMm(it) }
            .distinct()
            .sorted()

    /**
     * 下一次触发时刻。
     *
     * @return `null` 表示配置非法（解析不出时间 / 窗口起止颠倒 / 间隔非正），
     *         调用方应退回兜底周期而不是傻等。
     */
    fun nextTrigger(
        now: LocalDateTime,
        mode: String,
        windowStart: String,
        windowEnd: String,
        intervalMinutes: Int,
        fixedTimes: String,
    ): LocalDateTime? = when (mode) {
        "fixed" -> nextFixed(now, fixedTimes)
        else -> nextWindow(now, windowStart, windowEnd, intervalMinutes)
    }

    /** 距下一次触发的毫秒数；配置非法时返回 `null` */
    fun millisUntilNextTrigger(
        now: LocalDateTime,
        mode: String,
        windowStart: String,
        windowEnd: String,
        intervalMinutes: Int,
        fixedTimes: String,
    ): Long? = nextTrigger(now, mode, windowStart, windowEnd, intervalMinutes, fixedTimes)
        ?.let { Duration.between(now, it).toMillis().coerceAtLeast(MIN_DELAY_MS) }

    /**
     * 未来 [count] 个触发时刻，用于设置页实时预览。
     * 配置非法时返回空列表。
     */
    fun preview(
        now: LocalDateTime,
        mode: String,
        windowStart: String,
        windowEnd: String,
        intervalMinutes: Int,
        fixedTimes: String,
        count: Int = 4,
    ): List<LocalDateTime> {
        val out = ArrayList<LocalDateTime>(count)
        var cursor = now
        repeat(count) {
            val next = nextTrigger(cursor, mode, windowStart, windowEnd, intervalMinutes, fixedTimes)
                ?: return out
            out += next
            // 往前挪 1 分钟再求下一个，避免原地打转
            cursor = next.plusMinutes(1)
        }
        return out
    }

    /** 保底 1 秒，避免刚好卡在触发点边界算出 0 导致忙循环 */
    private const val MIN_DELAY_MS = 1_000L

    private fun nextFixed(now: LocalDateTime, raw: String): LocalDateTime? {
        val times = parseFixedTimes(raw)
        if (times.isEmpty()) return null
        times.firstOrNull { it > now.toLocalTime() }
            ?.let { return LocalDateTime.of(now.toLocalDate(), it) }
        return LocalDateTime.of(now.toLocalDate().plusDays(1), times.first())
    }

    private fun nextWindow(
        now: LocalDateTime,
        rawStart: String,
        rawEnd: String,
        intervalMinutes: Int,
    ): LocalDateTime? {
        if (intervalMinutes <= 0) return null
        val start = parseHhMm(rawStart) ?: return null
        val end = parseHhMm(rawEnd) ?: return null
        // 跨零点窗口（如 22:00~02:00）不支持：语义含糊，不如直接判非法让用户改配置
        if (!end.isAfter(start)) return null

        val today = now.toLocalDate()

        var candidate = LocalDateTime.of(today, start)
        if (candidate <= now) {
            val elapsedMinutes = Duration.between(candidate, now).toMinutes()
            val steps = elapsedMinutes / intervalMinutes + 1
            candidate = candidate.plusMinutes(steps * intervalMinutes.toLong())
        }
        if (!candidate.toLocalTime().isAfter(end)) return candidate

        // 今天窗口已跑完 → 明天起点
        return LocalDateTime.of(today.plusDays(1), start)
    }
}
