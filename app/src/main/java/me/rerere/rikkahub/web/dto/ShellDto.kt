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
)

/** Shizuku 当前状态，给调用方判断能不能用 */
@Serializable
data class ShellStatusResponse(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val ready: Boolean,
)
