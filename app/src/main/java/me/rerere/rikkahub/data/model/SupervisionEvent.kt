package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 监督锁事件（大统一重构 v2 §3，阶段 B）。
 *
 * ## 为什么必须换掉 `strengthenWith`
 *
 * [SupervisionSettings.strengthenWith] 是一个**单调只增的并集半格**：
 *
 * ```kotlin
 * enabled = enabled || other.enabled
 * lockedConversationIds = lockedConversationIds + other.lockedConversationIds
 * appealCountdownSeconds = minOf(...)        // 越小越严
 * ```
 *
 * 并集里**没有减法**，因此「解锁」这个意图在数学上无法表达。
 * 2026-08-23 实测：在平板解锁后，手机把旧锁态推回云端，`strengthenWith` 忠实地把锁
 * 「加强」回去，还顺手 `updatedAt = maxOf(...)`，于是两台设备互相投喂同一把锁，永生。
 *
 * 这与 2026-08-18 修的「pendingUnlock 清完下一轮又 PENDING」是同一病根，
 * 那次只给 `pendingUnlock` 打了单点补丁，`enabled` 与两个锁集合没管。
 *
 * ## 解法：把单调性从「状态」下移到「日志」
 *
 * - 云端存的是**事件集合**（OR-Set，按 [id] 去重）——集合本身仍然只增，保持 CRDT 单调性
 * - 本地状态 = `events.sortedBy(hlc).fold(base) { s, e -> s.apply(e) }`
 * - **解锁 = 一个 hlc 更大的事件**，不是「一个更弱的状态」→ 天然跨设备传播
 *
 * 于是「集合只增」与「状态可减弱」不再矛盾。
 *
 * ## ⚠️ windowId：不绑时段的解锁事件 = 永久解锁
 *
 * 监督锁的语义是**仅在监督时段内生效**。如果 `UnlockConv` 只带 hlc：
 *
 * ```
 * 周日 21:12  UnlockConv(hlc=T1)
 * 周一 08:30  新监督时段开始
 *             fold 事件日志 → UnlockConv 仍是该会话最新事件 → 仍是解锁态 💀
 * ```
 *
 * **一次解锁 = 永久解锁，监督系统当场报废。** 因此每个「窗口内行为」事件都必须绑定
 * 它所属的那一次时段（[windowId]）；fold 时非当前窗口的事件不参与状态计算，
 * 但**保留在日志里**（审计需要，且删掉会破坏收敛）。
 *
 * 这条同时天然实现了 `clearStaleUnlock()` 想干的事，且是声明式的，无需额外清理逻辑。
 */
@Serializable
data class SupervisionEvent(
    /** 去重键。同一事件在多设备间搬运时靠它幂等 */
    val id: String = Uuid.random().toString(),

    val kind: Kind,

    /** packed HLC（见 `SyncClock`），全序裁决与 fold 排序依据 */
    val hlc: Long,

    /** 谁产生的 */
    val actor: Actor,

    /**
     * 所属监督窗口。
     *
     * 格式 `<scheduleId>:<本次时段结束时刻>`，由 [SupervisionWindow.idAt] 生成
     * （复用 `SupervisionSchedule.activationSessionEndAt`，不新造时间语义）。
     *
     * [WINDOW_GLOBAL] 表示配置级事件，不属于任何窗口，永久生效。
     */
    val windowId: String,

    /** 事件载荷：会话 id / 路径前缀 / 配置值等，按 [kind] 解释 */
    val target: String = "",

    /** 人类可读理由，展示在监督事件历史里 */
    val reason: String = "",

    /**
     * 到期时刻（epoch ms）。**0 = 不设上限** —— 沿用原来的窗口语义：
     * 锁活到「创建它的那次监督时段」结束。
     *
     * 为什么需要它：默认语义对查岗够用，但有些场景要更短 ——
     * 「锁到你写完这张卷子」，08:30–11:50 的时段里只想锁 40 分钟。
     * 以前只能靠 unlock，但那要走守门员 + 冷却，太重。
     *
     * 语义边界：
     * - 与 [windowId] 是 **AND**：到 [expireAt] 失效，**或**时段结束失效，谁先到算谁；
     * - 只对窗口级事件（[Kind.isWindowScoped]）有意义，配置级事件恒为 0；
     * - fold 时 `nowMs >= expireAt` 的事件**直接跳过**，等价于它从没锁过。
     *   事件本身不删（删了会被对端同步回来，见 [SupervisionEventLog] 类注释）。
     */
    val expireAt: Long = 0L,
) {
    @Serializable
    enum class Kind {
        // ---- 窗口内行为（必须绑 windowId）----
        LOCK_CONVERSATION,
        UNLOCK_CONVERSATION,
        LOCK_PATH,
        UNLOCK_PATH,

        // ---- 配置级（windowId = global）----
        ENABLE,
        DISABLE,
        ;

        /** 该类事件是否只在其所属窗口内生效 */
        val isWindowScoped: Boolean
            get() = this == LOCK_CONVERSATION || this == UNLOCK_CONVERSATION ||
                this == LOCK_PATH || this == UNLOCK_PATH

        /** 是否为「解除」方向。这类事件**只能**在准入层被创建，见 §3.4 */
        val isRelaxing: Boolean
            get() = this == UNLOCK_CONVERSATION || this == UNLOCK_PATH || this == DISABLE
    }

    @Serializable
    enum class Actor {
        /** 用户本人在 UI 上操作 */
        USER,

        /** 守门员助手（`unlockGrantorAssistantId`）通过 supervision_admin 工具 */
        GRANTOR,

        /** 查岗等定时任务（`adminScheduleAgentIds`）——只能加严，不能放松 */
        SCHEDULE_AGENT,
    }

    companion object {
        const val WINDOW_GLOBAL = "global"
    }
}
