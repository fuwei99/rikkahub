package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AgentSessionEntity

@Dao
interface AgentSessionDAO {
    @Query("SELECT * FROM agent_session WHERE child_id = :childId")
    suspend fun getByChildId(childId: String): AgentSessionEntity?

    /** 实时观察单条 agent 会话（UI 卡片用） */
    @Query("SELECT * FROM agent_session WHERE child_id = :childId")
    fun getByChildIdFlow(childId: String): Flow<AgentSessionEntity?>

    @Query("SELECT * FROM agent_session WHERE parent_id = :parentId ORDER BY created_at DESC")
    suspend fun getByParent(parentId: String): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_session WHERE root_id = :rootId ORDER BY created_at DESC")
    suspend fun getByRoot(rootId: String): List<AgentSessionEntity>

    /** 抽屉 Agent 组：归档的不出现 */
    @Query("SELECT * FROM agent_session WHERE status != 'archived' ORDER BY created_at DESC")
    fun getVisibleFlow(): Flow<List<AgentSessionEntity>>

    /** 主对话内的 agent 组：某个父对话派出的、未归档的 agent（抽屉分组用） */
    @Query("SELECT * FROM agent_session WHERE root_id = :rootId AND status != 'archived' ORDER BY created_at DESC")
    fun getVisibleByRootFlow(rootId: String): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_session ORDER BY created_at DESC")
    suspend fun getAll(): List<AgentSessionEntity>

    @Query("SELECT child_id FROM agent_session")
    suspend fun getAllChildIds(): List<String>

    @Query("SELECT COUNT(*) FROM agent_session WHERE parent_id = :parentId AND status IN ('running','idle','waiting_parent','waiting_approval')")
    suspend fun countActiveOfParent(parentId: String): Int

    @Query("SELECT COUNT(*) FROM agent_session WHERE status IN ('running','idle','waiting_parent','waiting_approval')")
    suspend fun countActiveGlobal(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AgentSessionEntity)

    @Query("UPDATE agent_session SET status = :status WHERE child_id = :childId")
    suspend fun updateStatus(childId: String, status: String)

    @Query("UPDATE agent_session SET status = :status, last_summary = :summary, total_tokens = :totalTokens, finished_at = :finishedAt WHERE child_id = :childId")
    suspend fun updateProgress(
        childId: String,
        status: String,
        summary: String,
        totalTokens: Int,
        finishedAt: Long?,
    )

    @Query("UPDATE agent_session SET turns_with_parent = turns_with_parent + 1 WHERE child_id = :childId")
    suspend fun incrementTurns(childId: String)

    @Query("UPDATE agent_session SET premature_end_count = premature_end_count + 1 WHERE child_id = :childId")
    suspend fun incrementPrematureEnd(childId: String)

    @Query("UPDATE agent_session SET peers = :peers WHERE child_id = :childId")
    suspend fun updatePeers(childId: String, peers: String)

    @Query("DELETE FROM agent_session WHERE child_id = :childId")
    suspend fun deleteByChildId(childId: String)

    /** 保留期清理：finished_at 早于阈值的归档会话行 */
    @Query("SELECT child_id FROM agent_session WHERE status IN ('archived','done','failed','error','stopped') AND finished_at IS NOT NULL AND finished_at < :before")
    suspend fun getExpired(before: Long): List<String>
}
