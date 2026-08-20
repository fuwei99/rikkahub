package me.rerere.rikkahub.data.ai.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleTimePlannerTest {

    /** 构造某天某时刻的毫秒值。2026-08-20 是周四（ISO 4）。 */
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun hhmm(millis: Long): String = Calendar.getInstance().apply { timeInMillis = millis }.let {
        "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE))
    }

    private fun template(
        windows: List<ScheduleWindow> = emptyList(),
        dailyTimes: List<ScheduleDailyTime> = emptyList(),
        intervalMinutes: Int = 10,
    ) = ScheduleAgentTemplate(
        id = "t", name = "T",
        intervalMinutes = intervalMinutes,
        windows = windows,
        dailyTimes = dailyTimes,
    )

    // ---- 核心：格子对齐，不漂移 ----

    @Test
    fun `window ticks align to window start not to now`() {
        val t = template(windows = listOf(ScheduleWindow(start = "08:30", end = "11:50", intervalMinutes = 10)))
        // 老算法会算成 09:13:47 + 10min = 09:23:47（漂移）；新算法钉在 09:20
        val fire = ScheduleTimePlanner.nextFire(t, at(20, 9, 13, 47))
        assertEquals("09:20", hhmm(fire.atMillis))
        assertEquals(0, fire.atMillis % 60_000L) // 秒/毫秒被抹平
    }

    @Test
    fun `repeated scheduling never accumulates drift`() {
        val t = template(windows = listOf(ScheduleWindow(start = "08:30", end = "11:50", intervalMinutes = 10)))
        // 模拟每次闹钟都晚响 37 秒，连排 5 次，触发点必须始终是整十分
        var now = at(20, 8, 30, 37)
        repeat(5) {
            val fire = ScheduleTimePlanner.nextFire(t, now)
            assertEquals(0, fire.atMillis % (10 * 60_000L).let { _ -> 60_000L })
            val minute = Calendar.getInstance().apply { timeInMillis = fire.atMillis }.get(Calendar.MINUTE)
            assertEquals(0, minute % 10)
            now = fire.atMillis + 37_000L // 又晚响 37 秒
        }
    }

    @Test
    fun `tick exactly on grid moves to next grid`() {
        val t = template(windows = listOf(ScheduleWindow(start = "08:30", end = "11:50", intervalMinutes = 10)))
        // 正好卡在格子上 → 必须严格前进，不能返回自己（否则闹钟死循环立刻重触发）
        assertEquals("08:40", hhmm(ScheduleTimePlanner.nextFire(t, at(20, 8, 30)).atMillis))
    }

    // ---- 窗口外不空转 ----

    @Test
    fun `outside window jumps to next window start`() {
        val t = template(
            windows = listOf(
                ScheduleWindow(name = "早自习", start = "08:30", end = "11:50", intervalMinutes = 10),
                ScheduleWindow(name = "午自习", start = "14:00", end = "17:50", intervalMinutes = 10),
            )
        )
        // 12:30 在午饭休息里：不该 10 分钟醒一次，应直接排到 14:00
        val fire = ScheduleTimePlanner.nextFire(t, at(20, 12, 30))
        assertEquals("14:00", hhmm(fire.atMillis))
        assertEquals("午自习", fire.windowName)
    }

    @Test
    fun `last tick of window does not overflow past end`() {
        val t = template(
            windows = listOf(
                ScheduleWindow(name = "早自习", start = "08:30", end = "11:50", intervalMinutes = 10),
                ScheduleWindow(name = "午自习", start = "14:00", end = "17:50", intervalMinutes = 10),
            )
        )
        // 11:45 → 11:50 是窗口尾格（含），仍属早自习
        assertEquals("11:50", hhmm(ScheduleTimePlanner.nextFire(t, at(20, 11, 45)).atMillis))
        // 11:50 之后没格子了 → 跳到午自习
        assertEquals("14:00", hhmm(ScheduleTimePlanner.nextFire(t, at(20, 11, 51)).atMillis))
    }

    // ---- 多节奏：白天 10 分钟，夜里 60 分钟 ----

    @Test
    fun `different windows keep independent intervals`() {
        val t = template(
            windows = listOf(
                ScheduleWindow(name = "晚自习", start = "18:40", end = "22:20", intervalMinutes = 10),
                ScheduleWindow(name = "夜间睡眠", start = "01:20", end = "06:00", intervalMinutes = 60),
            )
        )
        // 晚自习里 10 分钟节奏
        assertEquals("19:00", hhmm(ScheduleTimePlanner.nextFire(t, at(20, 18, 55)).atMillis))
        // 睡眠段 60 分钟节奏：01:20 起格 → 02:20 / 03:20 …
        val night = ScheduleTimePlanner.nextFire(t, at(20, 2, 5))
        assertEquals("02:20", hhmm(night.atMillis))
        assertEquals("夜间睡眠", night.windowName)
    }

    // ---- 定时点：多点 + 硬保底 ----

    @Test
    fun `multiple daily times all fire`() {
        val t = template(
            dailyTimes = listOf(
                ScheduleDailyTime(at = "08:30", tag = "起床确认"),
                ScheduleDailyTime(at = "22:20", tag = "晚自习收卷"),
            )
        )
        val morning = ScheduleTimePlanner.nextFire(t, at(20, 7, 0))
        assertEquals("08:30", hhmm(morning.atMillis))
        assertEquals("起床确认", morning.tag)

        val evening = ScheduleTimePlanner.nextFire(t, at(20, 12, 0))
        assertEquals("22:20", hhmm(evening.atMillis))
        assertEquals("晚自习收卷", evening.tag)

        // 当天两点都过了 → 顺延到明天 08:30
        val tomorrow = ScheduleTimePlanner.nextFire(t, at(20, 23, 0))
        assertEquals("08:30", hhmm(tomorrow.atMillis))
        assertEquals(21, Calendar.getInstance().apply { timeInMillis = tomorrow.atMillis }.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `daily time wins when earlier than window tick`() {
        val t = template(
            windows = listOf(ScheduleWindow(name = "早自习", start = "08:30", end = "11:50", intervalMinutes = 10)),
            dailyTimes = listOf(ScheduleDailyTime(at = "07:00", tag = "起床确认")),
        )
        // 06:00：窗口要等到 08:30，定时点 07:00 更近 → 取定时点
        val fire = ScheduleTimePlanner.nextFire(t, at(20, 6, 0))
        assertEquals("07:00", hhmm(fire.atMillis))
        assertEquals("起床确认", fire.tag)
    }

    // ---- 星期过滤 ----

    @Test
    fun `window day filter skips excluded weekday`() {
        // 2026-08-23 是周日（ISO 7）；午自习只配周一到周六
        val t = template(
            windows = listOf(
                ScheduleWindow(
                    name = "午自习",
                    days = listOf(1, 2, 3, 4, 5, 6),
                    start = "14:00", end = "17:50", intervalMinutes = 10,
                )
            )
        )
        // 周日 15:00 → 不在窗口内，应排到周一 14:00
        val fire = ScheduleTimePlanner.nextFire(t, at(23, 15, 0))
        val cal = Calendar.getInstance().apply { timeInMillis = fire.atMillis }
        assertEquals("14:00", hhmm(fire.atMillis))
        assertEquals(24, cal.get(Calendar.DAY_OF_MONTH)) // 周一
        assertNull(ScheduleTimePlanner.isWithinAnyWindow(t, at(23, 15, 0)))
    }

    // ---- 跨午夜窗口 ----

    @Test
    fun `cross midnight window computes ticks correctly`() {
        val w = ScheduleWindow(name = "夜猫", start = "23:30", end = "01:00", intervalMinutes = 30)
        assertTrue(w.crossesMidnight)
        assertEquals(90, w.durationMinutes)

        val t = template(windows = listOf(w))
        // 23:40（窗口内，今天开场）→ 下一格 00:00
        assertEquals("00:00", hhmm(ScheduleTimePlanner.nextFire(t, at(20, 23, 40)).atMillis))
        // 00:20（属于昨天开场的那一场）→ 下一格 00:30
        val early = ScheduleTimePlanner.nextFire(t, at(21, 0, 20))
        assertEquals("00:30", hhmm(early.atMillis))
        assertEquals("夜猫", early.windowName)
        assertNotNull(ScheduleTimePlanner.isWithinAnyWindow(t, at(21, 0, 20)))
    }

    // ---- 放行判定 ----

    @Test
    fun `resolveTrigger blocks outside windows and passes inside`() {
        val t = template(windows = listOf(ScheduleWindow(name = "早自习", start = "08:30", end = "11:50", intervalMinutes = 10)))
        assertNull(ScheduleTimePlanner.resolveTrigger(t, at(20, 12, 30)))
        val ctx = ScheduleTimePlanner.resolveTrigger(t, at(20, 9, 0))
        assertNotNull(ctx)
        assertEquals("早自习", ctx!!.windowName)
    }

    @Test
    fun `resolveTrigger honors daily time grace period`() {
        val t = template(dailyTimes = listOf(ScheduleDailyTime(at = "08:30", tag = "起床确认")))
        // 正点命中
        assertEquals("起床确认", ScheduleTimePlanner.resolveTrigger(t, at(20, 8, 30))!!.tag)
        // Doze 推迟 3 分钟仍命中
        assertEquals("起床确认", ScheduleTimePlanner.resolveTrigger(t, at(20, 8, 33))!!.tag)
        // 推迟超过容差 → 不放行
        assertNull(ScheduleTimePlanner.resolveTrigger(t, at(20, 8, 40)))
    }

    @Test
    fun `no windows and no daily times falls back to interval and always passes`() {
        val t = template(intervalMinutes = 20)
        val now = at(20, 9, 13, 47)
        assertEquals(now + 20 * 60_000L, ScheduleTimePlanner.nextFire(t, now).atMillis)
        assertNotNull(ScheduleTimePlanner.resolveTrigger(t, now))
    }

    // ---- 脏输入不炸 ----

    @Test
    fun `malformed time strings degrade gracefully`() {
        assertEquals(0, parseHhMm(null))
        assertEquals(0, parseHhMm(""))
        assertEquals(0, parseHhMm("garbage"))
        assertEquals(8 * 60, parseHhMm("08"))
        assertEquals(8 * 60 + 30, parseHhMm(" 08:30 "))
        // 越界值被 clamp，不抛异常
        assertEquals(23 * 60 + 59, parseHhMm("99:99"))
    }

    @Test
    fun `zero interval does not hang`() {
        val t = template(windows = listOf(ScheduleWindow(start = "08:30", end = "11:50", intervalMinutes = 0)))
        val fire = ScheduleTimePlanner.nextFire(t, at(20, 9, 0, 30))
        // interval 被 coerce 到 1 分钟
        assertEquals("09:01", hhmm(fire.atMillis))
    }
}
