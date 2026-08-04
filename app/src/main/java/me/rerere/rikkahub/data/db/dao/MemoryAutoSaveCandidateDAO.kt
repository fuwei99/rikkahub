package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MemoryAutoSaveCandidateEntity

/**
 * 记忆自动提炼候选表 DAO（记忆图 P3）。
 * pending/failed 均可重试；processing 失败后 markFailed 下次重试。
 */
@Dao
interface MemoryAutoSaveCandidateDAO {
    @Insert
    suspend fun insert(candidate: MemoryAutoSaveCandidateEntity): Long

    @Query("SELECT * FROM memory_auto_save_candidate WHERE assistant_id = :assistantId AND status IN ('pending','failed') ORDER BY created_at ASC")
    suspend fun getPendingAndFailedByAssistant(assistantId: String): List<MemoryAutoSaveCandidateEntity>

    @Query("SELECT * FROM memory_auto_save_candidate WHERE status IN ('pending','failed') ORDER BY created_at ASC")
    suspend fun getPendingAndFailedAll(): List<MemoryAutoSaveCandidateEntity>

    @Query("UPDATE memory_auto_save_candidate SET status = 'processing' WHERE id IN (:ids)")
    suspend fun markProcessing(ids: List<Long>)

    @Query("UPDATE memory_auto_save_candidate SET status = 'failed', error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("DELETE FROM memory_auto_save_candidate WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM memory_auto_save_candidate WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistant(assistantId: String)

    @Query("SELECT COUNT(*) FROM memory_auto_save_candidate WHERE assistant_id = :assistantId AND status IN ('pending','failed')")
    suspend fun countPendingByAssistant(assistantId: String): Int
}
