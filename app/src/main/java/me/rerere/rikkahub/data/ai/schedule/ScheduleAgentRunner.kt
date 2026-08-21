package me.rerere.rikkahub.data.ai.schedule

import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentLimits
import me.rerere.rikkahub.data.ai.agent.AgentMessage
import me.rerere.rikkahub.data.ai.agent.AgentMessageKind
import me.rerere.rikkahub.data.ai.agent.AgentSenderRole
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.ai.agent.AgentUrgency
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_PROTOCOL_NOTE
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_VIRTUAL_PARENT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.applyPlaceholders
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ScheduleAgentRunner"

/**
 * Schedule Agent 触发执行（PLAN_SCHEDULE_AGENTS §3.2）：模拟父节点投递。
 *
 * 流程：
 * 1. 读模板，enabled=false 直接返回（下一次闹钟已排好）；
 * 2. 监督总闸 + 窗口/定时点放行判定（跳过时下一次照常排）；
 * 3. 按 conversationMode 找/建会话（reuse 复用常驻，fresh 每次新建）；
 * 4. 任务文本占位符展开 → 以 system 署名投递 `[schedule]` 系统消息到收件箱 + 唤醒
 *    （复用 [AgentBridge.deliver]，消息无条件入箱，目标空闲后自动开一轮生成）；
 * 5. 下一次触发由 [ScheduleAgentScheduler.scheduleNext] 在 Receiver 里排好（与执行成败解耦）。
 */
class ScheduleAgentRunner(
    private val manager: ScheduleAgentManager,
    private val bridge: AgentBridge,
    chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val agentSessionDao: AgentSessionDAO,
    private val conversationRepo: ConversationRepository,
) {
    suspend fun run(templateId: String) {
        val template = manager.getTemplate(templateId) ?: run {
            Log.w(TAG, "template not found: $templateId")
            return
        }
        if (!template.enabled) return

        // ChatService 由 Koin 构造注入（chatService 参数）：其 init 里
        // agentBridge.attach(Deps) 已完成——冷启动（闹钟拉起进程）时
        // get(Runner) 即连带创建 ChatService，唤醒一定发得出去。

        val sup = settingsStore.settingsFlow.first().supervision
        // 监督总闸：监督期内总闸关闭 → 所有定时任务跳过（不只查岗）
        if (sup.isActiveNow() && !sup.scheduleAgentsEnabledDuringSupervision) {
            Log.i(TAG, "skip ${template.id}: schedule agents disabled during supervision")
            return
        }
        // 窗口/定时点放行：闹钟被 Doze 推迟到窗口外、或 BOOT 重排踩到奇怪时刻时兜住。
        // 未配 windows/dailyTimes 的模板永远放行（老行为）。
        val trigger = ScheduleTimePlanner.resolveTrigger(template) ?: run {
            Log.i(TAG, "skip ${template.id}: outside all windows / daily times")
            return
        }

        val resolution = resolveSession(template) ?: run {
            Log.e(TAG, "failed to resolve session for ${template.id}")
            return
        }
        val sessionId = resolution.id

        // 模板工具强制并入会话（并集去重）：reuse 复用会话的 profile 快照不刷新，
        // 模板后来加的 allowedMcpTools（如微信 MCP）必须每次触发前并进去才能生效（2026-08-20）。
        bridge.ensureScheduleTools(template, sessionId)

        // reuse 会话已有历史时，定时器只负责续跑，不重复发送原任务。
        // 任务尚在生成、等待用户回答/审批，或者已经有未读派活时，什么都不再塞，
        // 避免下一次闹钟把同一任务复制成多封 inbox 邮件。
        if (!resolution.created) {
            val row = agentSessionDao.getByChildId(sessionId.toString())
            if (row == null) return
            if (row.status == AgentStatuses.RUNNING ||
                row.status == AgentStatuses.WAITING_PARENT ||
                row.status == AgentStatuses.WAITING_APPROVAL
            ) return
            val reminderLimit = template.prematureEndReminders.takeIf { it > 0 }
                ?: AgentLimits.MAX_PREMATURE_END_REMINDERS
            val unfinished = row.prematureEndCount > 0 &&
                (row.status == AgentStatuses.STOPPED || row.status == AgentStatuses.IDLE)
            if (unfinished) {
                // 达到提醒上限后保持停止态，不能又回到下面把原任务重新投递一遍。
                if (row.prematureEndCount >= reminderLimit) return
                if (bridge.remindScheduleTask(sessionId)) return
            }
        }

        val taskText = template.taskPrompt.applyPlaceholders(
            "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
            "name" to template.name,
            "window" to trigger.windowName,
            "tag" to trigger.tag,
        )
        val body = buildString {
            append("<from role=\"${AgentSenderRole.SYSTEM}\" title=\"定时任务：${template.name}\">")
            append("\n[schedule] ")
            append(taskText)
            // 协议兜底重申：绑定的助手可能 allowConversationSystemPrompt=false（拿不到
            // customSystemPrompt），且常驻会话的历史会被自动压缩。不重申就会出现
            // 「永远不调 agent_report → 永远判为提前结束」。
            append("\n\n")
            append(SCHEDULE_PROTOCOL_NOTE)
        }

        val err = bridge.deliver(
            AgentMessage(
                target = sessionId,
                text = body,
                kind = AgentMessageKind.SYSTEM,
                senderRole = AgentSenderRole.SYSTEM,
                senderTitle = template.name,
                templateId = template.id,
            ),
            urgency = AgentUrgency.MAIL,
        )
        if (err != null) {
            Log.w(TAG, "deliver failed for ${template.id}: $err")
        }
    }

    /**
     * reuse：找模板对应的常驻会话（会话被删 / 撞消息数上限则重建）；fresh：每次新建。
     *
     * **轮换**是 reuse 模式能长期跑的关键：常驻会话的 messageNodes 只增不减
     * （自动压缩折叠历史但不删节点），撞 [AgentLimits.MAX_MESSAGE_NODES] 后
     * `deliver` 会永久拒收。所以接近阈值时主动换一条新会话并归档旧的——
     * 上下文连续性让位于「任务永远活着」。
     */
    private data class SessionResolution(
        val id: Uuid,
        val created: Boolean,
    )

    private suspend fun resolveSession(template: ScheduleAgentTemplate): SessionResolution? {
        if (template.reuseConversation) {
            val row = agentSessionDao.getByTemplateId(template.id, SCHEDULE_VIRTUAL_PARENT_ID.toString())
            if (row != null && row.status != AgentStatuses.ARCHIVED) {
                val id = runCatching { Uuid.parse(row.childId) }.getOrNull()
                val conversation = id?.let { conversationRepo.getConversationById(it) }
                if (id != null && conversation != null) {
                    // 留一轮的余量（一次触发会产生若干 node），撞死线前就换会话
                    val nodes = conversation.messageNodes.size
                    if (nodes < AgentLimits.MAX_MESSAGE_NODES - SESSION_ROTATE_MARGIN) {
                        // 模板的自动压缩配置回灌常驻会话（2026-08-21）：
                        // reuse 模式下会话是 spawn 那一刻建的，之后改模板 JSON 不会自动生效，
                        // 老会话会永远按当初的（通常是 null）配置跑 → 用户改了模板却「没反应」。
                        if (conversation.autoCompressOverride != template.autoCompress) {
                            runCatching {
                                conversationRepo.updateConversation(
                                    conversation.copy(autoCompressOverride = template.autoCompress)
                                )
                            }.onFailure {
                                Log.w(TAG, "sync autoCompress to reused session failed for ${template.id}", it)
                            }
                        }
                        return SessionResolution(id = id, created = false)
                    }
                    Log.i(TAG, "rotating session for ${template.id}: nodes=$nodes")
                    runCatching { bridge.archive(id) }
                        .onFailure { Log.w(TAG, "archive old session failed for ${template.id}", it) }
                }
            }
        }
        return SessionResolution(id = bridge.spawnSchedule(template), created = true)
    }

    private companion object {
        /** 轮换余量：距 MAX_MESSAGE_NODES 还剩这么多节点时就换新会话 */
        const val SESSION_ROTATE_MARGIN = 8
    }
}
