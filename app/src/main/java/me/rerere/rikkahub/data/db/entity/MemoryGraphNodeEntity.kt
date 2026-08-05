package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 独立记忆图节点。与 legacy MemoryEntity 完全分表，不能被传统记忆全量注入链路读取。
 */
@Entity(
    tableName = "memory_graph_node",
    indices = [
        Index("scope"),
        Index(value = ["scope", "title"]),
    ],
)
data class MemoryGraphNodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** assistant id 或 MemoryGraphRepository.GLOBAL_SCOPE */
    @ColumnInfo("scope")
    val scope: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("importance")
    val importance: Float = 0.5f,
    @ColumnInfo("credibility")
    val credibility: Float = 0.5f,
    @ColumnInfo("folder_path")
    val folderPath: String? = null,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
)
