package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_TTS_FILTER_REGEX = "[#\\*\\/\\$%]"

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String
    abstract val filterRegex: String

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
        filterRegex: String = this.filterRegex,
    ): TTSProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "OpenAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini-tts",
        val voice: String = "alloy",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("gemini")
    data class Gemini(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Gemini TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        val model: String = "gemini-2.5-flash-preview-tts",
        val voiceName: String = "Kore",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("system")
    data class SystemTTS(
        override var id: Uuid = Uuid.random(),
        override var name: String = "System TTS",
        val speechRate: Float = 1.0f,
        val pitch: Float = 1.0f,
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiniMax TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.minimaxi.com/v1",
        val model: String = "speech-2.6-turbo",
        val voiceId: String = "female-shaonv",
        val emotion: String = "calm",
        val speed: Float = 1.0f,
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("qwen")
    data class Qwen(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Qwen TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
        val model: String = "qwen3-tts-flash",
        val voice: String = "Cherry",
        val languageType: String = "Auto",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("groq")
    data class Groq(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Groq TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.groq.com/openai/v1",
        val model: String = "canopylabs/orpheus-v1-english",
        val voice: String = "austin",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("xai")
    data class XAI(
        override var id: Uuid = Uuid.random(),
        override var name: String = "xAI TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.x.ai/v1",
        val voiceId: String = "eve",
        val language: String = "auto",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("mimo")
    // 默认值仅用于快捷起步 可在设置页任意修改
    data class MiMo(
        override var id: Uuid = Uuid.random(),
        override var name: String = "MiMo TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.xiaomimimo.com/v1",
        val model: String = "mimo-v2-tts",
        val voice: String = "mimo_default",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    @Serializable
    @SerialName("doubao")
    data class Doubao(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Doubao TTS",
        val apiKey: String = "sk-wei123",
        val baseUrl: String = "http://localhost:1547/v1",
        val voice: String = "female-shaonv",
        val speed: Float = 1.0f,
        val pitch: Float = 0.0f,
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Gemini::class,
                SystemTTS::class,
                MiniMax::class,
                Qwen::class,
                Groq::class,
                XAI::class,
                MiMo::class,
                Doubao::class,
            )
        }
    }
}
