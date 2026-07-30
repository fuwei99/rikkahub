package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MediaUploadOutboxEntity

@Dao
interface MediaUploadOutboxDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: MediaUploadOutboxEntity)

    @Query("SELECT * FROM media_upload_outbox WHERE next_attempt_at <= :now ORDER BY created_at ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int = 8): List<MediaUploadOutboxEntity>

    @Query("DELETE FROM media_upload_outbox WHERE asset_id = :assetId")
    suspend fun delete(assetId: String): Int

    @Query("DELETE FROM media_upload_outbox")
    suspend fun deleteAll()

    @Query("UPDATE media_upload_outbox SET retry_count = retry_count + 1, last_error = :error, updated_at = :updatedAt, next_attempt_at = :nextAttemptAt WHERE asset_id = :assetId")
    suspend fun markFailed(assetId: String, error: String, updatedAt: Long, nextAttemptAt: Long)
}
