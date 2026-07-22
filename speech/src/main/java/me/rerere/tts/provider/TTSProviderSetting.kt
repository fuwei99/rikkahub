package me.rerere.tts.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_TTS_FILTER_REGEX = "[#\\*\\/\\$%]"

@Serializable
data class TtsRegexRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val replaceWith: String = "",
    val enabled: Boolean = true
)

@Serializable
sealed class TTSProviderSetting {
    abstract val id: Uuid
    abstract val name: String
    abstract val filterRegex: String
    abstract val replaceWith: String
    abstract val regexRules: List<TtsRegexRule>
    abstract val playbackMode: String  // "stream" | "chunk"
    abstract val chunkLength: Int      // 可配置长度；0 代表不切片

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
        filterRegex: String = this.filterRegex,
        replaceWith: String = this.replaceWith,
        regexRules: List<TtsRegexRule> = this.regexRules,
        playbackMode: String = this.playbackMode,
        chunkLength: Int = this.chunkLength,
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        val speed: Float = 1.0f,
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        val model: String = "mimo-v2.5-tts",
        val voice: String = "mimo_default",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
            )
        }
    }

    @Serializable
    @SerialName("elevenlabs")
    data class ElevenLabs(
        override var id: Uuid = Uuid.random(),
        override var name: String = "ElevenLabs TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.elevenlabs.io",
        val model: String = "eleven_multilingual_v2",
        val voiceId: String = "JBFqnCBsd6RMkjVDRZzb",
        val stability: Float = 0.5f,
        val similarityBoost: Float = 0.75f,
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
            )
        }
    }

    /**
     * 阶跃星辰 Step TTS (step-tts-mini / step-tts-vivid / stepaudio-2.5-tts)。
     *
     * 与 Step ASR 共用同一个 baseUrl 与鉴权方式 (Authorization: Bearer sk-xxx),
     * 走 OpenAI 兼容的 [POST /v1/audio/speech] 非流式接口, 服务端一次性返回完整音频
     * 二进制 (默认 mp3, 也可选 wav/pcm/opus/flac)。客户端把整段音频包成一个 AudioChunk
     * 发出, 由 TtsSynthesizer 统一收集后交给播放器。
     *
     * 仅 stepaudio-2.5-tts 模型支持 [instruction] 字段 (全局语境, ≤200 字符), 其它模型
     * (step-tts-mini / step-tts-vivid / step-tts-2) 会忽略该字段, 留空时不下发。
     *
     * 官方文档:
     * - 模型总览: https://platform.stepfun.com/docs/zh/guides/models/stepaudio-2.5-tts
     * - 开发指南: https://platform.stepfun.com/docs/zh/guides/developer/tts
     */
    @Serializable
    @SerialName("step")
    data class Step(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Step TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.stepfun.com",
        // step-tts-mini | step-tts-vivid | stepaudio-2.5-tts | step-tts-2
        val model: String = "step-tts-mini",
        // 完整 voice-id 列表见开发指南; 默认值与官方 SDK 一致
        val voice: String = "elegantgentle-female",
        // mp3 | wav | pcm | opus | flac; 注意 StepFun API 使用 camelCase 字段名
        val responseFormat: String = "mp3",
        // 0.5 - 2.0, 1.0 为正常语速
        val speed: Float = 1.0f,
        // 0.1 - 2.0, 1.0 为正常音量
        val volume: Float = 1.0f,
        // 8000 | 16000 | 22050 | 24000
        val sampleRate: Int = 24000,
        // 仅 stepaudio-2.5-tts 生效; ≤200 字符, 留空时不下发
        val instruction: String = "",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
            )
        }
    }

    @Serializable
    @SerialName("fish-audio")
    data class FishAudio(
        override var id: Uuid = Uuid.random(),
        override var name: String = "Fish Audio TTS",
        val apiKey: String = "",
        val baseUrl: String = "https://api.fish.audio",
        val model: String = "s2.1-pro",
        val referenceId: String = "",
        val temperature: Float = 0.7f,
        val speed: Float = 1.0f,
        val format: String = "mp3",
        val topP: Float = 0.7f,
        override val chunkLength: Int = 300,
        val normalize: Boolean = true,
        val latency: String = "normal",
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream"
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
            )
        }
    }

    @Serializable
    data class VolcengineClonedSpeaker(
        val displayName: String,
        val speakerId: String
    )

    @Serializable
    @SerialName("volcengine-agent")
    data class VolcengineAgent(
        override var id: Uuid = Uuid.random(),
        override var name: String = "火山方舟Agent",
        val apiKey: String = "",
        val baseUrl: String = "https://openspeech.bytedance.com",
        val resourceId: String = "seed-tts-2.0",
        val speaker: String = "zh_female_gaolengyujie_uranus_bigtts",
        val format: String = "mp3",
        val sampleRate: Int = 24000,
        val clonedSpeakers: List<VolcengineClonedSpeaker> = emptyList(),
        override val filterRegex: String = DEFAULT_TTS_FILTER_REGEX,
        override val replaceWith: String = "",
        override val regexRules: List<TtsRegexRule> = emptyList(),
        override val playbackMode: String = "stream",
        override val chunkLength: Int = 160
    ) : TTSProviderSetting() {
        override fun copyProvider(
            id: Uuid,
            name: String,
            filterRegex: String,
            replaceWith: String,
            regexRules: List<TtsRegexRule>,
            playbackMode: String,
            chunkLength: Int,
        ): TTSProviderSetting {
            return this.copy(
                id = id,
                name = name,
                filterRegex = filterRegex,
                replaceWith = replaceWith,
                regexRules = regexRules,
                playbackMode = playbackMode,
                chunkLength = chunkLength
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
                ElevenLabs::class,
                Step::class,
                FishAudio::class,
                Doubao::class,
                VolcengineAgent::class,
            )
        }
    }
}

