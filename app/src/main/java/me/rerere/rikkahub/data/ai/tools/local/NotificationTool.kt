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

internal fun buildSendNotificationTool(context: Context): Tool = Tool(
    name = "send_notification",
    description = "Send a system notification to the Android device. Use this to notify the user when a task finishes or an important event occurs.",
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
            },
            required = listOf("title", "message"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "RikkaHub Notification"
        val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: ""

        val channelId = "rikkahub_agent_notifications"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Agent Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "System notifications triggered by RikkaHub AI Agent"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        runCatching {
            notificationManager.notify(notificationId, builder.build())
        }.getOrElse {
            error("Failed to post notification: ${it.message ?: it}")
        }

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("posted", true)
                    put("title", title)
                    put("message", message)
                    put("notification_id", notificationId)
                }.toString()
            )
        )
    },
)
