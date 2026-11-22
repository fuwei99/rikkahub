package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable

@Serializable
data class AgentSessionAdminDto(
    val childId: String,
    val parentId: String,
    val rootId: String,
    val templateId: String,
    val status: String,
    val taskBrief: String,
    val depth: Int,
    val totalTokens: Int,
    val turnsWithParent: Int,
    val prematureEndCount: Int,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val lastSummary: String,
    val profileJson: String,
)

@Serializable
data class AgentSessionStatusPatch(
    val status: String,
)

@Serializable
data class ScheduleAgentModelPatch(
    val modelId: String? = null,
    val fallbackModelIds: List<String> = emptyList(),
    val syncSession: Boolean = true,
)

@Serializable
data class AgentSessionModelPatch(
    val modelId: String? = null,
    val fallbackModelIds: List<String> = emptyList(),
    val syncConversation: Boolean = true,
)

@Serializable
data class OkResponse(
    val ok: Boolean = true,
    val id: String? = null,
)
