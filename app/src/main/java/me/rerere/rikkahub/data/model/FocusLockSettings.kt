package me.rerere.rikkahub.data.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Physical app-lock configuration, intentionally separate from LLM supervision rules. */
@Serializable
data class FocusLockSettings(
    val enabled: Boolean = false,
    val tasks: List<FocusLockTask> = emptyList(),
    /** Return to the launcher when an unapproved app reaches the foreground. */
    val returnHomeOnViolation: Boolean = true,
    /** Keep launcher/System UI usable; the accessibility service still catches the next app. */
    val allowLauncherAndSystemUi: Boolean = true,
    /** Additional package names supplied by the user. */
    val additionalAllowedPackages: Set<String> = emptySet(),
)

@Serializable
enum class FocusLockTaskMode {
    POMODORO,
    FIXED_WINDOW,
}

/** One user-created time window. Days use ISO numbering: Monday = 1 … Sunday = 7. */
@Serializable
data class FocusLockTask(
    val id: Uuid = Uuid.random(),
    val name: String = "番茄锁机",
    val enabled: Boolean = true,
    val daysOfWeek: Set<Int> = (1..5).toSet(),
    val startMinute: Int = 8 * 60 + 30,
    val endMinute: Int = 11 * 60 + 50,
    val mode: FocusLockTaskMode = FocusLockTaskMode.POMODORO,
    val workMinutes: Int = 45,
    val breakMinutes: Int = 10,
    /** 0 = repeat until the window ends. */
    val cycles: Int = 0,
    /** If true, the physical lock remains active during pomodoro breaks. */
    val lockDuringBreak: Boolean = false,
) {
    fun containsWindow(minuteOfDay: Int, isoDay: Int): Boolean {
        return if (startMinute <= endMinute) {
            isoDay in daysOfWeek && minuteOfDay in startMinute until endMinute
        } else if (minuteOfDay >= startMinute) {
            isoDay in daysOfWeek
        } else {
            val previousDay = if (isoDay == 1) 7 else isoDay - 1
            previousDay in daysOfWeek
        }
    }

    fun isActiveAt(epochMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled || startMinute == endMinute) return false
        val local = Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val minute = local.hour * 60 + local.minute
        if (!containsWindow(minute, local.dayOfWeek.isoDayNumber)) return false
        if (mode == FocusLockTaskMode.FIXED_WINDOW) return true

        val elapsed = if (startMinute <= endMinute) {
            minute - startMinute
        } else if (minute >= startMinute) {
            minute - startMinute
        } else {
            24 * 60 - startMinute + minute
        }
        val work = workMinutes.coerceAtLeast(1)
        val rest = breakMinutes.coerceAtLeast(0)
        val cycleLength = work + rest
        if (cycles > 0 && elapsed >= cycleLength * cycles) return false
        if (rest == 0) return true
        val phase = elapsed % cycleLength
        return phase < work || lockDuringBreak
    }
}

fun FocusLockSettings.isActiveAt(epochMillis: Long = System.currentTimeMillis()): Boolean =
    enabled && tasks.any { it.isActiveAt(epochMillis) }
