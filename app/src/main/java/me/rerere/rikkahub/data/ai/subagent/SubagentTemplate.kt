package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable

@Serializable
data class SubagentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String? = null,
    val defaultTools: String = "workspace",
    val maxSteps: Int = 50,
    val timeoutMinutes: Int = 15,
    val recommendedModel: ModelOverride? = null,
)

@Serializable
data class ModelOverride(
    val provider: String? = null,
    val modelId: String? = null,
)
