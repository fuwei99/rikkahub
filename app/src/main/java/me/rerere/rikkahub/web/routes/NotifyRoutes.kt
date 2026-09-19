package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.ToastLevel
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.dto.NotifyToastRequest
import me.rerere.rikkahub.web.dto.NotifyToastResponse
import kotlin.uuid.Uuid

/** 时长护栏：0 = 常驻；上限 10 分钟，防外部把屏幕糊死。 */
private const val MIN_DURATION_MS = 0L
private const val MAX_DURATION_MS = 10 * 60 * 1000L

/**
 * 设备提示接口（2026-09-19）。
 *
 * 和 `notify_toast` 工具是**同一个事件的两个入口**：
 *
 * | 通道 | 谁在用 | 依赖 |
 * |---|---|---|
 * | `notify_toast` 工具 | 应用内 agent | 无（直投 eventBus） |
 * | `POST /api/notify/toast` | workspace shell curl / 对端设备走隧道 | web server 必须开着 |
 *
 * 加这条 HTTP 路的意义：让**没有开这个工具**的调用方（比如 workspace 里的一段
 * 脚本、或者另一台设备）也能往这块屏上弹东西，不用为了弹一句话去开一个 agent
 * 工具开关。
 *
 * 注意：**通用工具通道 `/api/tools/call` 也能调 `notify_toast`**（见 ToolRoutes）。
 * 这条专用路保留是为了「一条 curl 就弹字」的最短路径，不用先查 schema。
 *
 * ## 端点
 *
 * `POST /api/notify/toast`
 * ```json
 * { "text": "构建完成", "title": "CI", "duration_ms": 4000, "level": "success" }
 * ```
 *
 * ## 鉴权
 *
 * 设备桥独立 Bearer，见 [requireDeviceBridgeToken]。token 为空 → 整个接口 403 关闭。
 *
 * ## 安全边界
 *
 * 拿到 token 的人能在你屏幕上弹字。别把它暴露在不可信网络里，泄露了就换。
 */
fun Route.notifyRoutes(
    eventBus: AppEventBus,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/notify") {
        post("/toast") {
            call.requireDeviceBridgeToken(advancedConfigStore, "notify")

            val request = call.receive<NotifyToastRequest>()
            val text = request.text.trim()
            if (text.isEmpty()) {
                throw BadRequestException("text is required")
            }

            val duration = request.durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            val toastId = Uuid.random().toString()
            val expireAt = if (duration <= 0L) 0L else System.currentTimeMillis() + duration

            eventBus.emit(
                AppEvent.ToastPending(
                    toastId = toastId,
                    text = text,
                    title = request.title.trim().takeIf { it.isNotEmpty() },
                    level = ToastLevel.normalize(request.level),
                    expireAt = expireAt,
                    source = request.source.trim().takeIf { it.isNotEmpty() } ?: "api",
                )
            )

            call.respond(
                HttpStatusCode.OK,
                NotifyToastResponse(accepted = true, toastId = toastId, expireAt = expireAt),
            )
        }
    }
}
