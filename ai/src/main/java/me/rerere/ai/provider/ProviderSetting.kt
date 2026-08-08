package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption

    /**
     * 云同步合并用：本渠道最后修改时间（epoch millis）。
     * 0 = 从未打过时间戳的旧数据，合并时按“无版本”处理。
     * 必须参与序列化，否则跨设备无法做逐项 LWW。
     */
    abstract val updatedAt: Long

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
        updatedAt: Long = this.updatedAt,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val updatedAt: Long = 0L,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
        /** 多 Token 轮换策略（LLM 渠道同样支持轮询/随机/失败切换）。 */
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
            updatedAt: Long,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                balanceOption = balanceOption,
                shortDescription = shortDescription,
                updatedAt = updatedAt,
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val updatedAt: Long = 0L,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
        /** 多 Token 轮换策略（LLM 渠道同样支持轮询/随机/失败切换）。 */
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
            updatedAt: Long,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption,
                updatedAt = updatedAt,
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        override val updatedAt: Long = 0L,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
        /** 多 Token 轮换策略（LLM 渠道同样支持轮询/随机/失败切换）。 */
        var keyStrategy: KeyStrategy = KeyStrategy.ROUND_ROBIN,
        /** 失败重试次数（含首次尝试，默认最多尝试 3 次）。 */
        var retryCount: Int = 3,
        /** 失败重试间隔（秒，默认 1 秒）。 */
        var retryIntervalSec: Int = 1,
        /** 命中即关闭（禁用）该 Token 的报错码，默认 401/403/422。 */
        var closeOnCodes: List<Int> = listOf(401, 403, 422),
        /** 手动关闭（开关关闭）的 Token，保留在列表里但不会被轮换使用。 */
        var disabledTokens: List<String> = emptyList(),
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
            updatedAt: Long,
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                updatedAt = updatedAt,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}

private val LLM_SPLIT_API_KEY_REGEX = Regex("[\\s,]+") // 空格换行和逗号

/** 把 LLM 渠道的 apiKey 字符串按空白/逗号拆成多个 Token（去空、去重）。 */
val ProviderSetting.apiKeyTokens: List<String>
    get() = when (this) {
        is ProviderSetting.OpenAI -> apiKey.split(LLM_SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
        is ProviderSetting.Google -> apiKey.split(LLM_SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
        is ProviderSetting.Claude -> apiKey.split(LLM_SPLIT_API_KEY_REGEX).filter { it.isNotBlank() }.distinct()
    }

/** 用一组 Token（每行一个）重写 LLM 渠道的 apiKey 字符串；保留空条目以便编辑。 */
fun ProviderSetting.withApiKeyTokens(tokens: List<String>): ProviderSetting {
    val joined = tokens.joinToString("\n")
    return when (this) {
        is ProviderSetting.OpenAI -> copy(apiKey = joined)
        is ProviderSetting.Google -> copy(apiKey = joined)
        is ProviderSetting.Claude -> copy(apiKey = joined)
    }
}

/** 当前 LLM 渠道的 Token 轮换策略。 */
val ProviderSetting.keyStrategy: KeyStrategy
    get() = when (this) {
        is ProviderSetting.OpenAI -> this.keyStrategy
        is ProviderSetting.Google -> this.keyStrategy
        is ProviderSetting.Claude -> this.keyStrategy
    }

/** 修改当前 LLM 渠道的 Token 轮换策略。 */
fun ProviderSetting.withKeyStrategy(strategy: KeyStrategy): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(keyStrategy = strategy)
    is ProviderSetting.Google -> copy(keyStrategy = strategy)
    is ProviderSetting.Claude -> copy(keyStrategy = strategy)
}

/** 当前 LLM 渠道手动关闭（开关关闭）的 Token 列表。 */
val ProviderSetting.disabledTokens: List<String>
    get() = when (this) {
        is ProviderSetting.OpenAI -> this.disabledTokens
        is ProviderSetting.Google -> this.disabledTokens
        is ProviderSetting.Claude -> this.disabledTokens
    }

/** 修改当前 LLM 渠道手动关闭的 Token 列表。 */
fun ProviderSetting.withDisabledTokens(tokens: List<String>): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(disabledTokens = tokens)
    is ProviderSetting.Google -> copy(disabledTokens = tokens)
    is ProviderSetting.Claude -> copy(disabledTokens = tokens)
}

/** 当前 LLM 渠道的失败重试次数（含首次尝试）。 */
val ProviderSetting.retryCount: Int
    get() = when (this) {
        is ProviderSetting.OpenAI -> this.retryCount
        is ProviderSetting.Google -> this.retryCount
        is ProviderSetting.Claude -> this.retryCount
    }

/** 修改当前 LLM 渠道的失败重试次数。 */
fun ProviderSetting.withRetryCount(count: Int): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(retryCount = count)
    is ProviderSetting.Google -> copy(retryCount = count)
    is ProviderSetting.Claude -> copy(retryCount = count)
}

/** 当前 LLM 渠道的失败重试间隔（秒）。 */
val ProviderSetting.retryIntervalSec: Int
    get() = when (this) {
        is ProviderSetting.OpenAI -> this.retryIntervalSec
        is ProviderSetting.Google -> this.retryIntervalSec
        is ProviderSetting.Claude -> this.retryIntervalSec
    }

/** 修改当前 LLM 渠道的失败重试间隔（秒）。 */
fun ProviderSetting.withRetryIntervalSec(seconds: Int): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(retryIntervalSec = seconds)
    is ProviderSetting.Google -> copy(retryIntervalSec = seconds)
    is ProviderSetting.Claude -> copy(retryIntervalSec = seconds)
}

/** 当前 LLM 渠道「报错即关闭」的报错码（命中后禁用该 Token，而不是删除）。 */
val ProviderSetting.closeOnCodes: List<Int>
    get() = when (this) {
        is ProviderSetting.OpenAI -> this.closeOnCodes
        is ProviderSetting.Google -> this.closeOnCodes
        is ProviderSetting.Claude -> this.closeOnCodes
    }

/** 修改当前 LLM 渠道「报错即关闭」的报错码。 */
fun ProviderSetting.withCloseOnCodes(codes: List<Int>): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(closeOnCodes = codes)
    is ProviderSetting.Google -> copy(closeOnCodes = codes)
    is ProviderSetting.Claude -> copy(closeOnCodes = codes)
}
