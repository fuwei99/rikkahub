package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.ToastLevel
import kotlin.uuid.Uuid

/** 默认悬浮 4 秒。 */
private const val DEFAULT_DURATION_MS = 4_000L

/** 时长上下限。0 = 常驻（手动关），负值一律当 0。 */
private const val MIN_DURATION_MS = 0L
private const val MAX_DURATION_MS = 10 * 60 * 1000L

/**
 * notify_toast（2026-09-19）：在屏幕上「跳脸」弹一条浮层提示。
 *
 * ## 和 ask_user 的分工
 *
 * - `ask_user` = **阻塞式**人机回路：答案要投回生成流，发起的生成在干等。
 * - `notify_toast` = **单向**告知：弹一下就完事，不阻塞、不等回答、不需要人理。
 *
 * 别拿它当 ask_user 用，也别拿 ask_user 当它用。
 *
 * ## 为什么走 eventBus 直投，不绕 web server
 *
 * web server 可能根本没开。工具是「一直要用」的那个通道，不能依赖一个
 * 可关的服务。所以这里直接 `tryEmit`，落 [AppEvent.ToastPending]，由挂顶层
 * 的 [me.rerere.rikkahub.ui.components.chat.ToastHost] 接住。
 *
 * 外部（workspace shell / 对端设备）那条路走 `POST /api/notify/toast`，
 * 是**另一条通道**，两条都通到同一个事件上。
 *
 * ## 为什么用 tryEmit 而不是 emit
 *
 * `emit` 是挂起的，会反压调用方；而且 execute 的挂起语义不该被一个提示工具
 * 绑架。buffer 满（16）就丢，一个 toast 丢了就丢了，不值得阻塞生成。
 */
internal fun buildNotifyToastTool(eventBus: AppEventBus): Tool = Tool(
    name = "notify_toast",
    description = """
        Show a floating toast on the user's screen. Non-blocking: it does not wait for
        any answer and does not pause generation. Use it to surface a short heads-up
        while you keep working. For questions that need a real answer, use ask_user instead.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Toast body text. Keep it short — one or two lines.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional bold title line above the body")
                })
                put("duration_ms", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "How long it stays on screen, in milliseconds. " +
                            "0 = sticky until manually dismissed. Default $DEFAULT_DURATION_MS, " +
                            "clamped to [$MIN_DURATION_MS, $MAX_DURATION_MS]."
                    )
                })
                put("level", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add(ToastLevel.INFO)
                            add(ToastLevel.SUCCESS)
                            add(ToastLevel.WARN)
                            add(ToastLevel.ERROR)
                        }
                    )
                    put("description", "Visual accent. Default ${ToastLevel.INFO}.")
                })
            },
            required = listOf("text"),
        )
    },
    // 弹个提示无副作用，不该拦人审批。默认 needsApproval 语义见 Tool 定义；
    // 这里显式给 false，免得被 FORCE_USER_APPROVAL 那类硬名单误伤。
    needsApproval = { false },
    execute = { args ->
        val obj = args.jsonObject
        val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim()

        if (text.isNullOrEmpty()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "text is required") }.toString()
                )
            )
        }

        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val level = ToastLevel.normalize(obj["level"]?.jsonPrimitive?.contentOrNull)
        val rawDuration = obj["duration_ms"]?.jsonPrimitive?.longOrNull
            ?: obj["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong()
            ?: DEFAULT_DURATION_MS
        val duration = rawDuration.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        val toastId = Uuid.random().toString()
        val expireAt = if (duration <= 0L) 0L else System.currentTimeMillis() + duration

        val delivered = eventBus.tryEmit(
            AppEvent.ToastPending(
                toastId = toastId,
                text = text,
                title = title,
                level = level,
                expireAt = expireAt,
                source = "notify_toast",
            )
        )

        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", delivered)
                    put("toast_id", toastId)
                    put("duration_ms", duration)
                    put("expire_at", expireAt)
                    if (!delivered) {
                        put("note", "event bus buffer was full; the toast may not have been shown")
                    }
                }.toString()
            )
        )
    }
)
