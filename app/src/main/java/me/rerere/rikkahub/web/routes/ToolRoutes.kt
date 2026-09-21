package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tools.local.RemoteToolContext
import me.rerere.rikkahub.data.ai.tools.local.RemoteToolRegistry
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.dto.RemoteToolCallRequest
import me.rerere.rikkahub.web.dto.RemoteToolCallResponse
import me.rerere.rikkahub.web.dto.RemoteToolListResponse
import kotlin.uuid.Uuid

/**
 * 远程工具调用接口（2026-09-19）。
 *
 * ## 为什么要有它
 *
 * 原来每个能力都得开一条专用路（`/api/shell` 执行、`/api/notify/toast` 弹提示），
 * 加一个能力改一次路由 + 一次 DTO + 一次调用方。而且监督锁走的是 D1 云同步，
 * **从平板锁手机要等好几分钟**；局域网直连是毫秒级。
 *
 * 现在收敛成一条：
 * - `GET  /api/tools`      —— 查清单（名字、说明、参数 schema）
 * - `POST /api/tools/call` —— 发：`{name, arguments, context?}`
 *
 * 加新工具只要往 [RemoteToolRegistry] 的白名单里注册，调用方零改动。
 *
 * ## 典型用法
 *
 * ```bash
 * # 1. 看看有哪些工具、supervision 的参数长什么样
 * curl -s -H "Authorization: Bearer $TOKEN" http://<手机局域网IP>:8080/api/tools | jq
 *
 * # 2. 立刻锁住某个对话（不用等 D1 同步）
 * curl -s -X POST http://<手机局域网IP>:8080/api/tools/call \
 *   -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
 *   -d '{"name":"supervision_admin",
 *        "arguments":{"action":"lock_conversation","conversation_id":"<uuid>","reason":"去学习"},
 *        "context":{"conversation_id":"<uuid>"}}'
 *
 * # 3. 查屏幕时间
 * curl -s -X POST .../api/tools/call \
 *   -d '{"name":"get_screen_time","arguments":{"range":"today"}}'
 * ```
 *
 * ## 鉴权
 *
 * 设备桥独立 Bearer（见 [requireDeviceBridgeToken]），token 空即整条路 403。
 *
 * ## 安全边界（重要）
 *
 * 这条路等于**把设备控制权交给 token 持有人**：能锁对话、锁路径、关无障碍、
 * 读剪贴板。**只在可信局域网 / 隧道里开**，token 泄露立刻换。
 * 危险工具（任意代码执行、开对话、改工具开关）**不在白名单里**，见
 * [RemoteToolRegistry] 的说明。
 */
fun Route.toolRoutes(
    registry: RemoteToolRegistry,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/tools") {
        /** 查清单。调用方靠它自描述参数，不用硬编码。 */
        get {
            call.requireDeviceBridgeToken(advancedConfigStore, "tools")
            call.respond(
                HttpStatusCode.OK,
                RemoteToolListResponse(
                    tools = registry.list(),
                    // 把 `[Environment Context: ...]` 一并交出去：调用方靠它知道挂了哪些
                    // 目录、相对路径基准在哪、哪些可写 —— 否则 workspace_* 的路径只能瞎猜，
                    // 写到一个不存在的路径上还以为是工具坏了。
                    environment = registry.environment(),
                ),
            )
        }

        /** 发：调一个工具。 */
        post("/call") {
            call.requireDeviceBridgeToken(advancedConfigStore, "tools")

            val request = call.receive<RemoteToolCallRequest>()
            if (request.name.isBlank()) {
                throw BadRequestException("name is required")
            }

            val ctx = RemoteToolContext(
                conversationId = request.context?.conversationId
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parseUuidOrThrow(it, "context.conversation_id") },
                assistantId = request.context?.assistantId
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parseUuidOrThrow(it, "context.assistant_id") },
                // 注意：工作区 id 是字符串（注册 id），不是 uuid —— 不能走 parseUuidOrThrow。
                workspaceId = request.context?.workspaceId
                    ?.trim()?.takeIf { it.isNotEmpty() },
            )

            val result = try {
                registry.call(
                    name = request.name.trim(),
                    arguments = request.arguments ?: JsonObject(emptyMap()),
                    ctx = ctx,
                )
            } catch (e: IllegalArgumentException) {
                // 未知工具 / 缺上下文 —— 是调用方的输入问题，别冒成 500
                throw BadRequestException(e.message ?: "invalid tool call")
            }

            call.respond(
                HttpStatusCode.OK,
                RemoteToolCallResponse(
                    ok = result.ok,
                    name = request.name.trim(),
                    durationMs = result.durationMs,
                    text = result.text,
                ),
            )
        }
    }
}

private fun parseUuidOrThrow(raw: String, field: String): Uuid =
    runCatching { Uuid.parse(raw) }.getOrElse {
        throw BadRequestException("$field 不是合法 uuid: $raw")
    }
