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
        Index(value = ["content_sha256"]),
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
    /**
     * 「像素内容」的 sha256：算之前先剥掉 PNG 的 tEXt/iTXt/zTXt 与 JPEG 的 APPn 段。
     *
     * [sha256] 是整字节摘要，一旦往图里写元数据（OCR 描述、标签等）它就变了，
     * 于是同一张图会被当成新文件反复入库。去重必须用一个对元数据免疫的摘要，
     * 这就是本列存在的理由。[sha256] 保持原样不动 —— 已有的 R2 对象靠它寻址。
     */
    @ColumnInfo("content_sha256")
    val contentSha256: String? = null,
    /**
     * 中文名（用户可改）。显示优先级：nameZh > displayName。
     *
     * 这里刻意只改名字不改磁盘文件：物理文件名永远是 UUID。
     * relative_path 既是多端同步的身份，也被历史会话里的 file:// 硬引用指着，
     * 动它等于制造死链。重命名因此退化成单列 UPDATE，天然原子。
     */
    @ColumnInfo("name_zh")
    val nameZh: String? = null,
    /** 英文名：只进搜索索引，不参与显示 */
    @ColumnInfo("name_en")
    val nameEn: String? = null,
    @ColumnInfo("prompt")
    val prompt: String? = null,
    @ColumnInfo("description")
    val description: String? = null,
    @ColumnInfo("ocr_text")
    val ocrText: String? = null,
    @ColumnInfo("deleted", defaultValue = "0")
    val deleted: Boolean = false,
)
