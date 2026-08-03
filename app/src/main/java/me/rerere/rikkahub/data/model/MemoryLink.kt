package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 记忆链接（有向边）的对外模型, 附带两端节点内容摘要,
 * 供 memory_tool 的 link/query_links 结果回传（模型据此理解关系）。
 */
@Serializable
data class MemoryLink(
    val id: Long,
    val sourceId: Int,
    val sourceContent: String = "",
    val targetId: Int,
    val targetContent: String = "",
    val type: String = "related",
    val weight: Float = 0.7f,
    val description: String = "",
    val scope: String = "",
)
