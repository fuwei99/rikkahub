package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable

@Serializable
data class SubagentTemplate(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val systemPrompt: String? = null,
    val defaultTools: List<String> = listOf("workspace"),
    val maxSteps: Int = 50,
    val timeoutMinutes: Int = 15,
    val recommendedModel: ModelOverride? = null,
)

@Serializable
data class ModelOverride(
    val providerName: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String? = null, // "off", "on", "auto", "low", "medium", "high", "max"
)

