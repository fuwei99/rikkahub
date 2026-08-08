package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Insert
    suspend fun insert(item: SyncOutboxEntity): Long

    /**
     * 可推送项：未进隔离区（永久失败次数未达上限）且已过退避时间。
     *
     * 对齐 [MediaUploadOutboxDAO.pending] 的 `next_attempt_at <= now` 语义：
     * 瞬时失败（没网/超时）只推迟，不判死刑。
     */
    @Query(
        "SELECT * FROM sync_outbox WHERE retry_count < :maxRetries AND next_attempt_at <= :now " +
            "ORDER BY created_at ASC LIMIT :limit"
    )
    suspend fun pending(
        now: Long,
        limit: Int = 50,
        maxRetries: Int = 5,
    ): List<SyncOutboxEntity>

    /** 隔离区：仅永久性失败达上限的项（数据本身有毒，重试无意义） */
    @Query("SELECT * FROM sync_outbox WHERE retry_count >= :maxRetries ORDER BY created_at ASC LIMIT :limit")
    suspend fun failedItems(limit: Int = 50, maxRetries: Int = 5): List<SyncOutboxEntity>

    /** 因退避而暂缓、但仍会自动重试的项数（UI 可据此区分「稍后重试」与「已隔离」） */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE retry_count < :maxRetries AND next_attempt_at > :now")
    suspend fun deferredCount(now: Long, maxRetries: Int = 5): Int

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * 永久性失败：累加 retry_count，达上限即进隔离区。
     * 同时也写退避时间，避免同一轮 flush 内反复撞同一条。
     */
    @Query(
        "UPDATE sync_outbox SET retry_count = retry_count + 1, transient_attempt = 0, " +
            "next_attempt_at = :nextAttemptAt, last_error = :error WHERE id = :id"
    )
    suspend fun markPermanentFailure(id: Long, error: String, nextAttemptAt: Long)

    /**
     * 瞬时失败：**不动** retry_count，只推进退避计数与下次尝试时间。
     * 没网、DNS 未就绪、超时、5xx 属于此类 —— 网络恢复后必须能自愈。
     */
    @Query(
        "UPDATE sync_outbox SET transient_attempt = transient_attempt + 1, " +
            "next_attempt_at = :nextAttemptAt, last_error = :error WHERE id = :id"
    )
    suspend fun markTransientFailure(id: Long, error: String, nextAttemptAt: Long)

    /**
     * 复活隔离区 + 清除所有退避：用户手动点同步时调用。
     *
     * 手动同步的语义就是「我知道之前失败了，现在再来一次」，
     * 因此必须给隔离项一条明确的重生路径 —— 否则用户只能靠再改一次会话
     * 触发 deleteByRef 才能救回数据（2026-08-08 故障根因）。
     */
    @Query("UPDATE sync_outbox SET retry_count = 0, transient_attempt = 0, next_attempt_at = 0")
    suspend fun reviveAll()

    /** 网络恢复等外部信号：只清退避，不碰隔离区 */
    @Query("UPDATE sync_outbox SET next_attempt_at = 0, transient_attempt = 0 WHERE retry_count < :maxRetries")
    suspend fun clearBackoff(maxRetries: Int = 5)

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE retry_count >= :maxRetries")
    fun quarantinedCountFlow(maxRetries: Int = 5): Flow<Int>

    @Query("DELETE FROM sync_outbox WHERE kind = :kind AND ref_key = :refKey")
    suspend fun deleteByRef(kind: String, refKey: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun clear()
}
