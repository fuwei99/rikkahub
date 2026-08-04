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
import me.rerere.ai.provider.apiKeyTokens
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.ai.util.toImageDataUriOrRemote
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
        Log.i(TAG, "generateImage task submit")

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("prompt", params.prompt)
                putWaveSpeedSize(params.size)
                addLoras(params.model, params.loras)
            }.mergeCustomBody(params.customBody)
        )

        val modelId = params.model.modelId.trimStart('/')
        val (key, pollUrl) = withContext(Dispatchers.IO) {
            submitTask(providerSetting, requestBody, modelId, params.customHeaders)
        }

        Log.i(TAG, "polling result from: $pollUrl")

        val imageUrls = withContext(Dispatchers.IO) {
            pollTaskResult(pollUrl, key, providerSetting.id.toString(), params.customHeaders)
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
        val maxReferences = params.model.imageCapabilities.maxReferenceImages
        require(maxReferences <= 0 || params.images.size <= maxReferences) {
            "This WaveSpeed model allows at most $maxReferences reference images"
        }

        Log.i(TAG, "editImage task submit")

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("prompt", params.prompt)
                // Local chat attachments must be converted to data URIs; WaveSpeed cannot
                // read device file paths.
                val imagesArray = kotlinx.serialization.json.buildJsonArray {
                    params.images.forEach {
                        add(kotlinx.serialization.json.JsonPrimitive(it.toImageDataUriOrRemote()))
                    }
                }
                put("images", imagesArray)
                putWaveSpeedSize(params.size)
                addLoras(params.model, params.loras)
            }.mergeCustomBody(params.customBody)
        )

        val modelId = params.model.modelId.trimStart('/')
        val (key, pollUrl) = withContext(Dispatchers.IO) {
            submitTask(providerSetting, requestBody, modelId, params.customHeaders)
        }

        Log.i(TAG, "polling result from: $pollUrl")

        val imageUrls = withContext(Dispatchers.IO) {
            pollTaskResult(pollUrl, key, providerSetting.id.toString(), params.customHeaders)
        }

        // WaveSpeed returns CDN URLs in `outputs`; preserve them instead of downloading and
        // expanding images into Base64 in memory.
        val items = imageUrls.map { url ->
            ImageGenerationItem(mimeType = "image/png", url = url)
        }

        items.forEach { emit(it) }
    }

    /**
     * 提交生成/编辑任务，内置 Token 轮换：仅当响应码为 401/403/422/429 时才切换到下一个
     * Token（422 视为额度耗尽，永久剔除并记录，由调用方从设置里删除）；其它错误码立即抛出。
     */
    private suspend fun submitTask(
        providerSetting: ImageProviderSetting.Wavespeed,
        requestBody: String,
        modelId: String,
        customHeaders: List<CustomHeader>,
    ): Pair<String, String> {
        val providerId = providerSetting.id.toString()
        val keyCount = providerSetting.apiKeyTokens.size.coerceAtLeast(1)
        var lastCode = 0
        var lastBody = ""

        val submitUrl = if (modelId.startsWith("http://") || modelId.startsWith("https://")) {
            modelId
        } else {
            "${providerSetting.baseUrl.trimEnd('/')}/$modelId"
        }

        for (attempt in 1..keyCount) {
            val key = keyRoulette.next(providerSetting.apiKey, providerId, providerSetting.keyStrategy)
            val request = Request.Builder()
                .url(submitUrl)
                .headers(customHeaders.toHeaders())
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build()

            val response = client.newCall(request).await()
            val responseBody = response.body.string()
            if (response.isSuccessful) {
                val pollUrl = parseSubmitResponse(responseBody, providerSetting.baseUrl)
                Log.i(TAG, "task submitted with ${key.take(6)}…, poll: $pollUrl")
                return key to pollUrl
            }
            lastCode = response.code
            lastBody = responseBody
            if (response.code in KeyRoulette.KEY_FAILURE_CODES) {
                keyRoulette.reportFailure(providerId, key, response.code)
                Log.w(TAG, "WaveSpeed key failed (HTTP ${response.code}), rotating: ${key.take(6)}…")
            } else {
                error("Failed to submit image task to WaveSpeed: ${response.code} $responseBody")
            }
        }
        error("All WaveSpeed tokens failed (HTTP $lastCode): $lastBody")
    }

    /** WaveSpeed uses `1024*1024`-style sizes; accept the common `1024x1024` form too. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putWaveSpeedSize(size: String) {
        if (size.isNotBlank() && size != "auto") {
            put("size", size.replace('x', '*'))
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.addLoras(model: me.rerere.ai.provider.Model, loras: List<me.rerere.ai.provider.ImageLoraSelection>) {
        if (loras.isEmpty()) return
        require(model.imageCapabilities.loraProtocol != WaveSpeedLoraProtocol.NONE) { "This WaveSpeed model does not support LoRA" }
        when (model.imageCapabilities.loraProtocol) {
            WaveSpeedLoraProtocol.PATH_SCALE_ARRAY -> put("loras", kotlinx.serialization.json.buildJsonArray {
                loras.forEach { lora -> add(buildJsonObject { put("path", lora.path); put("scale", lora.scale) }) }
            })
            WaveSpeedLoraProtocol.WEIGHT_SCALE -> {
                val lora = loras.first()
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
        providerId: String,
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
                if (response.code in KeyRoulette.KEY_FAILURE_CODES) {
                    keyRoulette.reportFailure(providerId, apiKey, response.code)
                }
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
