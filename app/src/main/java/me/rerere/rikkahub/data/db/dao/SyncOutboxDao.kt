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

    @Query("SELECT * FROM sync_outbox WHERE retry_count < :maxRetries ORDER BY created_at ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50, maxRetries: Int = 5): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE retry_count >= :maxRetries ORDER BY created_at ASC LIMIT :limit")
    suspend fun failedItems(limit: Int = 50, maxRetries: Int = 5): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE sync_outbox SET retry_count = retry_count + 1, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM sync_outbox WHERE kind = :kind AND ref_key = :refKey")
    suspend fun deleteByRef(kind: String, refKey: String)

    @Query("DELETE FROM sync_outbox")
    suspend fun clear()
}
