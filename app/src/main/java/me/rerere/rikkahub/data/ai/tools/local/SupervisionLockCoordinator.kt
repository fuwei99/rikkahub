package me.rerere.rikkahub.data.ai.tools.local

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.agent.AgentInboxStore
import me.rerere.rikkahub.data.ai.agent.AgentMessageKind
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.SupervisionEvent
import me.rerere.rikkahub.data.model.SupervisionEventFactory
import me.rerere.rikkahub.data.model.normalizeLockedPath
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "SupervisionLock"

/** 申诉材料在收件箱里的来源标记（开放枚举，见 AgentInboxSource）。 */
private const val INBOX_SOURCE_SUPERVISION = "supervision"

/**
 * 监督锁的落地协调器（PLAN_SUPERVISION_ADMIN_TOOL §5 / §9.6）。
 *
 * 为什么不把倒计时写在工具 `execute` 里：工具调用是同步返回的，agent 在等结果，
 * 里头 `delay(120_000)` 等于把整条生成挂在申诉窗口上（还会撞上工具超时）。
 * 所以工具只负责 [requestConversationLock] / [requestPathLock] 登记一笔，
 * 真正写配置由本类在「倒计时走完」或「用户操作」之后执行。
 *
 * 三条铁律（都是踩过的坑）：
 * 1. **落锁不依赖 LLM 往返**：超时、点 ❌、提交申诉，三种结局一律落锁。
 *    申诉正文只是投进发起 agent 的收件箱，解锁是它后续的二次动作。
 * 2. **无人值守也要生效**：schedule agent 发起时不弹窗（showDialog=false），
 *    倒计时照走，走完自动落锁。
 * 3. **加锁走普通 update**：Gate 的锁集合并集本来就放行加锁；
 *    只有解锁才需要 AdminBypass（见 [SettingsStore.updateSupervisionByAdmin]）。
 */
class SupervisionLockCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val eventBus: AppEventBus,
    private val agentInboxStore: AgentInboxStore,
) {

    private data class PendingAppeal(
        val appealId: String,
        val target: LockTarget,
        val reason: String,
        val initiatorConversationId: Uuid,
        val targetLabel: String,
        val showDialog: Boolean,
        var deadlineAt: Long,
        var extensionsLeft: Int,
        val extensionSeconds: Int,
        var job: Job? = null,
    )

    private sealed interface LockTarget {
        data class Conversation(val id: Uuid) : LockTarget
        data class Path(val prefix: String) : LockTarget
    }

    private val pending = ConcurrentHashMap<String, PendingAppeal>()

    /** 结果描述，回给工具让 agent 知道是「已锁」还是「倒计时中」。 */
    data class LockRequestResult(
        val locked: Boolean,
        val appealId: String?,
        val deadlineAt: Long,
        val message: String,
    )

    suspend fun requestConversationLock(
        conversationId: Uuid,
        reason: String,
        initiatorConversationId: Uuid,
        showDialog: Boolean,
    ): LockRequestResult = request(
        target = LockTarget.Conversation(conversationId),
        targetLabel = "对话 ${conversationId.toString().take(8)}…",
        reason = reason,
        initiatorConversationId = initiatorConversationId,
        showDialog = showDialog,
    )

    suspend fun requestPathLock(
        path: String,
        reason: String,
        initiatorConversationId: Uuid,
        showDialog: Boolean,
    ): LockRequestResult {
        val normalized = normalizeLockedPath(path)
            ?: return LockRequestResult(
                locked = false,
                appealId = null,
                deadlineAt = 0L,
                message = "path must be an absolute rootfs path prefix",
            )
        return request(
            target = LockTarget.Path(normalized),
            targetLabel = "路径 $normalized",
            reason = reason,
            initiatorConversationId = initiatorConversationId,
            showDialog = showDialog,
        )
    }

    private suspend fun request(
        target: LockTarget,
        targetLabel: String,
        reason: String,
        initiatorConversationId: Uuid,
        showDialog: Boolean,
    ): LockRequestResult {
        val sup = settingsStore.settingsFlow.value.supervision
        val countdown = sup.appealCountdownSeconds
        // 0 = 不给申诉机会，直接锁（用户自己在监督设置页调成 0 的，尊重它）
        if (countdown <= 0) {
            applyLock(target)
            return LockRequestResult(
                locked = true,
                appealId = null,
                deadlineAt = 0L,
                message = "已立即锁定（申诉倒计时被配置为 0）。",
            )
        }

        val appealId = Uuid.random().toString()
        val appeal = PendingAppeal(
            appealId = appealId,
            target = target,
            reason = reason,
            initiatorConversationId = initiatorConversationId,
            targetLabel = targetLabel,
            showDialog = showDialog,
            deadlineAt = System.currentTimeMillis() + countdown * 1000L,
            extensionsLeft = sup.appealMaxExtensions.coerceAtLeast(0),
            extensionSeconds = sup.appealExtensionSeconds.coerceAtLeast(0),
        )
        pending[appealId] = appeal
        emitPending(appeal)
        appeal.job = appScope.launch {
            // 睡到截止：期间 extendAppeal 可能把 deadlineAt 推后，所以循环重算
            while (true) {
                val left = appeal.deadlineAt - System.currentTimeMillis()
                if (left <= 0L) break
                delay(left)
            }
            // 超时兜底：人没理它（或压根没界面）也照样落锁
            finish(appealId, appealText = "")
        }
        return LockRequestResult(
            locked = false,
            appealId = appealId,
            deadlineAt = appeal.deadlineAt,
            message = "已通知用户，${countdown}s 倒计时结束后自动锁定（超时 / 拒绝 / 提交申诉都会落锁）。" +
                "若用户提交了申诉，正文会进你的收件箱，你可以再决定是否 unlock。",
        )
    }

    /** 「再给一会儿」：仅剩余次数 > 0 时有效。 */
    fun extendAppeal(appealId: String) {
        val appeal = pending[appealId] ?: return
        if (appeal.extensionsLeft <= 0) return
        appeal.extensionsLeft -= 1
        appeal.deadlineAt += appeal.extensionSeconds * 1000L
        appScope.launch { emitPending(appeal) }
    }

    /**
     * 用户处理完了：提交申诉（[appealText] 非空）或直接拒绝（空串）。
     * 两种情况都**立即落锁**——申诉不是否决权。
     */
    fun resolveAppeal(appealId: String, appealText: String) {
        appScope.launch { finish(appealId, appealText) }
    }

    private suspend fun finish(appealId: String, appealText: String) {
        // remove 保证同一 appeal 只落锁一次（超时 job 与用户操作会撞车）
        val appeal = pending.remove(appealId) ?: return
        appeal.job?.cancel()
        applyLock(appeal.target)
        if (appealText.isNotBlank()) {
            runCatching {
                agentInboxStore.enqueue(
                    target = appeal.initiatorConversationId,
                    body = "用户对「${appeal.targetLabel}」的锁定提出申诉：\n$appealText\n\n" +
                        "（锁已生效。你可以用 supervision_admin 的 unlock_* 撤销，也可以驳回。）",
                    kind = AgentMessageKind.REPORT,
                    source = INBOX_SOURCE_SUPERVISION,
                )
            }.onFailure { Log.w(TAG, "failed to deliver appeal text", it) }
        }
        eventBus.emit(AppEvent.SupervisionAppealResolved(appealId))
    }

    private suspend fun applyLock(target: LockTarget) {
        // 阶段 B（v2 §3.2）：上锁也必须走事件，不能再裸写锁集合。
        //
        // 原因不是「上锁需要授权」（加严方向本来就放行），而是**一致性**：
        // 锁态现在由事件日志 fold 决定。如果上锁裸写集合、解锁产生事件，
        // 那么下一次 applyEventLog 会用 fold 结果整体覆盖锁集合，
        // 裸写进去的那把锁**压根不在日志里 → 当场被抹掉**，锁了等于没锁。
        //
        // 加严方向的事件由任何 actor 都能产生（产生层只拦 isRelaxing），
        // 所以这里不需要任何 bypass 或授权参数。
        val (kind, targetKey) = when (target) {
            is LockTarget.Conversation ->
                SupervisionEvent.Kind.LOCK_CONVERSATION to target.id.toString()

            is LockTarget.Path ->
                SupervisionEvent.Kind.LOCK_PATH to target.prefix
        }
        settingsStore.appendSupervisionEvent(
            kind = kind,
            target = targetKey,
            authority = SupervisionEventFactory.Authority(
                // 锁是由监督侧（守门员工具或查岗任务）发起的。加严方向不校验 actor，
                // 统一记 GRANTOR 只影响事件历史 UI 的展示文案。
                actor = SupervisionEvent.Actor.GRANTOR,
            ),
            reason = "锁定 $targetKey",
        ).onFailure { e ->
            // 上锁失败几乎只可能是「当前不在监督时段」（NotInWindow）。
            // 这不是错误：锁本身只在时段内生效，时段外产生事件没有意义。
            Log.i(TAG, "lock event not created: ${e.message}")
        }
    }

    private suspend fun emitPending(appeal: PendingAppeal) {
        eventBus.emit(
            AppEvent.SupervisionAppealPending(
                appealId = appeal.appealId,
                initiatorConversationId = appeal.initiatorConversationId,
                targetLabel = appeal.targetLabel,
                reason = appeal.reason,
                deadlineAt = appeal.deadlineAt,
                extensionsLeft = appeal.extensionsLeft,
                extensionSeconds = appeal.extensionSeconds,
                showDialog = appeal.showDialog,
            )
        )
    }
}
