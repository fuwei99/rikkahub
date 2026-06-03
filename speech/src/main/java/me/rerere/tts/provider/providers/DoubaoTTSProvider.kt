package me.rerere.tts.provider.providers

import android.content.Context
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
import java.util.concurrent.TimeUnit

private const val TAG = "DoubaoTTSProvider"

class DoubaoTTSProvider : TTSProvider<TTSProviderSetting.Doubao> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Doubao,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = JSONObject().apply {
            put("model", "tts-1")
            put("input", request.text)
            put("voice", providerSetting.voice)
            put("response_format", "aac")
            put("speed", providerSetting.speed.toDouble())
            put("pitch", providerSetting.pitch.toDouble())
        }

        Log.i(TAG, "generateSpeech: $requestBody")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl}/audio/speech")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "Doubao TTS request failed: ${response.code} ${response.message}, body: $errorBody")
            throw Exception("Doubao TTS request failed: ${response.code} ${response.message}")
        }

        val byteStream = response.body?.byteStream() ?: throw Exception("Response body is null")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        try {
            while (byteStream.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead > 0) {
                    emit(
                        AudioChunk(
                            data = buffer.copyOf(bytesRead),
                            format = AudioFormat.AAC,
                            isLast = false,
                            metadata = mapOf(
                                "provider" to "doubao",
                                "voice" to providerSetting.voice,
                                "speed" to providerSetting.speed.toString(),
                                "pitch" to providerSetting.pitch.toString()
                            )
                        )
                    )
                }
            }
            // Emit final empty chunk
            emit(
                AudioChunk(
                    data = byteArrayOf(),
                    format = AudioFormat.AAC,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "doubao",
                        "voice" to providerSetting.voice
                    )
                )
            )
        } finally {
            byteStream.close()
        }
    }
}
