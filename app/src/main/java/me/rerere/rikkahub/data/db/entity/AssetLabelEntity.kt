package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 资产标签关联表：把「分类」和「标签」从物理落盘路径里解耦出来。
 *
 * 为什么需要它：
 * `managed_files.relative_path` 是 UNIQUE 且直接对应磁盘上的那一个文件，
 * 所以 `folder` 字段天生只能有一个值 —— 一张图不可能既在 upload 又在 ai_images。
 * 但业务上「AI 生成后又被当作附件上传的图」确实应该同时出现在两个分类里。
 *
 * 于是把归类关系挪到这张多对多表：
 * - kind = [KIND_FOLDER]：附加分类，值是 FileFolders 里的 folder 名。
 *   注意这是**附加**的，`managed_files.folder`（物理归属）仍然有效且照旧参与查询，
 *   本表只负责「除物理目录之外还应该出现在哪」，避免动存量数据。
 * - kind = [KIND_TAG]：用户标签，值是 ImageTag.id 的字符串形式。
 *
 * 删除资产时靠 FK CASCADE 自动清理，不需要业务代码记得删。
 */
@Entity(
    tableName = "asset_label_ref",
    primaryKeys = ["asset_id", "kind", "value"],
    foreignKeys = [
        ForeignKey(
            entity = ManagedFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["asset_id"]),
        Index(value = ["kind", "value"]),
    ],
)
data class AssetLabelEntity(
    @ColumnInfo("asset_id")
    val assetId: String,
    @ColumnInfo("kind")
    val kind: String,
    @ColumnInfo("value")
    val value: String,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_FOLDER = "folder"
        const val KIND_TAG = "tag"
    }
}
