package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 独立记忆图节点。与 legacy MemoryEntity 完全分表，不能被传统记忆全量注入链路读取。
 *
 * match_eligibility（匹配资格分层，v42）：0=常驻池始终参与匹配，1=门控池需被
 * `unlocks` 边解锁后才参与匹配。见 [me.rerere.rikkahub.data.model.MemoryGraphMatchEligibility]。
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
    @ColumnInfo("match_eligibility", defaultValue = "0")
    val matchEligibility: Int = 0,
    @ColumnInfo("folder_path")
    val folderPath: String? = null,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
)
