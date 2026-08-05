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

    @Query("UPDATE memory_auto_save_candidate SET status = 'processing', processing_at = :nowMs WHERE id IN (:ids)")
    suspend fun markProcessing(ids: List<Long>, nowMs: Long = System.currentTimeMillis())

    @Query("UPDATE memory_auto_save_candidate SET status = 'failed', error = :error, retry_count = COALESCE(retry_count, 0) + 1, processing_at = NULL WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    /** 崩溃遗留的 processing 候选超时后恢复为 pending 重新排队（对齐 Operit 失败保留重试语义） */
    @Query("UPDATE memory_auto_save_candidate SET status = 'pending', processing_at = NULL WHERE status = 'processing' AND (processing_at IS NULL OR processing_at < :cutoffMs)")
    suspend fun recoverStaleProcessing(cutoffMs: Long): Int

    /** 重试次数超限的直接丢弃，防止死循环 */
    @Query("DELETE FROM memory_auto_save_candidate WHERE status = 'failed' AND retry_count >= :maxRetries")
    suspend fun dropExhausted(maxRetries: Int): Int

    @Query("DELETE FROM memory_auto_save_candidate WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM memory_auto_save_candidate WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistant(assistantId: String)

    @Query("SELECT COUNT(*) FROM memory_auto_save_candidate WHERE assistant_id = :assistantId AND status IN ('pending','failed')")
    suspend fun countPendingByAssistant(assistantId: String): Int
}
