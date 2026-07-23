package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Model-provider built-in tool options. */
@Serializable
sealed class BuiltInTools {
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}
