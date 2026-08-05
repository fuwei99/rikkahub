package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MemoryGraphScope {
    ASSISTANT,
    GLOBAL,
}

@Serializable
data class MemoryGraphNode(
    val id: Long,
    val scope: String,
    val title: String,
    val content: String,
    val importance: Float = 0.5f,
    val credibility: Float = 0.5f,
    val folderPath: String? = null,
)

@Serializable
data class MemoryGraphLink(
    val id: Long,
    val scope: String,
    val sourceId: Long,
    val targetId: Long,
    val sourceTitle: String = "",
    val targetTitle: String = "",
    val type: String = "related",
    val weight: Float = 0.7f,
    val description: String = "",
)

data class MemoryGraphData(
    val nodes: List<MemoryGraphNode> = emptyList(),
    val links: List<MemoryGraphLink> = emptyList(),
)

data class MemoryGraphSearchHit(
    val node: MemoryGraphNode,
    val score: Float,
)
