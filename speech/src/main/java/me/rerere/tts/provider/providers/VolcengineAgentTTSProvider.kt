package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val TAG = "VolcengineAgentTTSProvider"

class VolcengineAgentTTSProvider : TTSProvider<TTSProviderSetting.VolcengineAgent> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.VolcengineAgent,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val payload = JSONObject().apply {
            put("req_params", JSONObject().apply {
                put("text", request.text)
                put("speaker", providerSetting.speaker.ifBlank { "zh_female_gaolengyujie_uranus_bigtts" })
                put("audio_params", JSONObject().apply {
                    put("format", providerSetting.format.ifBlank { "mp3" })
                    put("sample_rate", providerSetting.sampleRate)
                })
            })
        }

        val requestUrl = "${providerSetting.baseUrl.trimEnd('/')}/api/v3/plan/tts/unidirectional"

        val httpRequest = Request.Builder()
            .url(requestUrl)
            .addHeader("X-Api-Key", providerSetting.apiKey)
            .addHeader("X-Api-Resource-Id", providerSetting.resourceId.ifBlank { "seed-tts-2.0" })
            .addHeader("Content-Type", "application/json")
            .addHeader("Connection", "keep-alive")
            .addHeader("X-Control-Require-Usage-Tokens-Return", "*")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            Log.e(TAG, "Volcengine Agent TTS request failed: ${response.code} ${response.message}, body: $errorBody")
            throw Exception("Volcengine Agent TTS request failed: ${response.code} ${response.message}")
        }

        val inputStream = response.body.byteStream()
        val reader = BufferedReader(InputStreamReader(inputStream))
        val audioFormat = if (providerSetting.format.equals("wav", ignoreCase = true)) AudioFormat.WAV else AudioFormat.MP3

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isEmpty()) continue

                try {
                    val json = JSONObject(currentLine)
                    val code = json.optInt("code", 0)

                    if (code == 0 && json.has("data")) {
                        val base64Data = json.optString("data", "")
                        if (base64Data.isNotEmpty()) {
                            val chunkAudio = Base64.decode(base64Data, Base64.DEFAULT)
                            if (chunkAudio.isNotEmpty()) {
                                emit(
                                    AudioChunk(
                                        data = chunkAudio,
                                        format = audioFormat,
                                        isLast = false,
                                        metadata = mapOf(
                                            "provider" to "volcengine_agent",
                                            "speaker" to providerSetting.speaker
                                        )
                                    )
                                )
                            }
                        }
                    }

                    if (code == 20000000) {
                        break
                    }

                    if (code > 0 && code != 20000000) {
                        Log.e(TAG, "Volcengine Agent TTS streaming error code: $code response: $json")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse chunk line: $currentLine", e)
                }
            }

            // Final empty chunk mark
            emit(
                AudioChunk(
                    data = byteArrayOf(),
                    format = audioFormat,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "volcengine_agent",
                        "speaker" to providerSetting.speaker
                    )
                )
            )
        } finally {
            reader.close()
            inputStream.close()
        }
    }
}