package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.sync.core.SyncClock
import kotlin.uuid.Uuid

/**
 * 监督事件的**产生层**（大统一重构 v2 §3.4）。
 *
 * ## 为什么防作弊要从合并层上移到这里
 *
 * 旧防线在合并层：`strengthenWith` 拒绝一切减弱。后果是**连用户本人都被拒**——
 * 你在平板上解锁，手机同步一次就把锁「加强」回去（2026-08-23 21:12 的事故）。
 *
 * v2 把三层职责彻底分开：
 *
 * | 层 | 职责 |
 * |---|---|
 * | **产生层（本类）** | 唯一有权创建事件的地方。守门员身份、冷却期、时段判定全在这 |
 * | **合并层** | 只搬运事件，**永不产生、永不篡改** |
 * | **fold 层** | 按 hlc + windowId 算状态，**无任何判断** |
 *
 * 这比「合并时判断要不要放行」清晰得多，也不会再出现「本人解不开自己的锁」。
 *
 * ## 铁律：同步下拉路径不得调用本类
 *
 * `SyncApplyGate.applyingRemote == true` 时代码路径**根本不允许**产生事件。
 * 云端来的事件是**已经存在的事实**，只能 `merge`，不能重新 `create`——
 * 重新创建会给它打上本机新 hlc，等于把对端的旧事件伪装成本机新事实，
 * 于是两端互相「重新创建」同一个事件，永不收敛。
 * 见 [SupervisionEventLog.merge] 与 `SyncCrdt` 的调用约定。
 */
object SupervisionEventFactory {

    /** 产生请求被拒的原因，供 UI 与审计日志展示 */
    sealed interface Rejection {
        val message: String

        /** 不在监督时段内 —— 锁本身不生效，没必要产生事件 */
        data object NotInWindow : Rejection {
            override val message = "当前不在监督时段内，无需解锁"
        }

        /** 解除类事件缺少授权 */
        data class Unauthorized(override val message: String) : Rejection

        /** 冷却期未到 */
        data class Cooling(val remainingMs: Long) : Rejection {
            override val message = "解锁冷却中，还需等待 ${remainingMs / 60_000} 分钟"
        }

        data class InvalidTarget(override val message: String) : Rejection
    }

    /**
     * 产生一个事件的授权上下文。
     *
     * @param actor            谁在操作
     * @param hasAdminBypass   是否处于 `SupervisionGate.AdminBypass` 作用域内。
     *   这是用户本人经 UI 二次确认后的显式授权标记，**不是**同步路径能拿到的东西。
     * @param confirmedUnlock  是否已通过 [PendingUnlock] 冷却确认
     */
    data class Authority(
        val actor: SupervisionEvent.Actor,
        val hasAdminBypass: Boolean = false,
        val confirmedUnlock: Boolean = false,
    )

    /**
     * 尝试产生一个事件。
     *
     * @return 成功返回事件；被准入层拒绝返回 [Rejection]
     */
    fun create(
        settings: SupervisionSettings,
        kind: SupervisionEvent.Kind,
        target: String,
        authority: Authority,
        clock: SyncClock,
        reason: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): Result<SupervisionEvent> {
        val windowId = if (kind.isWindowScoped) {
            SupervisionWindow.idAt(settings, nowMs)
                ?: return fail(Rejection.NotInWindow)
        } else {
            // 配置级事件（ENABLE / DISABLE）不属于任何窗口，永久生效
            SupervisionEvent.WINDOW_GLOBAL
        }

        // ---- 目标校验 ----
        when (kind) {
            SupervisionEvent.Kind.LOCK_CONVERSATION,
            SupervisionEvent.Kind.UNLOCK_CONVERSATION -> {
                if (runCatching { Uuid.parse(target) }.isFailure) {
                    return fail(Rejection.InvalidTarget("对话 id 不是合法 uuid：$target"))
                }
            }

            SupervisionEvent.Kind.LOCK_PATH,
            SupervisionEvent.Kind.UNLOCK_PATH -> {
                if (target.isBlank()) return fail(Rejection.InvalidTarget("锁定路径不能为空"))
            }

            SupervisionEvent.Kind.ENABLE,
            SupervisionEvent.Kind.DISABLE -> Unit
        }

        // ---- 准入：解除方向需要授权 ----
        if (kind.isRelaxing) {
            val denial = checkRelaxAuthority(settings, authority, nowMs)
            if (denial != null) return fail(denial)
        }
        // 加严方向（LOCK_*、ENABLE）无需授权：任何 actor 都可以让规则更严。
        // 这是刻意的 —— 查岗 agent 可以自己上锁，代价是最多让用户多受点管，
        // 而放松方向一律要过上面那道门。

        return Result.success(
            SupervisionEvent(
                kind = kind,
                // ★ hlc 必须在**准入通过后**才取：取了就消耗一个计数，
                // 被拒的请求不该推进因果时钟
                hlc = clock.now(),
                actor = authority.actor,
                windowId = windowId,
                target = target,
                reason = reason,
            )
        )
    }

    /**
     * 解除类事件的授权校验。
     *
     * @return null = 放行；非 null = 拒绝原因
     */
    private fun checkRelaxAuthority(
        settings: SupervisionSettings,
        authority: Authority,
        nowMs: Long,
    ): Rejection? {
        // 查岗类定时任务永远不能放松规则：能自己上锁，不能自己开锁。
        // 否则被 prompt 注入的 agent 可以把自己解绑。
        if (authority.actor == SupervisionEvent.Actor.SCHEDULE_AGENT) {
            return Rejection.Unauthorized("定时任务只能加严，不能解除监督限制")
        }

        // 用户本人带 AdminBypass（UI 二次确认过）→ 直接放行。
        // 这是修「本人解不开自己的锁」的地方。
        if (authority.hasAdminBypass) return null

        // 守门员路径：必须是被登记的守门员助手，且走完冷却确认
        if (authority.actor == SupervisionEvent.Actor.GRANTOR) {
            if (settings.unlockGrantorAssistantId == null) {
                return Rejection.Unauthorized("未设置守门员助手，监督期内不可解锁")
            }
            if (!authority.confirmedUnlock) {
                val pending = settings.pendingUnlock
                    ?: return Rejection.Unauthorized("需先由守门员发起解锁请求并等待冷却")
                return when (pending.status) {
                    // 已批准 → 放行
                    PendingUnlock.Status.APPROVED -> null

                    // 冷却已满但用户还没在 UI 上点「确认解锁」。
                    // 不能自动放行：冷却机制的意义就是「过一会儿再问一次你是否真要解」，
                    // 少了这次确认，守门员一句话就能直接开锁。
                    PendingUnlock.Status.READY ->
                        Rejection.Unauthorized("冷却已结束，请在设置页点「确认解锁」")

                    PendingUnlock.Status.PENDING ->
                        Rejection.Cooling((pending.expiresAt - nowMs).coerceAtLeast(0L))

                    PendingUnlock.Status.REJECTED, PendingUnlock.Status.CANCELLED ->
                        Rejection.Unauthorized("上一次解锁请求已失效，需重新发起")
                }
            }
            return null
        }

        return Rejection.Unauthorized("解除监督限制需要用户本人确认或守门员授权")
    }

    private fun fail(rejection: Rejection): Result<SupervisionEvent> =
        Result.failure(SupervisionEventRejected(rejection))
}

/** 准入层拒绝产生事件。携带结构化原因，便于 UI 展示与审计 */
class SupervisionEventRejected(
    val rejection: SupervisionEventFactory.Rejection,
) : Exception(rejection.message)
