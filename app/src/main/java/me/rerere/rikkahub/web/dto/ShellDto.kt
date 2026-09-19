package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable

/** 执行一条 shell 命令 */
@Serializable
data class ShellExecRequest(
    val command: String,
    /** 可选，毫秒。会被夹到 [1s, 300s] */
    val timeoutMs: Long? = null,
)

@Serializable
data class ShellExecResponse(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    /**
     * 命令实际以什么身份跑的：2000 = shell（ADB 级），0 = root，-1 = 没跑成。
     *
     * 不想猜就直接看这个 —— 它等于你手敲 `id -u` 拿到的数。
     */
    val execUid: Int,
)

/** Shizuku 当前状态，给调用方判断能不能用 */
@Serializable
data class ShellStatusResponse(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val ready: Boolean,
    /** Shizuku 服务进程 uid：2000 = shell，0 = root，-1 = 拿不到 */
    val serverUid: Int,
    val version: Int,
)
