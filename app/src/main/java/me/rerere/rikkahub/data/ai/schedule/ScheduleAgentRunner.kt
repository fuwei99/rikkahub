package me.rerere.rikkahub.data.ai.schedule

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentMessage
import me.rerere.rikkahub.data.ai.agent.AgentMessageKind
import me.rerere.rikkahub.data.ai.agent.AgentSenderRole
import me.rerere.rikkahub.data.ai.agent.AgentUrgency
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_VIRTUAL_PARENT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.applyPlaceholders
import org.koin.android.ext.android.get
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
 * 2. 监督总闸 / onlyDuringSupervision 过滤（跳过时下一次照常排）；
 * 3. 按 conversationMode 找/建会话（reuse 复用常驻，fresh 每次新建）；
 * 4. 任务文本占位符展开 → 以 system 署名投递 `[schedule]` 系统消息到收件箱 + 唤醒
 *    （复用 [AgentBridge.deliver]，消息无条件入箱，目标空闲后自动开一轮生成）；
 * 5. 下一次触发由 [ScheduleAgentScheduler.scheduleNext] 在 Receiver 里排好（与执行成败解耦）。
 */
class ScheduleAgentRunner(
    private val context: Context,
    private val manager: ScheduleAgentManager,
    private val bridge: AgentBridge,
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

        // 冷启动（闹钟拉起进程）时 ChatService 可能还没被创建：先实例化它，
        // 让 AgentBridge.attach(Deps) 在 init 里完成，否则 dispatchWake 发不出消息。
        runCatching { context.get<ChatService>() }
            .onFailure { Log.w(TAG, "ChatService init failed", it) }

        val sup = settingsStore.settingsFlow.first().supervision
        // 监督总闸：监督期内总闸关闭 → 所有定时任务跳过（不只查岗）
        if (sup.isActiveNow() && !sup.scheduleAgentsEnabledDuringSupervision) {
            Log.i(TAG, "skip ${template.id}: schedule agents disabled during supervision")
            return
        }
        // 模板开关：仅监督时段内触发，非监督时段跳过
        if (template.onlyDuringSupervision && !sup.isActiveNow()) {
            Log.i(TAG, "skip ${template.id}: not in supervision window")
            return
        }

        val sessionId = resolveSession(template) ?: run {
            Log.e(TAG, "failed to resolve session for ${template.id}")
            return
        }

        val taskText = template.taskPrompt.applyPlaceholders(
            "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
            "name" to template.name,
        )
        val body = buildString {
            append("<from role=\"${AgentSenderRole.SYSTEM}\" title=\"定时任务：${template.name}\">")
            append("\n[schedule] ")
            append(taskText)
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

    /** reuse：找模板对应的常驻会话（会话被删则重建）；fresh：每次新建。 */
    private suspend fun resolveSession(template: ScheduleAgentTemplate): Uuid? {
        if (template.reuseConversation) {
            val row = agentSessionDao.getByTemplateId(template.id, SCHEDULE_VIRTUAL_PARENT_ID.toString())
            if (row != null) {
                val id = runCatching { Uuid.parse(row.childId) }.getOrNull()
                if (id != null && conversationRepo.getConversationById(id) != null) return id
            }
        }
        return bridge.spawnSchedule(template)
    }
}
