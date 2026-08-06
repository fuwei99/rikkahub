package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 记忆调试日志设置（记忆日志设置页，与「记忆检索设置」同级的独立配置块）。
 *
 * 记忆相关链路（记忆图语义检索、抽取器、自动提炼等）共用的文件日志
 * （`<filesDir>/logs/memory_graph_debug.log`，随 /rikkahub-data 挂载可读）。
 * 参数全部走设置，不硬编码：
 * - [enabled]：是否写文件日志（排查完成后可关，release 不再写盘）；
 * - [maxAgeHours]：超过 N 小时的文件（含轮转备份）自动清除，默认 24h；
 * - [maxLines]：主日志行数超过 N 时滚动（当前 → .1，依次后移，保留 [keepBackups] 份）；
 * - [keepBackups]：轮转保留的备份份数。
 */
@Serializable
data class MemoryLogSettings(
    /** 文件日志总开关 */
    val enabled: Boolean = true,
    /** 超过 N 小时的文件（含轮转备份）自动清除 */
    val maxAgeHours: Int = 24,
    /** 主日志超过 N 行时滚动 */
    val maxLines: Int = 5000,
    /** 轮转保留的备份份数（.1/.2/...） */
    val keepBackups: Int = 3,
) {
    fun sanitized(): MemoryLogSettings = copy(
        maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30),
        maxLines = maxLines.coerceIn(100, 100_000),
        keepBackups = keepBackups.coerceIn(1, 10),
    )
}
