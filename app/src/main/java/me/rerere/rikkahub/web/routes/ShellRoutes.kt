package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.shizuku.ShellMode
import me.rerere.rikkahub.data.shizuku.ShellRunner
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ForbiddenException
import me.rerere.rikkahub.web.ServiceUnavailableException
import me.rerere.rikkahub.web.UnauthorizedException
import me.rerere.rikkahub.web.dto.ShellExecRequest
import me.rerere.rikkahub.web.dto.ShellExecResponse
import me.rerere.rikkahub.web.dto.ShellStatusResponse
import java.security.MessageDigest

/**
 * Shizuku shell 桥（2026-09-19）。
 *
 * ## 为什么有这东西
 *
 * workspace 里那个 shell 是应用进程的子进程，uid 就是应用 uid，看不见 `/system`、
 * 摸不到 binder，**从 shell 侧直接要 ADB 权限是死路**。所以改成：应用自己通过
 * Shizuku 拿到 shell(uid 2000) 权限，再开一条受控的 HTTP 口子把能力吐出去。
 *
 * ## 模式（重要）
 *
 * **默认 `local`，不走 ADB。** 这台设备上 Shizuku 每次重启都要重新配对，大部分
 * 时候其实不可用，所以默认档必须是不依赖它的那条路。要用 ADB 就显式传 `mode`：
 *
 * | mode | 行为 |
 * |---|---|
 * | `local`（默认） | 应用自身 uid，永远可用 |
 * | `shizuku` | shell(uid 2000)；**没配对/没授权 → 直接 503，不静默降级** |
 * | `auto` | 能用 Shizuku 就用，否则本地 |
 *
 * 响应体里的 `mode` 是**实际**用上的模式，别拿请求参数当结果。
 *
 * ## 鉴权
 *
 * **独立 Bearer key**，与 web JWT 开关完全解耦（照抄 externalDeliveryRoutes 那套）：
 * - key 取自 [SyncAdvancedConfigStore] 的 `shellBridgeToken`（设备本地 JSON，不上云）
 * - `shellBridgeEnabled = false` 或 token 为空 → 整个接口 403 关闭
 * - 常量时间比较，防时序侧信道
 *
 * ## 端点
 *
 * - `GET  /api/shell/status` → Shizuku 可用性 + 建议模式
 * - `POST /api/shell`        → `{"command": "...", "mode": "local", "timeoutMs": 30000}`
 *
 * ## 安全边界
 *
 * `shizuku` 模式等于把 shell(2000) 交出去，能改系统设置、装/卸应用。**只在信任的
 * 网络里开**：建议配合 web server 的「仅本机模式」，token 一旦泄露立刻换。
 */
fun Route.shellRoutes(
    shellRunner: ShellRunner,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/shell") {
        get("/status") {
            call.requireShellBridgeToken(advancedConfigStore)

            val binderAlive = shellRunner.shizukuBinderAlive()
            val granted = shellRunner.shizukuPermissionGranted()
            val ready = binderAlive && granted

            call.respond(
                HttpStatusCode.OK,
                ShellStatusResponse(
                    shizukuBinderAlive = binderAlive,
                    shizukuPermissionGranted = granted,
                    shizukuReady = ready,
                    localReady = true,
                    recommendedMode = if (ready) ShellMode.SHIZUKU.id else ShellMode.LOCAL.id,
                ),
            )
        }

        post {
            call.requireShellBridgeToken(advancedConfigStore)

            val request = call.receive<ShellExecRequest>()
            if (request.command.isBlank()) {
                throw BadRequestException("command is required")
            }

            val mode = ShellMode.parse(request.mode)
                ?: throw BadRequestException("未知 mode: ${request.mode}（只认 local / shizuku / auto）")

            val timeout = (request.timeoutMs ?: ShellRunner.DEFAULT_TIMEOUT_MS)
                .coerceIn(ShellRunner.MIN_TIMEOUT_MS, ShellRunner.MAX_TIMEOUT_MS)

            val result = shellRunner.exec(request.command, mode, timeout)

            // 明确点了 ADB 但 Shizuku 没起来 → 报错，不静默降级成 local
            if (mode == ShellMode.SHIZUKU && result.exitCode == ShellRunner.UNAVAILABLE_EXIT_CODE) {
                throw ServiceUnavailableException(result.stderr)
            }

            call.respond(
                HttpStatusCode.OK,
                ShellExecResponse(
                    mode = result.mode.id,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    durationMs = result.durationMs,
                ),
            )
        }
    }
}

private fun ApplicationCall.requireShellBridgeToken(store: SyncAdvancedConfigStore) {
    val config = store.current
    if (!config.shellBridgeEnabled || config.shellBridgeToken.isBlank()) {
        throw ForbiddenException("shell 桥未启用（shellBridgeEnabled=false 或 token 为空）")
    }

    val header = request.headers[HttpHeaders.Authorization]
    val bearer = header
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.trim()

    if (bearer.isNullOrEmpty() || !secureEquals(bearer, config.shellBridgeToken)) {
        throw UnauthorizedException("无效的 shell 桥 token")
    }
}

private const val BEARER_PREFIX = "Bearer "

private fun secureEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
