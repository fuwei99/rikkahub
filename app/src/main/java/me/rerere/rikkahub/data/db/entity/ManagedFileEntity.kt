package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(
    tableName = "managed_files",
    indices = [
        Index(value = ["relative_path"], unique = true),
        Index(value = ["folder"]),
        Index(value = ["r2_key", "r2_acct"]),
        Index(value = ["external_url"]),
        Index(value = ["sha256"]),
    ]
)
data class ManagedFileEntity(
    @PrimaryKey
    val id: String = Uuid.random().toString(),
    @ColumnInfo("folder")
    val folder: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("r2_key")
    val r2Key: String? = null,
    @ColumnInfo("r2_acct")
    val r2Acct: String? = null,
    @ColumnInfo("external_url")
    val externalUrl: String? = null,
    @ColumnInfo("sha256")
    val sha256: String? = null,
    @ColumnInfo("prompt")
    val prompt: String? = null,
    @ColumnInfo("description")
    val description: String? = null,
    @ColumnInfo("ocr_text")
    val ocrText: String? = null,
    @ColumnInfo("deleted", defaultValue = "0")
    val deleted: Boolean = false,
)
