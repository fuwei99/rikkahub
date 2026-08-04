package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

/** 多 Token 轮换策略，作用于渠道下配置的多个 API Token。 */
@Serializable
enum class KeyStrategy {
    /** 轮询：按最近使用时间依次循环，尽量均衡分摊每个 Token。 */
    ROUND_ROBIN,

    /** 随机：每次随机挑选一个 Token。 */
    RANDOM,

    /**
     * 失败切换：固定使用第一个可用 Token，仅当请求返回 401/403/422/429 时才切换到下一个；
     * 其中 422 视为额度耗尽，该 Token 会被永久剔除（并从设置中自动删除）。
     */
    FAILOVER,
}

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
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
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
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
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
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
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
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
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
    @SerialName("tokenrhythm-imggen")
    data class TokenRhythm(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "TokenRhythm",
        override var models: List<Model> = emptyList(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://tokenrhythm.studio/v1",
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
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
                TokenRhythm::class,
            )
        }
    }
}

private val SPLIT_API_KEY_REGEX = Regex("[\\s,]+") // 空格换行和逗号

/** 把渠道的 apiKey 字符串按空白/逗号拆成多个 Token（去空、去重）。 */
val ImageProviderSetting.apiKeyTokens: List<String>
    get() = when (this) {
        is ImageProviderSetting.OpenAI -> apiKey.split(SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
        is ImageProviderSetting.NewAPI -> apiKey.split(SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
        is ImageProviderSetting.Volcengine -> apiKey.split(SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
        is ImageProviderSetting.Wavespeed -> apiKey.split(SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
        is ImageProviderSetting.TokenRhythm -> apiKey.split(SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
    }

/** 用一组 Token（每行一个）重写渠道的 apiKey 字符串；保留空条目以便编辑。 */
fun ImageProviderSetting.withApiKeyTokens(tokens: List<String>): ImageProviderSetting {
    val joined = tokens.joinToString("\n")
    return when (this) {
        is ImageProviderSetting.OpenAI -> copy(apiKey = joined)
        is ImageProviderSetting.NewAPI -> copy(apiKey = joined)
        is ImageProviderSetting.Volcengine -> copy(apiKey = joined)
        is ImageProviderSetting.Wavespeed -> copy(apiKey = joined)
        is ImageProviderSetting.TokenRhythm -> copy(apiKey = joined)
    }
}

/** 当前渠道的 Token 轮换策略。 */
val ImageProviderSetting.keyStrategy: KeyStrategy
    get() = when (this) {
        is ImageProviderSetting.OpenAI -> this.keyStrategy
        is ImageProviderSetting.NewAPI -> this.keyStrategy
        is ImageProviderSetting.Volcengine -> this.keyStrategy
        is ImageProviderSetting.Wavespeed -> this.keyStrategy
        is ImageProviderSetting.TokenRhythm -> this.keyStrategy
    }

/** 修改当前渠道的 Token 轮换策略。 */
fun ImageProviderSetting.withKeyStrategy(strategy: KeyStrategy): ImageProviderSetting = when (this) {
    is ImageProviderSetting.OpenAI -> copy(keyStrategy = strategy)
    is ImageProviderSetting.NewAPI -> copy(keyStrategy = strategy)
    is ImageProviderSetting.Volcengine -> copy(keyStrategy = strategy)
    is ImageProviderSetting.Wavespeed -> copy(keyStrategy = strategy)
    is ImageProviderSetting.TokenRhythm -> copy(keyStrategy = strategy)
}
