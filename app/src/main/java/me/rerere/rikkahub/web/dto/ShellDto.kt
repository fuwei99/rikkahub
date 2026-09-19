package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable

/** 执行一条 shell 命令 */
@Serializable
data class ShellExecRequest(
    val command: String,

    /**
     * 执行模式，**默认 `local`（不走 ADB）**：
     * - `local`    —— 应用自身 uid，永远可用
     * - `shizuku`  —— shell(uid 2000)，需要 Shizuku 已配对启动并授权；不可用直接报 503
     * - `auto`     —— 能用 Shizuku 就用，否则本地
     *
     * 认别名：`adb`/`shell` → shizuku，`app`/`normal` → local。
     */
    val mode: String? = null,

    /** 可选，毫秒。会被夹到 [1s, 300s] */
    val timeoutMs: Long? = null,
)

@Serializable
data class ShellExecResponse(
    /** **实际**用上的模式，不是请求的模式。auto 时看这个才知道走没走 ADB */
    val mode: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

/** Shizuku 当前状态，给调用方判断能不能用 shizuku 模式 */
@Serializable
data class ShellStatusResponse(
    /** Shizuku 服务进程活着吗 */
    val shizukuBinderAlive: Boolean,
    /** 本应用在 Shizuku 里被授权了吗 */
    val shizukuPermissionGranted: Boolean,
    /** binder 通 + 已授权 = 可以用 shizuku 模式 */
    val shizukuReady: Boolean,
    /** 本地模式永远可用 */
    val localReady: Boolean = true,
    /** 建议用哪个模式：shizukuReady 为 true 时给 "shizuku"，否则 "local" */
    val recommendedMode: String,
)
