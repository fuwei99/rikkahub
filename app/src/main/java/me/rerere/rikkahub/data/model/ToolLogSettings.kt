package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.common.android.ToolCallDebugLog

/**
 * 工具调用调试日志设置（日志设置页，与「记忆图日志」同级的独立配置块）。
 *
 * 文件落 `<filesDir>/logs/tool_call_debug.log`（随 /rikkahub-data 挂载可读）。
 * 结构是「总开关 + 每个工具一个子开关」，以后加别的工具日志只需加一个字段：
 * - [enabled]：总开关，关掉后所有通道都不写盘；
 * - [askUser]：ask_user 全过程日志（Pending → 弹窗 → 提交 → 回投 → 落库 → 续跑 → 超时）；
 * - [maxAgeHours]：超过 N 小时的文件（含轮转备份）自动清除，默认 24h；
 * - [maxLines]：主日志行数超过 N 时滚动，默认 5000；
 * - [keepBackups]：轮转保留的备份份数。
 *
 * 与记忆图日志一致，属设备本地调试开关，不随 D1 同步。
 */
@Serializable
data class ToolLogSettings(
    /** 工具日志总开关 */
    val enabled: Boolean = false,
    /** 子开关：ask_user 全过程日志 */
    val askUser: Boolean = false,
    /** 超过 N 小时的文件（含轮转备份）自动清除 */
    val maxAgeHours: Int = 24,
    /** 主日志超过 N 行时滚动 */
    val maxLines: Int = 5000,
    /** 轮转保留的备份份数（.1/.2/...） */
    val keepBackups: Int = 3,
) {
    /** 当前开启的通道集合，直接喂给 [ToolCallDebugLog.configure]。 */
    val enabledChannels: Set<String>
        get() = buildSet {
            if (askUser) add(ToolCallDebugLog.CHANNEL_ASK_USER)
        }

    fun sanitized(): ToolLogSettings = copy(
        maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30),
        maxLines = maxLines.coerceIn(100, 100_000),
        keepBackups = keepBackups.coerceIn(1, 10),
    )
}
