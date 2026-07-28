package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GenMediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("prompt")
    val prompt: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo(name = "type", defaultValue = TYPE_IMAGE_GENERATION)
    val type: String = TYPE_IMAGE_GENERATION,
    @ColumnInfo("source_paths")
    val sourcePaths: String? = null,
    // ---- 云资产（P3）：镜像完成后回填；path 随之存 r2:// 引用或本地相对路径 ----
    @ColumnInfo("r2_key")
    val r2Key: String? = null,
    @ColumnInfo("r2_acct")
    val r2Acct: String? = null,
    /** 渠道原始返回 URL（会过期；镜像完成后渲染改走 r2://） */
    @ColumnInfo("original_url")
    val originalUrl: String? = null,
) {
    companion object {
        const val TYPE_IMAGE_GENERATION = "image_generation"
        const val TYPE_IMAGE_EDIT = "image_edit"
    }
}
