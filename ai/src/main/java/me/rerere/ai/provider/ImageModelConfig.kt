package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ImageModelCapabilities(
    val supportsImageEditing: Boolean = false,
    val maxReferenceImages: Int = 0,
    val loraProtocol: WaveSpeedLoraProtocol = WaveSpeedLoraProtocol.NONE,
    /** Optional private Hugging Face token, used only by P-Image WEIGHT_SCALE requests. */
    val pImageHfApiToken: String = "",
    val maxLoras: Int = 0,
    /**
     * Which OpenAI-compatible API the model speaks. AUTO keeps legacy behavior
     * (NewAPI providers -> chat/completions, others -> images API with chat fallback).
     */
    val apiDialect: ImageApiDialect = ImageApiDialect.AUTO,
    /**
     * 单次请求最多可生成的输出张数；0 或 1 表示不支持多张（一次只出一张）。
     * 预置模型按官方能力填好默认值（目前：TokenRhythm / 火山方舟），
     * 用户在模型编辑器的「能力」页可自行修改。
     */
    val maxOutputImages: Int = 0,
)

/** OpenAI-compatible image API dialect, configured per model instead of guessed from provider type. */
@Serializable
enum class ImageApiDialect {
    AUTO,
    IMAGES_API,
    CHAT_COMPLETIONS,
}

@Serializable
enum class WaveSpeedLoraProtocol {
    NONE,
    PATH_SCALE_ARRAY,
    WEIGHT_SCALE,
}

@Serializable
data class ImageModelParameter(
    val key: String,
    val explanation: String,
    val defaultValue: JsonElement? = null,
)

@Serializable
data class ImageModelIdMapping(
    val parameterKey: String,
    val parameterValue: String,
    val modelId: String,
)

@Serializable
data class WaveSpeedLora(
    val id: String,
    val explanation: String,
    val url: String,
)

/** Returns configured model parameter defaults in a form that can be merged into an API request. */
fun Model.defaultImageParameterBodies(): List<CustomBody> = imageParameters.mapNotNull { parameter ->
    parameter.defaultValue?.let { defaultValue ->
        CustomBody(key = parameter.key, value = defaultValue)
    }
}
