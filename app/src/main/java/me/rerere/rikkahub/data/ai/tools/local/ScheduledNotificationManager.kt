package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class ScheduledNotificationItem(
    val id: Int,
    val title: String,
    val message: String,
    val timeMs: Long,
    val timeFormatted: String,
    val repeatRule: String? = null, // "daily", "weekly", null
    val enabled: Boolean = true,
)

object ScheduledNotificationManager {
    private const val PREF_NAME = "scheduled_notifications_pref"
    private const val KEY_ITEMS = "scheduled_items"
    private val json = Json { ignoreUnknownKeys = true }

    fun getItems(context: Context): List<ScheduledNotificationItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ScheduledNotificationItem>>(jsonStr) }.getOrDefault(emptyList())
    }

    private fun saveItems(context: Context, items: List<ScheduledNotificationItem>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(items)).apply()
    }

    fun addSchedule(
        context: Context,
        title: String,
        message: String,
        timeMs: Long,
        repeatRule: String? = null,
    ): ScheduledNotificationItem {
        val id = (System.currentTimeMillis() % 1000000).toInt()
        val timeFormatted = formatTime(timeMs)
        val item = ScheduledNotificationItem(
            id = id,
            title = title,
            message = message,
            timeMs = timeMs,
            timeFormatted = timeFormatted,
            repeatRule = repeatRule,
            enabled = true,
        )
        val items = getItems(context).filterNot { it.id == id } + item
        saveItems(context, items)
        scheduleAlarm(context, item)
        return item
    }

    fun removeSchedule(context: Context, id: Int): Boolean {
        val items = getItems(context)
        val item = items.find { it.id == id } ?: return false
        cancelAlarm(context, item)
        saveItems(context, items.filterNot { it.id == id })
        return true
    }

    fun rescheduleAll(context: Context) {
        val items = getItems(context).filter { it.enabled && it.timeMs > System.currentTimeMillis() }
        items.forEach { scheduleAlarm(context, it) }
    }

    fun toggleSchedule(context: Context, id: Int, enabled: Boolean): Boolean {
        val items = getItems(context)
        val item = items.find { it.id == id } ?: return false
        val updated = item.copy(enabled = enabled)
        saveItems(context, items.map { if (it.id == id) updated else it })
        if (enabled) scheduleAlarm(context, updated) else cancelAlarm(context, item)
        return true
    }

    fun handleFired(context: Context, id: Int, repeatRule: String?) {
        val items = getItems(context)
        val item = items.find { it.id == id } ?: return
        if (repeatRule == "daily") {
            val nextTimeMs = item.timeMs + 24 * 60 * 60 * 1000L
            val updated = item.copy(timeMs = nextTimeMs, timeFormatted = formatTime(nextTimeMs))
            saveItems(context, items.map { if (it.id == id) updated else it })
            scheduleAlarm(context, updated)
        } else if (repeatRule == "weekly") {
            val nextTimeMs = item.timeMs + 7 * 24 * 60 * 60 * 1000L
            val updated = item.copy(timeMs = nextTimeMs, timeFormatted = formatTime(nextTimeMs))
            saveItems(context, items.map { if (it.id == id) updated else it })
            scheduleAlarm(context, updated)
        } else {
            val updated = item.copy(enabled = false)
            saveItems(context, items.map { if (it.id == id) updated else it })
        }
    }

    private fun scheduleAlarm(context: Context, item: ScheduledNotificationItem) {
        if (!item.enabled) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, me.rerere.rikkahub.receiver.ScheduledNotificationReceiver::class.java).apply {
            action = "me.rerere.rikkahub.ACTION_SCHEDULED_NOTIFICATION"
            putExtra("id", item.id)
            putExtra("title", item.title)
            putExtra("message", item.message)
            putExtra("repeat", item.repeatRule)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, item.id, intent, flags)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.timeMs, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, item.timeMs, pendingIntent)
        }
    }

    private fun cancelAlarm(context: Context, item: ScheduledNotificationItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, me.rerere.rikkahub.receiver.ScheduledNotificationReceiver::class.java).apply {
            action = "me.rerere.rikkahub.ACTION_SCHEDULED_NOTIFICATION"
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, item.id, intent, flags)
        alarmManager.cancel(pendingIntent)
    }

    private fun formatTime(timeMs: Long): String {
        return runCatching {
            val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timeMs), ZoneId.systemDefault())
            ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }.getOrDefault(timeMs.toString())
    }
}

internal fun parseScheduledTime(timeInput: String): Long {
    val trimmed = timeInput.trim()
    val now = System.currentTimeMillis()

    if (trimmed.endsWith("m", ignoreCase = true)) {
        val mins = trimmed.dropLast(1).toLongOrNull() ?: 10
        return now + mins * 60 * 1000L
    }
    if (trimmed.endsWith("h", ignoreCase = true)) {
        val hours = trimmed.dropLast(1).toLongOrNull() ?: 1
        return now + hours * 3600 * 1000L
    }

    if (trimmed.matches(Regex("""^\d{1,2}:\d{2}$"""))) {
        val parts = trimmed.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) {
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    return runCatching {
        val ldt = LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrElse {
        now + 10 * 60 * 1000L
    }
}
