package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

/**
 * 向量模型服务设置（记忆图 Phase 2）。
 *
 * 与生图服务 / 搜索服务 / 语音服务并列的独立服务区块。
 * 当前只有 OpenAI 兼容类型（`POST {baseUrl}/embeddings`）——
 * 火山方舟（/api/plan/v3 Plan 订阅、/api/v3 免费额度）、Fireworks、
 * 阿里百炼、智谱、OpenAI 等全部兼容，后续如需本地 ONNX 等再加类型。
 */
@Serializable
sealed class VectorProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>

    abstract val builtIn: Boolean
    abstract val description: @Composable () -> Unit
    abstract val shortDescription: @Composable () -> Unit

    // Model list operations are shared by all provider types and expressed via copyProvider,
    // so new provider subclasses only need to implement copyProvider.
    fun addModel(model: Model): VectorProviderSetting =
        copyProvider(models = models + model)

    fun editModel(model: Model): VectorProviderSetting =
        copyProvider(models = models.map { if (it.id == model.id) model.copy() else it })

    fun delModel(model: Model): VectorProviderSetting =
        copyProvider(models = models.filter { it.id != model.id })

    fun moveModel(from: Int, to: Int): VectorProviderSetting =
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
    ): VectorProviderSetting

    @Serializable
    @SerialName("openai-vec")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI 兼容向量服务",
        override var models: List<Model> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
    ) : VectorProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): VectorProviderSetting = copy(
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
            )
        }
    }
}
