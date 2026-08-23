package me.rerere.rikkahub.data.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 监督事件日志与 fold 引擎（大统一重构 v2 §3，阶段 B）。
 *
 * 设计要点见 [SupervisionEvent] 的类注释。这里只放三件事：
 * 1. [SupervisionWindow.idAt]：由当前时间算出所属窗口 id
 * 2. [merge]：两端事件集合的 CRDT 合并（OR-Set，按 id 去重）
 * 3. [fold]：事件序列 → 锁状态
 */
object SupervisionWindow {

    /**
     * 返回 [nowMs] 所处监督窗口的 id，不在任何时段内返回 null。
     *
     * 复用 [SupervisionSchedule.activationSessionEndAt]：它已经正确处理了跨夜区间
     * 与 `endMinute == 1440` 的折算，别在这里重造时间语义。
     *
     * 窗口 id 用「本次时段的结束时刻」而非起始时刻做锚点，理由：
     * `activationSessionEndAt` 直接给出结束时刻，而起始时刻在跨夜场景要反推日期，
     * 容易在夏令时/日期边界上出错。同一次时段内任意时刻算出的结束时刻是同一个值，
     * 满足 id 的稳定性要求。
     */
    fun idAt(settings: SupervisionSettings, nowMs: Long): String? {
        settings.schedules.forEach { schedule ->
            val end = schedule.activationSessionEndAt(nowMs) ?: return@forEach
            if (nowMs < end) return "${schedule.id}:$end"
        }
        return null
    }

    /** 便捷重载 */
    fun idAt(settings: SupervisionSettings, instant: Instant): String? =
        idAt(settings, instant.toEpochMilliseconds())
}

/**
 * 监督事件集合。作为一个整体存进 `settings.supervision` 分片。
 *
 * @param events 事件列表；顺序不重要，[fold] 会按 hlc 排序
 */
@Serializable
data class SupervisionEventLog(
    val events: List<SupervisionEvent> = emptyList(),
) {

    /**
     * OR-Set 合并：按事件 id 取并集。
     *
     * 为什么是并集：事件是**不可变的历史事实**，两端都该看到全部事实。
     * 状态的「减弱」由 [fold] 时的 hlc 顺序表达，不靠删事件实现。
     *
     * 同 id 冲突（理论上不该发生，除非 uuid 碰撞或有人改了事件）取 hlc 较大者，
     * 保证两端得出同一结果。
     */
    fun merge(other: SupervisionEventLog): SupervisionEventLog {
        if (other.events.isEmpty()) return this
        if (events.isEmpty()) return other
        val byId = LinkedHashMap<String, SupervisionEvent>(events.size + other.events.size)
        (events + other.events).forEach { e ->
            val existing = byId[e.id]
            if (existing == null || e.hlc > existing.hlc) byId[e.id] = e
        }
        return SupervisionEventLog(byId.values.sortedBy { it.hlc })
    }

    fun append(event: SupervisionEvent): SupervisionEventLog =
        SupervisionEventLog(events + event)

    /**
     * 把事件序列折叠成锁状态。
     *
     * @param currentWindowId 当前所处窗口（[SupervisionWindow.idAt] 的结果）。
     *   为 null 表示当前不在任何监督时段 —— 此时窗口级锁一律不生效
     *   （与现有 `isActiveAt` 的语义一致：这把锁不是「永久封存对话」的工具）。
     *
     * @return 折叠出的锁集合与 enabled 覆盖值
     */
    fun fold(currentWindowId: String?): FoldResult {
        // 按 hlc 全序重放。同 hlc 时按 id 定序，保证两端结果一致
        val ordered = events.sortedWith(compareBy({ it.hlc }, { it.id }))

        val lockedConversations = mutableSetOf<Uuid>()
        val lockedPaths = mutableSetOf<String>()
        var enabledOverride: Boolean? = null

        ordered.forEach { e ->
            // ★ 窗口级事件只在其所属窗口内参与计算。
            // 不满足条件的事件**保留在日志里**（审计 + 收敛需要），只是不影响当前状态。
            if (e.kind.isWindowScoped && e.windowId != currentWindowId) return@forEach

            when (e.kind) {
                SupervisionEvent.Kind.LOCK_CONVERSATION ->
                    runCatching { Uuid.parse(e.target) }.getOrNull()?.let { lockedConversations += it }

                SupervisionEvent.Kind.UNLOCK_CONVERSATION ->
                    runCatching { Uuid.parse(e.target) }.getOrNull()?.let { lockedConversations -= it }

                SupervisionEvent.Kind.LOCK_PATH -> if (e.target.isNotBlank()) lockedPaths += e.target
                SupervisionEvent.Kind.UNLOCK_PATH -> lockedPaths -= e.target

                SupervisionEvent.Kind.ENABLE -> enabledOverride = true
                SupervisionEvent.Kind.DISABLE -> enabledOverride = false
            }
        }

        return FoldResult(
            lockedConversationIds = lockedConversations,
            lockedWorkspacePaths = lockedPaths,
            enabledOverride = enabledOverride,
        )
    }

    /**
     * 压缩（v2 §3.5）。
     *
     * ⚠️ **不能简单「保留最近 N 条」**：A 与 B 各自独立裁剪，裁掉的集合不同，
     * A 裁掉的事件会从 B 那儿同步回来 → **事件复活**，与 2026-08-18 那个
     * pendingUnlock 复活是同一个机制。
     *
     * 正确条件（两条都满足才丢）：
     * 1. 该事件所属窗口**已经结束**（`windowId` 不再是任何活跃窗口）
     * 2. `hlc < stableWatermark` —— 所有已知设备都确认拉过这个位置
     *
     * @param stableWatermark 所有设备 ack 的 hlc 最小值；拿不到就传 0（= 不压缩）
     * @param activeWindowIds 当前仍可能生效的窗口 id 集合
     */
    fun compact(stableWatermark: Long, activeWindowIds: Set<String>): SupervisionEventLog {
        if (stableWatermark <= 0L) return this // 拿不到全设备 ack 就不压缩，事件很小，不急
        val kept = events.filter { e ->
            when {
                e.hlc >= stableWatermark -> true
                !e.kind.isWindowScoped -> true // 配置级事件是终态来源，永久保留
                e.windowId in activeWindowIds -> true
                else -> false
            }
        }
        return if (kept.size == events.size) this else SupervisionEventLog(kept)
    }

    data class FoldResult(
        val lockedConversationIds: Set<Uuid>,
        val lockedWorkspacePaths: Set<String>,
        /** null = 事件日志未表态，沿用配置里的 enabled */
        val enabledOverride: Boolean?,
    )
}
