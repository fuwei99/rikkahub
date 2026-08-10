package me.rerere.rikkahub.data.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存按钮 + 是否立即开始监督」相关逻辑的回归测试。
 *
 * 场景来源：时段还没配完，勾一下总开关就立刻锁机。修复方式是 UI 走草稿 +
 * [SupervisionSettings.deferUntil]（跳过本段）。
 */
class SupervisionDeferTest {

    private val tz = TimeZone.currentSystemDefault()

    /** 造一个「本地时间 today 16:57」的时刻，today 取当前日期，保证时区/夏令时一致。 */
    private fun localAt(hour: Int, minute: Int): Long {
        val today = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            .toLocalDateTime(tz).date
        return LocalDateTime(
            year = today.year,
            monthNumber = today.monthNumber,
            dayOfMonth = today.day,
            hour = hour,
            minute = minute,
        ).toInstant(tz).toEpochMilliseconds()
    }

    private fun dowOf(epochMillis: Long): Int =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz).dayOfWeek.isoDayNumber

    /** 午自习 14:00-17:50，覆盖当天。 */
    private fun afternoonSession(epochMillis: Long) = SupervisionSchedule(
        daysOfWeek = setOf(dowOf(epochMillis)),
        startMinute = 14 * 60,
        endMinute = 17 * 60 + 50,
    )

    @Test
    fun `命中时段时 currentSessionEndAt 返回本段结束时刻`() {
        val now = localAt(16, 57)
        val sup = SupervisionSettings(
            enabled = true,
            schedules = listOf(afternoonSession(now)),
        )
        assertEquals(localAt(17, 50), sup.currentSessionEndAt(now))
    }

    @Test
    fun `未命中时段时 currentSessionEndAt 返回 null`() {
        val now = localAt(13, 0)
        val sup = SupervisionSettings(
            enabled = true,
            schedules = listOf(afternoonSession(now)),
        )
        assertNull(sup.currentSessionEndAt(now))
    }

    @Test
    fun `选择立即开始 deferUntil 为 0 时段内即刻锁定`() {
        val now = localAt(16, 57)
        val sup = SupervisionSettings(
            enabled = true,
            schedules = listOf(afternoonSession(now)),
            deferUntil = 0L,
        )
        assertTrue(sup.isActiveAt(Instant.fromEpochMilliseconds(now)))
    }

    @Test
    fun `选择下一段再说 本段内不锁 到点后恢复锁定`() {
        val now = localAt(16, 57)
        val sessionEnd = localAt(17, 50)
        val sup = SupervisionSettings(
            enabled = true,
            schedules = listOf(
                afternoonSession(now),
                // 晚自习 18:40-22:00，用于验证「下一段照样锁」
                SupervisionSchedule(
                    daysOfWeek = setOf(dowOf(now)),
                    startMinute = 18 * 60 + 40,
                    endMinute = 22 * 60,
                ),
            ),
            deferUntil = sessionEnd,
        )

        // 本段（午自习）内：宽限，不锁
        assertFalse(sup.isActiveAt(Instant.fromEpochMilliseconds(now)))
        assertFalse(sup.isActiveAt(Instant.fromEpochMilliseconds(localAt(17, 49))))
        // 下一段（晚自习）：deferUntil 已过期，照样锁
        assertTrue(sup.isActiveAt(Instant.fromEpochMilliseconds(localAt(19, 0))))
    }

    @Test
    fun `deferUntil 只许变小 任一侧为0则不延后`() {
        val early = localAt(17, 50)
        val late = localAt(22, 0)
        val a = SupervisionSettings(enabled = true, deferUntil = late)
        val b = SupervisionSettings(enabled = true, deferUntil = early)
        // strengthenWith 取更早结束宽限 = 更严
        assertEquals(early, a.strengthenWith(b).deferUntil)
        // 任一侧为 0（不延后 = 最严）则结果为 0
        assertEquals(0L, a.strengthenWith(SupervisionSettings(deferUntil = 0L)).deferUntil)
    }

    @Test
    fun `未启用总开关时 deferUntil 与时段都不生效`() {
        val now = localAt(16, 57)
        val sup = SupervisionSettings(
            enabled = false,
            schedules = listOf(afternoonSession(now)),
        )
        assertFalse(sup.isActiveAt(Instant.fromEpochMilliseconds(now)))
    }
}
