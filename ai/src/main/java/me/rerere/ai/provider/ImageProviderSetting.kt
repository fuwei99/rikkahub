package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
sealed class ImageProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>

    abstract val builtIn: Boolean
    abstract val description: @Composable () -> Unit
    abstract val shortDescription: @Composable () -> Unit

    // Model list operations are shared by all provider types and expressed via copyProvider,
    // so new provider subclasses only need to implement copyProvider.
    fun addModel(model: Model): ImageProviderSetting =
        copyProvider(models = models + model)

    fun editModel(model: Model): ImageProviderSetting =
        copyProvider(models = models.map { if (it.id == model.id) model.copy() else it })

    fun delModel(model: Model): ImageProviderSetting =
        copyProvider(models = models.filter { it.id != model.id })

    fun moveModel(from: Int, to: Int): ImageProviderSetting =
        copyProvider(models = models.toMutableList().apply {
            val m = removeAt(from)
            add(to, m)
        })

    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ImageProviderSetting

    @Serializable
    @SerialName("openai-imggen")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI DALL-E",
        override var models: List<Model> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
    ) : ImageProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ImageProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("newapi-imggen")
    data class NewAPI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "NewAPI 生图",
        override var models: List<Model> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://your-newapi-server/v1",
    ) : ImageProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ImageProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("volcengine-imggen")
    data class Volcengine(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "火山方舟生图",
        override var models: List<Model> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://ark.cn-beijing.volces.com/api/plan/v3",
    ) : ImageProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ImageProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    @Serializable
    @SerialName("wavespeed-imggen")
    data class Wavespeed(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "WaveSpeed AI",
        override var models: List<Model> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.wavespeed.ai/api/v3",
    ) : ImageProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ImageProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            builtIn = builtIn,
            description = description,
            shortDescription = shortDescription,
        )
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                NewAPI::class,
                Volcengine::class,
                Wavespeed::class,
            )
        }
    }
}
