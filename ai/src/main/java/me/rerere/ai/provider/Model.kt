package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // Image-only configuration. Other model types keep the defaults and ignore these fields.
    val imageCapabilities: ImageModelCapabilities = ImageModelCapabilities(),
    val imageParameters: List<ImageModelParameter> = emptyList(),
    val waveSpeedLoras: List<WaveSpeedLora> = emptyList(),
)

/** Capabilities are declared per image model rather than inferred from its provider. */
@Serializable
data class ImageModelCapabilities(
    val supportsImageEditing: Boolean = false,
    val maxReferenceImages: Int = 0,
    val loraProtocol: WaveSpeedLoraProtocol = WaveSpeedLoraProtocol.NONE,
    val maxLoras: Int = 0,
)

/** LoRA payloads vary even between WaveSpeed models, so this is model-level metadata. */
@Serializable
enum class WaveSpeedLoraProtocol {
    NONE,
    PATH_SCALE_ARRAY,
    WEIGHT_SCALE,
)

/** A provider-native image parameter that an LLM may select at tool-call time. */
@Serializable
data class ImageModelParameter(
    val key: String,
    val explanation: String,
    val defaultValue: JsonElement? = null,
)

/** A WaveSpeed LoRA is configured once by the user and referenced by its short ID. */
@Serializable
data class WaveSpeedLora(
    val id: String,
    val explanation: String,
    val url: String,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



