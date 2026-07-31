package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val isReasoningEnabled: Boolean = true,
    val toolCallingStrategy: ToolCallingStrategy = ToolCallingStrategy.NATIVE,
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    val imageCapabilities: ImageModelCapabilities = ImageModelCapabilities(),
    /** User-facing note injected into the image-generation tool description to distinguish similar image models. */
    val imageDescription: String = "",
    /** Optional system prompt used by NewAPI chat image models. */
    val imageSystemPrompt: String = "",
    /** Optional model ID routing table, e.g. resolution=4K -> provider-specific 4K model ID. */
    val imageModelIdMappings: List<ImageModelIdMapping> = emptyList(),
    val imageParameters: List<ImageModelParameter> = emptyList(),
    val waveSpeedLoras: List<WaveSpeedLora> = emptyList(),
)
