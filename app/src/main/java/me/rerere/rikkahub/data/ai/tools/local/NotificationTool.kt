package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

private val taskTimers = ConcurrentHashMap<String, Long>()

internal fun buildSendNotificationTool(context: Context): Tool = Tool(
    name = "send_notification",
    description = "Send a system notification or manage scheduled reminders. Actions: notify (default), start_timer, stop_timer, schedule, list, cancel, toggle.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: notify (default), start_timer, stop_timer, schedule, list, cancel, toggle")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification message")
                })
                put("task_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of task for timers")
                })
                put("time", buildJsonObject {
                    put("type", "string")
                    put("description", "Trigger time for schedule e.g. '2026-07-27 22:00', '22:00', or '30m'")
                })
                put("repeat", buildJsonObject {
                    put("type", "string")
                    put("description", "Repeat rule: 'daily', 'weekly', 'weekly:1,2,3,4,5' (1=Monday..7=Sunday, ranges like 1-5 allowed), 'weekdays', 'weekends', or null/empty for single trigger")
                })
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Scheduled notification ID for cancel/toggle")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Target enabled state for toggle action")
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "notify"
        val taskName = obj["task_name"]?.jsonPrimitive?.contentOrNull ?: "后台任务"
        val titleInput = obj["title"]?.jsonPrimitive?.contentOrNull
        val messageInput = obj["message"]?.jsonPrimitive?.contentOrNull
        val timeInput = obj["time"]?.jsonPrimitive?.contentOrNull
        val repeatInput = obj["repeat"]?.jsonPrimitive?.contentOrNull
        val targetId = obj["id"]?.jsonPrimitive?.intOrNull

        when (action) {
            "schedule" -> {
                val title = titleInput ?: "AI 定时提醒"
                val message = messageInput ?: ""
                val timeMs = timeInput?.let { parseScheduledTime(it) } ?: (System.currentTimeMillis() + 10 * 60 * 1000L)
                val item = ScheduledNotificationManager.addSchedule(context, title, message, timeMs, repeatInput)

                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", "schedule")
                            put("id", item.id)
                            put("title", item.title)
                            put("message", item.message)
                            put("time_formatted", item.timeFormatted)
                            put("repeat", item.repeatRule)
                        }.toString()
                    )
                )
            }
            "list" -> {
                val items = ScheduledNotificationManager.getItems(context)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", "list")
                            put("count", items.size)
                            put("items", buildJsonArray {
                                items.forEach { item ->
                                    add(buildJsonObject {
                                        put("id", item.id)
                                        put("title", item.title)
                                        put("message", item.message)
                                        put("time_formatted", item.timeFormatted)
                                        put("repeat", item.repeatRule)
                                        put("enabled", item.enabled)
                                    })
                                }
                            })
                        }.toString()
                    )
                )
            }
            "cancel" -> {
                val id = targetId ?: error("id is required for cancel action")
                val success = ScheduledNotificationManager.removeSchedule(context, id)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", success)
                            put("action", "cancel")
                            put("id", id)
                        }.toString()
                    )
                )
            }
            "toggle" -> {
                val id = targetId ?: error("id is required for toggle action")
                val enabled = obj["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val success = ScheduledNotificationManager.toggleSchedule(context, id, enabled)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", success)
                            put("action", "toggle")
                            put("id", id)
                            put("enabled", enabled)
                        }.toString()
                    )
                )
            }
            "start_timer" -> {
                taskTimers[taskName] = System.currentTimeMillis()
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", "start_timer")
                            put("task_name", taskName)
                            put("start_time_ms", taskTimers[taskName]!!)
                        }.toString()
                    )
                )
            }
            "stop_timer" -> {
                val startTime = taskTimers.remove(taskName)
                val durationText = if (startTime != null) {
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                    if (elapsedSec < 60) String.format("%.1f秒", elapsedSec)
                    else String.format("%d分%d秒", (elapsedSec / 60).toInt(), (elapsedSec % 60).toInt())
                } else null

                val title = titleInput ?: "任务完成"
                val message = messageInput ?: if (durationText != null) {
                    "任务 [$taskName] 已完成，用时: $durationText"
                } else {
                    "任务 [$taskName] 已完成"
                }

                postNotification(context, title, message)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", "stop_timer")
                            put("task_name", taskName)
                            if (durationText != null) put("duration", durationText)
                            put("posted_notification", true)
                        }.toString()
                    )
                )
            }
            else -> {
                val title = titleInput ?: "RikkaHub Notification"
                val message = messageInput ?: ""
                postNotification(context, title, message)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", "notify")
                            put("title", title)
                            put("message", message)
                            put("posted_notification", true)
                        }.toString()
                    )
                )
            }
        }
    },
)

internal fun postNotification(context: Context, title: String, message: String) {
    val channelId = "rikkahub_agent_high_priority_v3"
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "Agent 系统通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "RikkaHub 紧急系统通知与任务完成提醒"
            enableVibration(false)
            enableLights(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    val notificationId = (System.currentTimeMillis() % 100000).toInt()
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)

    runCatching {
        notificationManager.notify(notificationId, builder.build())
    }.getOrElse {
        it.printStackTrace()
    }
}
