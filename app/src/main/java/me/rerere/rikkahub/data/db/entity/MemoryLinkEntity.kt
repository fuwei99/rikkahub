package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 记忆有向加权边（Memory Graph P1）。
 *
 * 与 [MemoryEntity] 的关联通过 source_id / target_id（Int，即 memoryentity.id）完成，
 * Room 不强制 FK——记忆表是整表 bundle 同步（先删后插），强 FK 反而会在同步窗口内误伤。
 *
 * 时间列（created_at / valid_from / valid_until / superseded_by_id）为 P3 的时间感知
 * 生命周期（Graphiti 双时态 / Mem0 supersede）预埋，避免二次迁移。
 *
 * 约束：链接只允许同 scope（assistant↔assistant、global↔global），
 * 跨 scope 链接会破坏同步/隔离语义（见方案 §6.1）。
 */
@Entity(
    tableName = "memory_link",
    indices = [
        Index("scope"),
        Index("source_id"),
        Index("target_id"),
    ]
)
data class MemoryLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("source_id")
    val sourceId: Int,
    @ColumnInfo("target_id")
    val targetId: Int,
    @ColumnInfo("type")
    val type: String = "related",
    @ColumnInfo("weight")
    val weight: Float = 0.7f,
    @ColumnInfo("description")
    val description: String = "",
    /** 记忆作用域: assistantId 或 MemoryRepository.GLOBAL_MEMORY_ID */
    @ColumnInfo("scope")
    val scope: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** 关系生效时间（P3 时间感知预留） */
    @ColumnInfo("valid_from")
    val validFrom: Long? = null,
    /** 关系失效时间（P3 supersede/decay 预留） */
    @ColumnInfo("valid_until")
    val validUntil: Long? = null,
    /** 若本边已被替代, 指向替代它的边的 id（保留时间线而非删除） */
    @ColumnInfo("superseded_by_id")
    val supersededById: Long? = null,
)
