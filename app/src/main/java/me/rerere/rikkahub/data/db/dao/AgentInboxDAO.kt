package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentInboxEntity

/**
 * 收件箱 DAO（方案 2026-08-07「多 Agent 通信内核」落地 plan Step 1）。
 *
 * 读写纪律：
 * - 写只有 [insert]（enqueue 唯一入口）与标记已读/清理；
 * - 未读全文只经 [getUnread] + [markRead] 配对读取（I4：读即已读，不得二次进上下文）。
 */
@Dao
interface AgentInboxDAO {
    /** @return 新行 rowid（即邮件 id） */
    @Insert
    suspend fun insert(entity: AgentInboxEntity): Long

    /** 未读数实时流（UI 角标 / 未读提示注入用） */
    @Query("SELECT COUNT(*) FROM agent_inbox WHERE target_id = :targetId AND read_at IS NULL")
    fun countUnreadFlow(targetId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM agent_inbox WHERE target_id = :targetId AND read_at IS NULL")
    suspend fun countUnread(targetId: String): Int

    /** 未读全文（按到达顺序）。调用方读完必须调 [markRead]。 */
    @Query("SELECT * FROM agent_inbox WHERE target_id = :targetId AND read_at IS NULL ORDER BY id ASC")
    suspend fun getUnread(targetId: String): List<AgentInboxEntity>

    /** 目标对话的最大邮件 id（唤醒去重水位用） */
    @Query("SELECT COALESCE(MAX(id), 0) FROM agent_inbox WHERE target_id = :targetId")
    suspend fun maxIdOf(targetId: String): Long

    @Query("UPDATE agent_inbox SET read_at = :readAt WHERE target_id = :targetId AND read_at IS NULL")
    suspend fun markRead(targetId: String, readAt: Long)

    /** 清理：已读且早于阈值的行（随 agent 会话保留期清理一起跑） */
    @Query("DELETE FROM agent_inbox WHERE read_at IS NOT NULL AND created_at < :before")
    suspend fun deleteReadBefore(before: Long)

    /** 目标对话删除时级联清空 */
    @Query("DELETE FROM agent_inbox WHERE target_id = :targetId")
    suspend fun deleteByTarget(targetId: String)

    /** 未读合并用：最后一条未读（超限报告合并进它） */
    @Query("SELECT * FROM agent_inbox WHERE target_id = :targetId AND read_at IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastUnread(targetId: String): AgentInboxEntity?

    @Query("UPDATE agent_inbox SET body = :body, created_at = :createdAt WHERE id = :id")
    suspend fun updateBody(id: Long, body: String, createdAt: Long)
}
