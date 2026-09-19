package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.plugin.TtsPluginHost
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "CustomJsTTSProvider"

/**
 * JS 插件 TTS provider。
 *
 * 把用户的脚本丢进 [TtsPluginHost]（QuickJS）执行，拿回 base64 音频，包成一个
 * [AudioChunk] 发出。**一次性返回整段音频**，不做流式分片 —— 因为 QuickJS 是同步模型，
 * 脚本里没法边收边 yield。分片交给下游的 TextChunker（`chunkLength`）。
 *
 * 失败语义：脚本抛异常 → 整个 flow 抛出，由上层 TtsController 捕获后走错误提示，
 * 不会静默吞掉（这是刻意的，插件调试期最怕静默失败）。
 */
class CustomJsTTSProvider : TTSProvider<TTSProviderSetting.CustomJs> {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.CustomJs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val script = providerSetting.script
        if (script.isBlank()) {
            error("Custom JS TTS: 脚本为空，请先在设置里填写插件脚本")
        }

        val requestJson = buildJsonObject {
            put("text", request.text)
            put("format", providerSetting.format)
            put("sampleRate", providerSetting.sampleRate)
        }.toString()

        val varsJson = buildJsonObject {
            providerSetting.vars.forEach { (key, value) -> put(key, value) }
        }.toString()

        Log.i(TAG, "generateSpeech: vars=${providerSetting.vars.keys}, format=${providerSetting.format}")

        val resultJson = TtsPluginHost(httpClient).run(script, requestJson, varsJson)

        val obj = json.parseToJsonElement(resultJson).jsonObject
        val base64Audio = obj["base64"]?.jsonPrimitive?.contentOrNull
            ?: error("Custom JS TTS: 插件返回值缺少 base64 字段（应返回 { base64, format, sampleRate }）")

        val audioBytes = try {
            Base64.getDecoder().decode(base64Audio)
        } catch (e: IllegalArgumentException) {
            error("Custom JS TTS: base64 解码失败 —— ${e.message}")
        }
        if (audioBytes.isEmpty()) {
            error("Custom JS TTS: 插件返回了空音频")
        }

        val format = obj["format"]?.jsonPrimitive?.contentOrNull
            ?.let { parseAudioFormat(it) }
            ?: parseAudioFormat(providerSetting.format)
        val sampleRate = obj["sampleRate"]?.jsonPrimitive?.intOrNull
            ?: providerSetting.sampleRate

        emit(
            AudioChunk(
                data = audioBytes,
                format = format,
                sampleRate = sampleRate,
                isLast = true,
                metadata = mapOf(
                    "provider" to "custom-js",
                    "name" to providerSetting.name
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun parseAudioFormat(raw: String): AudioFormat = when (raw.trim().lowercase()) {
        "mp3" -> AudioFormat.MP3
        "wav" -> AudioFormat.WAV
        "ogg" -> AudioFormat.OGG
        "aac" -> AudioFormat.AAC
        "opus" -> AudioFormat.OPUS
        "pcm" -> AudioFormat.PCM
        else -> AudioFormat.MP3
    }
}
