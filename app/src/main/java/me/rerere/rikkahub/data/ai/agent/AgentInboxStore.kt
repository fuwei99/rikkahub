package me.rerere.rikkahub.data.ai.agent

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
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
 *
 * ## 落盘归档（2026-09-22）
 *
 * [enqueue] 写库之后，会额外把同一封信追加进 [AgentMailArchive]（明文 md）。
 * 归档是**纯旁路**：不参与 I4、不影响投递、失败不抛异常。它的唯一目的是让
 * 「读即已读」不再等于「历史消失」——见 [AgentMailArchive] 的类注释。
 */
class AgentInboxStore(
    private val dao: AgentInboxDAO,
    private val settingsStore: SettingsStore,
    /**
     * 落盘归档。**可空**：单测 / 未接线场景退化成「不归档」，主路 DB 行为不变。
     */
    private val archive: AgentMailArchive? = null,
) {
    /**
     * 入箱（唯一写入口）。返回邮件 id；合并进已有未读时返回被合并行的 id。
     *
     * 超限合并（§10）：未读数达到设置里的上限后，新信正文 append 进最后一条未读，
     * 防止单个 agent 疯狂 report 撑爆（2026-08-08 起上限从 AgentLimits 迁到通信设置可配）。
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
        val maxUnread = settingsStore.settingsFlow.first()
            .communication.maxUnreadPerTarget.coerceAtLeast(1)
        val unread = dao.countUnread(targetId)
        if (unread >= maxUnread) {
            val last = dao.lastUnread(targetId)
            if (last != null) {
                val now = System.currentTimeMillis()
                val merged = last.body + "\n\n[merged +${1}] " + body
                dao.updateBody(last.id, merged, now)
                // 合并路径同样要落档：否则合并进来的新信在归档里查不到。
                archive?.append(last.copy(body = merged, createdAt = now))
                Log.w(TAG, "inbox of $targetId exceeds limit, merged into mail #${last.id}")
                return last.id
            }
        }
        val entity = AgentInboxEntity(
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
        val id = dao.insert(entity)
        archive?.append(entity.copy(id = id))
        return id
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

    /**
     * 取走指定 id 的未读行并标记已读（await/join 消费用，2026-08-08 期三）。
     *
     * 与 [takeUnread] 的区别：只消费命中的信，**不碰其他未读**——
     * await 在攒批窗口内只取匹配发送方的信，其余留箱由 inbox 或下一次 await 处理（保 I4）。
     */
    suspend fun takeByIds(ids: List<Long>): List<AgentInboxEntity> {
        if (ids.isEmpty()) return emptyList()
        val rows = dao.getByIds(ids)
        if (rows.isNotEmpty()) {
            dao.markReadByIds(ids, System.currentTimeMillis())
        }
        return rows
    }

    /**
     * 只读未读全文，**不标记已读**（await/join 攒批窗口内 peek 用。
     * I8：消费动作延迟到返回前一刻才落已读，中途被取消/超时信保持未读）。
     */
    suspend fun peekUnread(target: Uuid): List<AgentInboxEntity> =
        dao.getUnread(target.toString())

    /** 未读数实时流（未读提示 transformer / UI 角标用） */
    fun unreadFlow(target: Uuid): Flow<Int> = dao.countUnreadFlow(target.toString())

    suspend fun countUnread(target: Uuid): Int = dao.countUnread(target.toString())

    /** 最大邮件 id：唤醒去重水位（同一批未读只唤醒一次，§6.2） */
    suspend fun maxMailId(target: Uuid): Long = dao.maxIdOf(target.toString())

    /** 清理：已读且早于阈值的行（随 agent 会话保留期清理一起跑，§10） */
    suspend fun deleteReadBefore(before: Long) = dao.deleteReadBefore(before)

    /** 目标对话删除时级联清空 */
    suspend fun deleteByTarget(target: Uuid) = dao.deleteByTarget(target.toString())

    /**
     * **非破坏性**读取：某个对话的全部来信（含已读），倒序。**绝不改 `read_at`。**
     *
     * 给外部（HTTP `/api/mail/inbox`、查岗）用。想要「消费」语义请用 [takeUnread]。
     */
    suspend fun listAll(target: Uuid, limit: Int = 50): List<AgentInboxEntity> =
        dao.getAllOf(target.toString(), limit.coerceIn(1, 200))

    /**
     * 归档文件绝对路径（`<filesDir>/agent-mail/<targetId>.md`）。
     *
     * 工具结果 / HTTP 响应把它回报出去，调用方就知道「完整历史在哪」，
     * 想搜直接 `rg`，不必再要一个查询接口。未接线归档时返回 null。
     */
    fun archivePath(target: Uuid): String? = archive?.pathFor(target.toString())
}
