package me.rerere.rikkahub.data.ai.schedule

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_VIRTUAL_PARENT_ID
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import kotlin.uuid.Uuid

private const val TAG = "ScheduleProtection"

/**
 * 「受保护的定时任务会话」判定与拦截（2026-08-21）。
 *
 * 单一真源：某个 conversationId 是不是受保护的定时任务会话，只由这里回答
 * （`agent_session.parent_id == SCHEDULE_VIRTUAL_PARENT_ID` + 模板 `protection.enabled`）。
 * ChatService / ConversationRepository / Web 路由 / UI 全部问它，避免同一规则在
 * 五个地方各写一遍然后漏掉一处（漏一处就是一个后门）。
 *
 * 纪律：
 * 1. **已归档会话不再保护**——否则 [me.rerere.rikkahub.data.ai.agent.AgentBridge] 的
 *    保留期清理与 Runner 的会话轮换会被自己的规则挡死；
 * 2. **内部动作一律 force 绕过**（轮换 archive、CALL 抢占等），只拦真人手点的入口；
 * 3. 判定失败（读表 / 读模板抛异常）一律**放行**：保护机制不该把 App 弄瘫。
 */
class ScheduleProtectionGuard(
    private val agentSessionDao: AgentSessionDAO,
    private val scheduleManager: ScheduleAgentManager,
) {
    /** 该对话生效的保护配置；null = 不是受保护的定时任务会话。 */
    suspend fun protectionOf(conversationId: Uuid): ScheduleProtection? = runCatching {
        val row = agentSessionDao.getByChildId(conversationId.toString()) ?: return null
        if (row.parentId != SCHEDULE_VIRTUAL_PARENT_ID.toString()) return null
        // 归档 = 任务已退役，放开所有限制（清理 / 轮换要靠它）
        if (row.status == AgentStatuses.ARCHIVED) return null
        scheduleManager.getTemplate(row.templateId)?.protection?.takeIf { it.enabled }
    }.onFailure { Log.w(TAG, "protectionOf failed for $conversationId", it) }.getOrNull()

    /**
     * 拦截判定。
     *
     * @return 错误文案（应当拒绝该动作）；null = 放行。
     */
    suspend fun blockReason(conversationId: Uuid, action: ScheduleAction): String? =
        protectionOf(conversationId)?.reasonFor(action)

    /** UI 用：当前所有未归档定时任务会话的保护配置快照流。 */
    val protectedSessionsFlow: Flow<Map<Uuid, ScheduleProtection>> =
        agentSessionDao.getVisibleFlow().map { rows ->
            val templates = runCatching { scheduleManager.listTemplates(includeDisabled = true) }
                .getOrDefault(emptyList())
                .associateBy { it.id }
            rows.asSequence()
                .filter { it.parentId == SCHEDULE_VIRTUAL_PARENT_ID.toString() }
                .mapNotNull { row ->
                    val id = runCatching { Uuid.parse(row.childId) }.getOrNull() ?: return@mapNotNull null
                    val protection = templates[row.templateId]?.protection?.takeIf { it.enabled }
                        ?: return@mapNotNull null
                    id to protection
                }
                .toMap()
        }
}
