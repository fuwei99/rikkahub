package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun buildSetAlarmTool(context: Context): Tool = Tool(
    name = "set_alarm",
    description = """
        Open the system Clock app to create an alarm. Use this for wake-up alarms or strong reminders.
        Android may require the user to confirm the alarm in the Clock app; do not assume it was silently created.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("hour", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hour in 24-hour time, 0-23")
                })
                put("minute", buildJsonObject {
                    put("type", "integer")
                    put("description", "Minute, 0-59")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "Alarm label/message")
                })
                put("skip_ui", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Request silently creating the alarm. Many Android versions/ROMs ignore this; default false.")
                })
                put("days", buildJsonObject {
                    put("type", "array")
                    put("description", "Optional repeat days as integers from java.util.Calendar: 1=Sunday, 2=Monday, ..., 7=Saturday")
                })
            },
            required = listOf("hour", "minute"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val hour = obj["hour"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 23)
            ?: error("hour is required")
        val minute = obj["minute"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 59)
            ?: error("minute is required")
        val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "RikkaHub"
        val skipUi = obj["skip_ui"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val days = obj["days"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { day -> day in 1..7 } }
            ?.distinct()
            .orEmpty()

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (days.isNotEmpty()) {
                putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.getOrElse {
            error("Failed to open system Clock app: ${it.message ?: it}")
        }
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("opened", true)
                    put("hour", hour)
                    put("minute", minute)
                    put("message", message)
                    put("skip_ui_requested", skipUi)
                    put("note", "The system Clock app was opened. The user may need to confirm the alarm.")
                }.toString()
            )
        )
    },
)

internal fun buildShowAlarmsTool(context: Context): Tool = Tool(
    name = "show_alarms",
    description = "Open the system Clock app alarm list so the user can view, edit, enable, disable, or delete alarms.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.getOrElse {
            error("Failed to open system Clock app alarms screen: ${it.message ?: it}")
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("opened", true) }.toString()))
    },
)
