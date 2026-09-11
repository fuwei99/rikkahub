package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.common.android.SyncPerfLog

/**
 * D1 同步性能日志设置（日志设置页）。
 *
 * 与 [ToolLogSettings] 同构：总开关 + 分通道子开关 + 轮转策略。
 *
 * 默认全关：这是排查用的工具，高频写盘，不该常驻。
 * 唯一例外是 [round] 默认跟随总开关打开 —— 只开总开关就能拿到最有价值的
 * 「每轮耗时分解表」，不用再去研究三个通道分别是干嘛的。
 */
@Serializable
data class SyncPerfLogSettings(
    /** 总开关 */
    val enabled: Boolean = false,
    /** 每轮同步汇总表（阶段耗时分解 + 请求数），排查首选 */
    val round: Boolean = true,
    /** 阶段级耗时（≥50ms 才记） */
    val phase: Boolean = false,
    /** 单条 SQL 往返明细，最吵 */
    val request: Boolean = false,
    /** 超过 N 小时的文件（含轮转备份）自动清除 */
    val maxAgeHours: Int = 24,
    /** 主日志超过 N 行时滚动 */
    val maxLines: Int = 5000,
    /** 轮转保留的备份份数 */
    val keepBackups: Int = 3,
) {
    /** 当前开启的通道集合，直接喂给 [SyncPerfLog.configure]。 */
    val enabledChannels: Set<String>
        get() = buildSet {
            if (round) add(SyncPerfLog.CHANNEL_ROUND)
            if (phase) add(SyncPerfLog.CHANNEL_PHASE)
            if (request) add(SyncPerfLog.CHANNEL_REQUEST)
        }

    fun sanitized(): SyncPerfLogSettings = copy(
        maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30),
        maxLines = maxLines.coerceIn(100, 100_000),
        keepBackups = keepBackups.coerceIn(1, 10),
    )
}
