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
     * 格式 `day:<监督日起始时刻 epoch ms>`，由 [SupervisionWindow.idAt] 生成。
     *
     * ⚠️ 粒度是**监督日**（本地 06:00 换日），**不是**单个 schedule 条目。
     * 2026-09-28 之前这里是 `<scheduleId>:<本段结束时刻>`，等于「一节 40 分钟的课
     * 一个窗口」；时段表被 10 分钟课间切碎，于是锁连一次课间都熬不过去
     * （用户实测：「锁定还是会在一次监督时段后失效」）。课表怎么切片是调度的事，
     * 不该决定锁活多久。
     *
     * 比较一律用 [SupervisionWindow.matches]（兼容旧格式），别写 ==。
     *
     * [WINDOW_GLOBAL] 表示配置级事件，不属于任何窗口，永久生效。
     */
    val windowId: String,

    /** 事件载荷：会话 id / 路径前缀 / 配置值等，按 [kind] 解释 */
    val target: String = "",

    /** 人类可读理由，展示在监督事件历史里 */
    val reason: String = "",

    /**
     * 到期时刻（epoch ms）。**0 = 锁到本时段结束**（老的窗口语义）。
     *
     * ## 两种语义（2026-09-20 改）
     *
     * - **0**：锁活到「创建它的那个**监督日**」结束（本地 06:00 换日）。
     *   当天所有小节、课间、午休都算同一个窗口，跨天才失效。
     * - **> 0**：**绝对截止**，跨窗口有效。不再看 [windowId]，从创建起一直生效到
     *   这个时刻。仍然只在监督时段内实际拦人（`isConversationLockedNow` 还叠了
     *   `isActiveNow()`），但课间、午休、时段切换都不会把它清掉。
     *
     * ## 为什么要改
     *
     * 时段表是切碎的（08:30-09:50 / 10:00-10:50 / 11:00-11:50 …）。2026-09-20 那次
     * 只补了 `expireAt > 0` 的一半：不传 `expire_at` 的锁照样死在课间。
     * 2026-09-28 把窗口本身抬到监督日粒度，默认路径才真正修好。
     *
     * ## 边界
     *
     * - 只对窗口级事件（[Kind.isWindowScoped]）有意义，配置级事件恒为 0；
     * - **解锁事件永远为 0**（工具侧只给 lock_* 传 expireAt）。这是安全属性：
     *   一旦解锁也能跨窗口，就退回「一次解锁 = 永久解锁」，正是 v2 重构要堵的洞；
     * - fold 时 `nowMs >= expireAt` 的事件**直接跳过**，等价于它从没锁过。
     *   事件本身不删（删了会被对端同步回来，见 [SupervisionEventLog] 类注释）。
     */
    val expireAt: Long = 0L,
) {
    /**
     * 该事件是否已经**永久失效** —— 即「丢掉它不会改变任何时刻的 fold 结果」（2026-10-04）。
     *
     * ## 判据三条
     *
     * 1. **配置级事件永不失效**。ENABLE / DISABLE 是 enabled 的终态来源，且每次拨开关
     *    才加一条 —— 数量恒定，不构成增长。
     * 2. **带未到 expireAt 的跨窗口锁不能丢**。它虽然早就离开了自己的 windowId，
     *    但它本人就是当前锁态的来源，丢掉 = 丢锁。（与 [SupervisionEventLog.compact] 同一条理由）
     * 3. **窗口级事件要等窗口结束满 [graceMs]**。窗口结束时刻走
     *    [SupervisionWindow.endMsOf]；解析不出来就返回 null，一律不当垃圾（保守优先）。
     *
     * ## 为什么谓词必须是「绝对时间」的
     *
     * 裁剪要跨设备达成一致，否则被裁的事件会从对端复活。窗口 id 自带绝对 epoch 时间戳，
     * 「现在离窗口结束多久了」是一道只依赖墙上时钟的题 —— 两端算出同一答案。
     * 反过来，任何形如「保留最近 N 条」的规则都是**非确定**的（两端看到的集合不同），
     * 那种裁剪绝对不能用。
     */
    fun isInertAt(nowMs: Long, graceMs: Long = SupervisionEventLog.INERT_GRACE_MS): Boolean {
        if (!kind.isWindowScoped) return false
        if (expireAt > 0L && expireAt > nowMs) return false
        val endMs = SupervisionWindow.endMsOf(windowId) ?: return false
        return endMs + graceMs < nowMs
    }

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
