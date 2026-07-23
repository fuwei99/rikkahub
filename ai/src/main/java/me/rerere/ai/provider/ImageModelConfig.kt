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
)

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
