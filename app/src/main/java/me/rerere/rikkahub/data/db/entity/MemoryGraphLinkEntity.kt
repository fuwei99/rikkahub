package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 独立记忆图有向边；source/target 只引用 memory_graph_node.id。 */
@Entity(
    tableName = "memory_graph_link",
    indices = [
        Index("scope"),
        Index("source_id"),
        Index("target_id"),
        Index(value = ["scope", "source_id", "target_id", "type"], unique = true),
    ],
)
data class MemoryGraphLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("scope")
    val scope: String,
    @ColumnInfo("source_id")
    val sourceId: Long,
    @ColumnInfo("target_id")
    val targetId: Long,
    @ColumnInfo("type")
    val type: String = "related",
    @ColumnInfo("weight")
    val weight: Float = 0.7f,
    @ColumnInfo("description")
    val description: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
)
