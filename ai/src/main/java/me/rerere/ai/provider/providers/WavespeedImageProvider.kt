package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageProvider
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.WaveSpeedLoraProtocol
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "WavespeedImageProvider"

class WavespeedImageProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : ImageProvider<ImageProviderSetting.Wavespeed> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override suspend fun generateImage(
        providerSetting: ImageProviderSetting.Wavespeed,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("prompt", params.prompt)
                val sizeVal = params.size
                if (sizeVal.isNotBlank() && sizeVal != "auto") {
                    put("size", sizeVal)
                }
                addLoras(params.model, params.loras)

            }.mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "generateImage task submit")

        val modelId = params.model.modelId.trimStart('/')
        val submitUrl = if (modelId.startsWith("http://") || modelId.startsWith("https://")) {
            modelId
        } else {
            "${providerSetting.baseUrl.trimEnd('/')}/$modelId"
        }

        val submitRequest = Request.Builder()
            .url(submitUrl)
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val pollUrl = withContext(Dispatchers.IO) {
            val response = client.newCall(submitRequest).await()
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                error("Failed to submit image task to WaveSpeed: ${response.code} $responseBody")
            }
            parseSubmitResponse(responseBody, providerSetting.baseUrl)
        }

        Log.i(TAG, "polling result from: $pollUrl")

        val imageUrls = withContext(Dispatchers.IO) {
            pollTaskResult(pollUrl, key, params.customHeaders)
        }

        // WaveSpeed returns CDN URLs in `outputs`; preserve them instead of downloading and
        // expanding images into Base64 in memory.
        val items = imageUrls.map { url ->
            ImageGenerationItem(mimeType = "image/png", url = url)
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ImageProviderSetting.Wavespeed,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(params.model.imageCapabilities.supportsImageEditing) {
            "The selected WaveSpeed model does not support image editing"
        }
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("prompt", params.prompt)
                val imagesArray = kotlinx.serialization.json.buildJsonArray {
                    params.images.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
                put("images", imagesArray)
                val sizeVal = params.size
                if (sizeVal.isNotBlank() && sizeVal != "auto") {
                    put("size", sizeVal)
                }
                require(!params.model.imageCapabilities.supportsImageEditing ||
                    params.model.imageCapabilities.maxReferenceImages <= 0 ||
                    params.images.size <= params.model.imageCapabilities.maxReferenceImages
                ) { "This WaveSpeed model allows at most ${params.model.imageCapabilities.maxReferenceImages} reference images" }
                addLoras(params.model, params.loras)
            }.mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "editImage task submit")

        val modelId = params.model.modelId.trimStart('/')
        val submitUrl = if (modelId.startsWith("http://") || modelId.startsWith("https://")) {
            modelId
        } else {
            "${providerSetting.baseUrl.trimEnd('/')}/$modelId"
        }

        val submitRequest = Request.Builder()
            .url(submitUrl)
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val pollUrl = withContext(Dispatchers.IO) {
            val response = client.newCall(submitRequest).await()
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                error("Failed to submit image edit task to WaveSpeed: ${response.code} $responseBody")
            }
            parseSubmitResponse(responseBody, providerSetting.baseUrl)
        }

        Log.i(TAG, "polling result from: $pollUrl")

        val imageUrls = withContext(Dispatchers.IO) {
            pollTaskResult(pollUrl, key, params.customHeaders)
        }

        // WaveSpeed returns CDN URLs in `outputs`; preserve them instead of downloading and
        // expanding images into Base64 in memory.
        val items = imageUrls.map { url ->
            ImageGenerationItem(mimeType = "image/png", url = url)
        }

        items.forEach { emit(it) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.addLoras(model: me.rerere.ai.provider.Model, loras: List<me.rerere.ai.provider.ImageLoraSelection>) {
        if (loras.isEmpty()) return
        require(model.imageCapabilities.maxLoras > 0) { "This WaveSpeed model does not support LoRA" }
        require(loras.size <= model.imageCapabilities.maxLoras) {
            "This WaveSpeed model allows at most ${model.imageCapabilities.maxLoras} LoRAs per generation"
        }
        when (model.imageCapabilities.loraProtocol) {
            WaveSpeedLoraProtocol.PATH_SCALE_ARRAY -> put("loras", kotlinx.serialization.json.buildJsonArray {
                loras.forEach { lora -> add(buildJsonObject { put("path", lora.path); put("scale", lora.scale) }) }
            })
            WaveSpeedLoraProtocol.WEIGHT_SCALE -> {
                val lora = loras.single()
                put("lora_weights", lora.path)
                put("lora_scale", lora.scale)
                model.imageCapabilities.pImageHfApiToken
                    .takeIf { it.isNotBlank() }
                    ?.let { put("hf_api_token", it) }
            }
            WaveSpeedLoraProtocol.NONE -> error("This WaveSpeed model does not support LoRA")
        }
    }

    private fun parseSubmitResponse(bodyStr: String, baseUrl: String): String {
        val bodyObj = json.parseToJsonElement(bodyStr).jsonObject
        val dataObj = bodyObj["data"]?.jsonObject ?: bodyObj
        val urlsObj = dataObj["urls"]?.jsonObject
        val getUrl = urlsObj?.get("get")?.jsonPrimitive?.contentOrNull
        if (!getUrl.isNullOrBlank()) {
            return getUrl
        }
        val taskId = dataObj["id"]?.jsonPrimitive?.contentOrNull
            ?: error("No task id or urls.get returned in WaveSpeed response")
        return "${baseUrl.trimEnd('/')}/predictions/$taskId/result"
    }

    private suspend fun pollTaskResult(
        pollUrl: String,
        apiKey: String,
        customHeaders: List<CustomHeader>
    ): List<String> {
        var pollDelay = 2000L
        val maxAttempts = 60

        for (attempt in 1..maxAttempts) {
            val request = Request.Builder()
                .url(pollUrl)
                .headers(customHeaders.toHeaders())
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            val response = client.newCall(request).await()
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                error("Failed to query task result from WaveSpeed: ${response.code} $bodyStr")
            }

            val bodyObj = json.parseToJsonElement(bodyStr).jsonObject
            val dataObj = bodyObj["data"]?.jsonObject ?: bodyObj
            val status = dataObj["status"]?.jsonPrimitive?.contentOrNull ?: ""

            when (status.lowercase()) {
                "completed" -> {
                    val outputs = dataObj["outputs"]?.jsonArray
                        ?: error("No outputs array in completed WaveSpeed response")
                    return outputs.mapNotNull { it.jsonPrimitive.contentOrNull }
                }
                "failed", "cancelled", "timeout" -> {
                    val errorMsg = dataObj["error"]?.jsonPrimitive?.contentOrNull
                        ?: "Task ended with status: $status"
                    error("WaveSpeed task generation failed: $errorMsg")
                }
                else -> {
                    // created or processing, wait and poll again
                    delay(pollDelay)
                    pollDelay = (pollDelay + 1000L).coerceAtMost(10000L)
                }
            }
        }
        error("Timed out waiting for WaveSpeed image generation result")
    }

}
