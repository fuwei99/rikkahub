package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_upload_outbox")
data class MediaUploadOutboxEntity(
    @PrimaryKey
    @ColumnInfo("asset_id")
    val assetId: String,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo("next_attempt_at")
    val nextAttemptAt: Long = 0L,
    @ColumnInfo("retry_count")
    val retryCount: Int = 0,
    @ColumnInfo("last_error")
    val lastError: String? = null,
)
