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
import me.rerere.rikkahub.data.shizuku.ShizukuShell
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ForbiddenException
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
 * ## 鉴权
 *
 * **独立 Bearer key**，与 web JWT 开关完全解耦（照抄 externalDeliveryRoutes 那套）：
 * - key 取自 [SyncAdvancedConfigStore] 的 `shellBridgeToken`（设备本地 JSON，不上云）
 * - `shellBridgeEnabled = false` 或 token 为空 → 整个接口 403 关闭
 * - 常量时间比较，防时序侧信道
 *
 * 之所以不复用 web JWT：workspace 侧要拿 JWT 得先知道 web 访问密码，绕一圈还多一份
 * 凭证要管；一条专用的、随时可以单独吊销的 token 更干净。
 *
 * ## 端点
 *
 * - `GET  /api/shell/status` → Shizuku 可用性
 * - `POST /api/shell`        → 执行命令，body `{"command": "...", "timeoutMs": 30000}`
 *
 * ## 安全边界
 *
 * 这条口子等于把 shell(2000) 交出去，能干的包括但不限于读其他应用数据目录之外的
 * 绝大多数系统状态、改系统设置、装/卸应用。**只在信任的网络里开**：建议配合
 * web server 的「仅本机模式」，且 token 一旦泄露立刻换。
 */
fun Route.shellRoutes(
    shizukuShell: ShizukuShell,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/shell") {
        get("/status") {
            call.requireShellBridgeToken(advancedConfigStore)
            val binderAlive = shizukuShell.isBinderAlive()
            val granted = shizukuShell.isPermissionGranted()
            call.respond(
                HttpStatusCode.OK,
                ShellStatusResponse(
                    binderAlive = binderAlive,
                    permissionGranted = granted,
                    ready = binderAlive && granted,
                ),
            )
        }

        post {
            call.requireShellBridgeToken(advancedConfigStore)

            val request = call.receive<ShellExecRequest>()
            val command = request.command
            if (command.isBlank()) {
                throw BadRequestException("command is required")
            }

            val timeout = (request.timeoutMs ?: ShizukuShell.DEFAULT_TIMEOUT_MS)
                .coerceIn(ShizukuShell.MIN_TIMEOUT_MS, ShizukuShell.MAX_TIMEOUT_MS)

            val result = shizukuShell.exec(command, timeout)
            call.respond(
                HttpStatusCode.OK,
                ShellExecResponse(
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
