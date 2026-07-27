package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    description = "Send a system notification to the Android device. Supports standard alerts and task timers (action: start_timer / stop_timer).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification content message")
                })
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Action: 'notify' (default), 'start_timer', or 'stop_timer'")
                })
                put("task_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the task for timer tracking")
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

        when (action) {
            "start_timer" -> {
                taskTimers[taskName] = System.currentTimeMillis()
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", true)
                            put("action", "start_timer")
                            put("task_name", taskName)
                            put("start_time_ms", taskTimers[taskName]!!)
                            put("note", "Timer started for task [$taskName].")
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
            enableVibration(false) // 遵从用户指示：彻底关闭震动
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
        .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS) // 遵从系统免打扰/静音模式，绝不乱响，无震动
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)

    runCatching {
        notificationManager.notify(notificationId, builder.build())
    }.getOrElse {
        it.printStackTrace()
    }
}
