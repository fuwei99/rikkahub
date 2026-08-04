package me.rerere.rikkahub.data.ai.tools.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.core.BUNDLE_SCHEDULED_NOTIFICATIONS
import me.rerere.rikkahub.data.sync.core.SyncBundleEnqueuer
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
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
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
)

object ScheduledNotificationManager {
    private const val PREF_NAME = "scheduled_notifications_pref"
    private const val KEY_ITEMS = "scheduled_items"
    private val json = Json { ignoreUnknownKeys = true }

    fun getItems(context: Context): List<ScheduledNotificationItem> =
        getAllItems(context).filterNot { it.deleted }

    fun getAllItems(context: Context): List<ScheduledNotificationItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ScheduledNotificationItem>>(jsonStr) }.getOrDefault(emptyList())
    }

    private fun saveItems(context: Context, items: List<ScheduledNotificationItem>, enqueueSync: Boolean = true) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(items)).apply()
        if (enqueueSync && !SyncApplyGate.applyingRemote) {
            SyncBundleEnqueuer.enqueue(BUNDLE_SCHEDULED_NOTIFICATIONS)
        }
    }

    fun replaceFromSync(context: Context, remoteItems: List<ScheduledNotificationItem>) {
        val previous = getItems(context)
        previous.forEach { cancelAlarm(context, it) }
        val merged = mergeItems(local = getAllItems(context), remote = remoteItems)
        saveItems(context, merged, enqueueSync = false)
        merged
            .filterNot { it.deleted }
            .filter { it.enabled && it.timeMs > System.currentTimeMillis() }
            .forEach { scheduleAlarm(context, it) }
    }

    private fun mergeItems(
        local: List<ScheduledNotificationItem>,
        remote: List<ScheduledNotificationItem>,
    ): List<ScheduledNotificationItem> {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val ids = LinkedHashSet<Int>().apply {
            addAll(remote.map { it.id })
            addAll(local.map { it.id })
        }
        return ids.mapNotNull { id ->
            val l = localById[id]
            val r = remoteById[id]
            when {
                l == null -> r
                r == null -> l
                l.updatedAt > r.updatedAt -> l
                else -> r
            }
        }
    }

    fun addSchedule(
        context: Context,
        title: String,
        message: String,
        timeMs: Long,
        repeatRule: String? = null,
    ): ScheduledNotificationItem {
        val normalizedRule = normalizeRepeatRule(repeatRule)
        // 星期集合规则下，若初始时间落在非触发日，自动推进到最近的下一个触发日
        val alignedTimeMs = alignToRepeatRule(timeMs, normalizedRule)
        val id = (System.currentTimeMillis() % 1000000).toInt()
        val item = ScheduledNotificationItem(
            id = id,
            title = title,
            message = message,
            timeMs = alignedTimeMs,
            timeFormatted = formatTime(alignedTimeMs),
            repeatRule = normalizedRule,
            enabled = true,
            updatedAt = System.currentTimeMillis(),
        )
        val items = getItems(context).filterNot { it.id == id } + item
        saveItems(context, items)
        scheduleAlarm(context, item)
        return item
    }

    fun removeSchedule(context: Context, id: Int): Boolean {
        val items = getAllItems(context)
        val item = items.find { it.id == id && !it.deleted } ?: return false
        cancelAlarm(context, item)
        val tombstone = item.copy(enabled = false, deleted = true, updatedAt = System.currentTimeMillis())
        saveItems(context, items.map { if (it.id == id) tombstone else it })
        return true
    }

    fun rescheduleAll(context: Context) {
        val items = getItems(context).filter { it.enabled && it.timeMs > System.currentTimeMillis() }
        items.forEach { scheduleAlarm(context, it) }
    }

    fun toggleSchedule(context: Context, id: Int, enabled: Boolean): Boolean {
        val items = getItems(context)
        val item = items.find { it.id == id } ?: return false
        val updated = item.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        saveItems(context, items.map { if (it.id == id) updated else it })
        if (enabled) scheduleAlarm(context, updated) else cancelAlarm(context, item)
        return true
    }

    fun handleFired(context: Context, id: Int, repeatRule: String?) {
        val items = getItems(context)
        val item = items.find { it.id == id } ?: return
        val nextTimeMs = nextFireTimeMs(item.timeMs, repeatRule)
        if (nextTimeMs != null) {
            val updated = item.copy(
                timeMs = nextTimeMs,
                timeFormatted = formatTime(nextTimeMs),
                updatedAt = System.currentTimeMillis(),
            )
            saveItems(context, items.map { if (it.id == id) updated else it })
            scheduleAlarm(context, updated)
        } else {
            val updated = item.copy(enabled = false, updatedAt = System.currentTimeMillis())
            saveItems(context, items.map { if (it.id == id) updated else it })
        }
    }

    private fun scheduleAlarm(context: Context, item: ScheduledNotificationItem) {
        if (!item.enabled || item.deleted) return
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

    // ---- repeat 规则解析与推进 ----

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val WEEKDAYS_RULE = "weekly:1,2,3,4,5"
    private const val WEEKENDS_RULE = "weekly:6,7"

    /**
     * 归一化 repeat 规则，非法规则直接抛异常（agent 能立刻看到原因并修正）。
     * 支持：daily / weekly / weekly:1,2,3,4,5（1=周一 .. 7=周日，支持 1-5 范围写法）/ weekdays / weekends / null(单次)
     */
    internal fun normalizeRepeatRule(rule: String?): String? {
        if (rule == null) return null
        val trimmed = rule.trim().lowercase()
        if (trimmed.isEmpty()) return null
        return when (trimmed) {
            "daily", "weekly" -> trimmed
            "weekdays" -> WEEKDAYS_RULE
            "weekends" -> WEEKENDS_RULE
            else -> {
                if (trimmed.startsWith("weekly:") && parseWeekdaySet(trimmed) != null) {
                    trimmed
                } else {
                    throw IllegalArgumentException(
                        "非法 repeat 规则: '$rule'。支持: daily / weekly / weekly:1,2,3,4,5 (1=周一..7=周日，可写范围如 1-5) / weekdays / weekends / 留空(单次)"
                    )
                }
            }
        }
    }

    /** 解析 "weekly:1,2,3,4,5" / "weekly:1-5,7" 为星期集合，非法返回 null */
    private fun parseWeekdaySet(rule: String): Set<Int>? {
        if (!rule.startsWith("weekly:")) return null
        val days = mutableSetOf<Int>()
        rule.removePrefix("weekly:").split(',').forEach { token ->
            val t = token.trim()
            if (t.isEmpty()) return@forEach
            val range = t.split('-')
            if (range.size == 2) {
                val start = range[0].trim().toIntOrNull()
                val end = range[1].trim().toIntOrNull()
                if (start != null && end != null) {
                    for (d in minOf(start, end)..maxOf(start, end)) {
                        if (d in 1..7) days += d
                    }
                }
            } else {
                t.toIntOrNull()?.let { if (it in 1..7) days += it }
            }
        }
        return days.ifEmpty { null }
    }

    /** 计算下次触发时间；null 表示单次（触发后停用） */
    private fun nextFireTimeMs(currentMs: Long, rule: String?): Long? {
        if (rule == null) return null
        if (rule == "daily") return currentMs + DAY_MS
        if (rule == "weekly") return currentMs + 7 * DAY_MS
        val weekdays = parseWeekdaySet(rule)
        // 未知规则兜底按每周推进，避免历史脏数据突然停发
        if (weekdays == null) return currentMs + 7 * DAY_MS
        // 触发后推进必须严格晚于当前触发日，否则会原地重排立即再触发
        return advanceToWeekday(currentMs, weekdays, allowSameDay = false)
    }

    /** 星期集合规则下，把时间推进到集合内最近的一个触发日（同钟点） */
    private fun advanceToWeekday(timeMs: Long, weekdays: Set<Int>, allowSameDay: Boolean): Long? {
        val zoned = java.time.Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
        if (allowSameDay && zoned.dayOfWeek.value in weekdays) return timeMs
        for (offset in 1..7) {
            val day = zoned.toLocalDate().plusDays(offset.toLong()).dayOfWeek.value
            if (day in weekdays) {
                return zoned.toLocalDateTime()
                    .plusDays(offset.toLong())
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }
        }
        return null
    }

    /** 创建时的初始时间对齐：非每日/每周规则（星期集合）下，跳到最近的下一个触发日 */
    private fun alignToRepeatRule(timeMs: Long, rule: String?): Long {
        if (rule == null || rule == "daily" || rule == "weekly") return timeMs
        val weekdays = parseWeekdaySet(rule) ?: return timeMs
        // 创建时当天已在集合内则保留原时间
        return advanceToWeekday(timeMs, weekdays, allowSameDay = true) ?: timeMs
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
