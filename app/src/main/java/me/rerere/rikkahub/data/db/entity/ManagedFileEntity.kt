package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "managed_files",
    indices = [
        Index(value = ["relative_path"], unique = true),
        Index(value = ["folder"])
    ]
)
data class ManagedFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
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
    // ---- 云资产（P3）：上传 R2 后回填，attachment 对象引用 = r2://<r2Acct>/<r2Key> ----
    @ColumnInfo("r2_key")
    val r2Key: String? = null,
    @ColumnInfo("r2_acct")
    val r2Acct: String? = null,
)
