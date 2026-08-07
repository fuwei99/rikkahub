package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.AgentInboxDAO
import me.rerere.rikkahub.data.db.entity.AgentInboxEntity
import kotlin.uuid.Uuid

private const val TAG = "AgentInboxStore"

/**
 * 收件箱存储门面（方案 2026-08-07「多 Agent 通信内核」落地 plan Step 2）。
 *
 * 收敛设计的硬不变式在这里物化：
 * - **I1/I2**：[enqueue] 是跨对话消息的唯一落库口，纯 DB 写、**完全不看目标状态**，
 *   永远成功——「没唤醒/没抢占」从不等于「丢了」；
 * - **I3**：入库即返回，发送方永不因目标忙而阻塞；
 * - **I4**：未读全文只经 [takeUnread] 读取，读即标记已读，同一封信不会两次进上下文。
 *
 * 调度动作（唤醒/抢占）不在这里——本类只负责「信」，何时开口由 AgentMessageBus 决定。
 */
class AgentInboxStore(
    private val dao: AgentInboxDAO,
) {
    /**
     * 入箱（唯一写入口）。返回邮件 id；合并进已有未读时返回被合并行的 id。
     *
     * 超限合并（§10）：未读数达到 [AgentLimits.MAX_UNREAD_PER_TARGET] 后，
     * 新信正文 append 进最后一条未读，防止单个 agent 疯狂 report 撑爆。
     */
    suspend fun enqueue(
        target: Uuid,
        body: String,
        kind: AgentMessageKind,
        source: String,
        urgency: AgentUrgency = AgentUrgency.MAIL,
        senderId: Uuid? = null,
        senderTitle: String = "",
        templateId: String? = null,
    ): Long {
        val targetId = target.toString()
        val unread = dao.countUnread(targetId)
        if (unread >= AgentLimits.MAX_UNREAD_PER_TARGET) {
            val last = dao.lastUnread(targetId)
            if (last != null) {
                val merged = last.body + "\n\n[merged +${1}] " + body
                dao.updateBody(last.id, merged, System.currentTimeMillis())
                Log.w(TAG, "inbox of $targetId exceeds limit, merged into mail #${last.id}")
                return last.id
            }
        }
        return dao.insert(
            AgentInboxEntity(
                targetId = targetId,
                source = source,
                urgency = urgency.wire,
                kind = kind.name.lowercase(),
                senderId = senderId?.toString(),
                senderTitle = senderTitle,
                templateId = templateId,
                body = body,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 取走全部未读并标记已读（I4 的唯一读口）。无未读返回空列表。
     */
    suspend fun takeUnread(target: Uuid): List<AgentInboxEntity> {
        val targetId = target.toString()
        val rows = dao.getUnread(targetId)
        if (rows.isNotEmpty()) {
            dao.markRead(targetId, System.currentTimeMillis())
        }
        return rows
    }

    /** 未读数实时流（未读提示 transformer / UI 角标用） */
    fun unreadFlow(target: Uuid): Flow<Int> = dao.countUnreadFlow(target.toString())

    suspend fun countUnread(target: Uuid): Int = dao.countUnread(target.toString())

    /** 最大邮件 id：唤醒去重水位（同一批未读只唤醒一次，§6.2） */
    suspend fun maxMailId(target: Uuid): Long = dao.maxIdOf(target.toString())

    /** 清理：已读且早于阈值的行（随 agent 会话保留期清理一起跑，§10） */
    suspend fun deleteReadBefore(before: Long) = dao.deleteReadBefore(before)

    /** 目标对话删除时级联清空 */
    suspend fun deleteByTarget(target: Uuid) = dao.deleteByTarget(target.toString())
}
