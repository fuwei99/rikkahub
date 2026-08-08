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
     * 其中命中 closeOnCodes（默认 401/403/422）的 Token 会被**关闭**（保留但禁用，不再自动删除）。
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
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
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
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
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
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
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
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
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
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
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

/** 当前渠道手动关闭（开关关闭）的 Token 列表。 */
val ImageProviderSetting.disabledTokens: List<String>
    get() = when (this) {
        is ImageProviderSetting.OpenAI -> this.disabledTokens
        is ImageProviderSetting.NewAPI -> this.disabledTokens
        is ImageProviderSetting.Volcengine -> this.disabledTokens
        is ImageProviderSetting.Wavespeed -> this.disabledTokens
        is ImageProviderSetting.TokenRhythm -> this.disabledTokens
    }

/** 修改当前渠道手动关闭的 Token 列表。 */
fun ImageProviderSetting.withDisabledTokens(tokens: List<String>): ImageProviderSetting = when (this) {
    is ImageProviderSetting.OpenAI -> copy(disabledTokens = tokens)
    is ImageProviderSetting.NewAPI -> copy(disabledTokens = tokens)
    is ImageProviderSetting.Volcengine -> copy(disabledTokens = tokens)
    is ImageProviderSetting.Wavespeed -> copy(disabledTokens = tokens)
    is ImageProviderSetting.TokenRhythm -> copy(disabledTokens = tokens)
}

/** 当前渠道的失败重试次数（含首次尝试）。 */
val ImageProviderSetting.retryCount: Int
    get() = when (this) {
        is ImageProviderSetting.OpenAI -> this.retryCount
        is ImageProviderSetting.NewAPI -> this.retryCount
        is ImageProviderSetting.Volcengine -> this.retryCount
        is ImageProviderSetting.Wavespeed -> this.retryCount
        is ImageProviderSetting.TokenRhythm -> this.retryCount
    }

/** 修改当前渠道的失败重试次数。 */
fun ImageProviderSetting.withRetryCount(count: Int): ImageProviderSetting = when (this) {
    is ImageProviderSetting.OpenAI -> copy(retryCount = count)
    is ImageProviderSetting.NewAPI -> copy(retryCount = count)
    is ImageProviderSetting.Volcengine -> copy(retryCount = count)
    is ImageProviderSetting.Wavespeed -> copy(retryCount = count)
    is ImageProviderSetting.TokenRhythm -> copy(retryCount = count)
}

/** 当前渠道的失败重试间隔（秒）。 */
val ImageProviderSetting.retryIntervalSec: Int
    get() = when (this) {
        is ImageProviderSetting.OpenAI -> this.retryIntervalSec
        is ImageProviderSetting.NewAPI -> this.retryIntervalSec
        is ImageProviderSetting.Volcengine -> this.retryIntervalSec
        is ImageProviderSetting.Wavespeed -> this.retryIntervalSec
        is ImageProviderSetting.TokenRhythm -> this.retryIntervalSec
    }

/** 修改当前渠道的失败重试间隔（秒）。 */
fun ImageProviderSetting.withRetryIntervalSec(seconds: Int): ImageProviderSetting = when (this) {
    is ImageProviderSetting.OpenAI -> copy(retryIntervalSec = seconds)
    is ImageProviderSetting.NewAPI -> copy(retryIntervalSec = seconds)
    is ImageProviderSetting.Volcengine -> copy(retryIntervalSec = seconds)
    is ImageProviderSetting.Wavespeed -> copy(retryIntervalSec = seconds)
    is ImageProviderSetting.TokenRhythm -> copy(retryIntervalSec = seconds)
}

/** 当前渠道「报错即关闭」的报错码（命中后禁用该 Token，而不是删除）。 */
val ImageProviderSetting.closeOnCodes: List<Int>
    get() = when (this) {
        is ImageProviderSetting.OpenAI -> this.closeOnCodes
        is ImageProviderSetting.NewAPI -> this.closeOnCodes
        is ImageProviderSetting.Volcengine -> this.closeOnCodes
        is ImageProviderSetting.Wavespeed -> this.closeOnCodes
        is ImageProviderSetting.TokenRhythm -> this.closeOnCodes
    }

/** 修改当前渠道「报错即关闭」的报错码。 */
fun ImageProviderSetting.withCloseOnCodes(codes: List<Int>): ImageProviderSetting = when (this) {
    is ImageProviderSetting.OpenAI -> copy(closeOnCodes = codes)
    is ImageProviderSetting.NewAPI -> copy(closeOnCodes = codes)
    is ImageProviderSetting.Volcengine -> copy(closeOnCodes = codes)
    is ImageProviderSetting.Wavespeed -> copy(closeOnCodes = codes)
    is ImageProviderSetting.TokenRhythm -> copy(closeOnCodes = codes)
}
