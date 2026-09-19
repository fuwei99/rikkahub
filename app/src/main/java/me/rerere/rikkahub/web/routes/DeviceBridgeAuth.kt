package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.ForbiddenException
import me.rerere.rikkahub.web.UnauthorizedException
import java.security.MessageDigest

/**
 * 设备桥（device bridge）的共享鉴权（2026-09-19）。
 *
 * 「设备桥」= 一组**设备本地**的 HTTP 接口，让 workspace shell / 对端设备能直接
 * 指挥这台机器：`/api/shell*`（执行）、`/api/notify/*`（弹提示）、`/api/tools*`
 * （调工具）。它们共同的特点：
 *
 * - **独立 Bearer key**，与 web JWT 完全解耦（workspace 侧不需要知道 web 访问密码）
 * - **常量时间比较**，防时序侧信道
 * - token 为空 → 接口整体 403 关闭
 *
 * v1 三组接口共用 `shellBridgeToken`（`SyncAdvancedConfigStore`，设备本地 JSON，
 * **不上云**）。刻意**不看** `shellBridgeEnabled`：那个开关是「要不要把 shell(2000)
 * 权限交出去」的安全闸，而弹提示 / 调工具不提权，是两件事。将来要分权，把
 * token 拆成几个字段即可，调用点不用动。
 */
internal const val DEVICE_BRIDGE_BEARER_PREFIX = "Bearer "

/**
 * 校验设备桥 Bearer token。
 *
 * @param label 出现在报错文案里的接口名（如 "notify" / "tools"），方便排障时一眼看出是哪条路。
 */
internal fun ApplicationCall.requireDeviceBridgeToken(
    store: SyncAdvancedConfigStore,
    label: String,
) {
    val token = store.current.shellBridgeToken
    if (token.isBlank()) {
        throw ForbiddenException("$label 接口未启用（shellBridgeToken 为空）")
    }

    val header = request.headers[HttpHeaders.Authorization]
    val bearer = header
        ?.takeIf { it.startsWith(DEVICE_BRIDGE_BEARER_PREFIX, ignoreCase = true) }
        ?.substring(DEVICE_BRIDGE_BEARER_PREFIX.length)
        ?.trim()

    if (bearer.isNullOrEmpty() || !constantTimeEquals(bearer, token)) {
        throw UnauthorizedException("无效的 $label 接口 token")
    }
}

/**
 * 常量时间字符串比较。
 *
 * 故意不叫 `secureEquals`：`ShellRoutes.kt` / `NotifyRoutes.kt` 里各有一份
 * 文件私有的同名函数，顶层 internal 与文件 private 同名同签名在部分 Kotlin
 * 版本下会报重复声明。换个名字，谁都不挡谁。
 */
internal fun constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
