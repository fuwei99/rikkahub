package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageProvider
import me.rerere.ai.provider.ImageProviderSetting
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

private const val TAG = "VolcengineImageProvider"
private const val MAX_TOTAL_IMAGES = 15
private const val DEFAULT_OUTPUT_FORMAT = "png"

// Ark result URLs expire within ~24h; request base64 so results can be persisted locally.
private const val DEFAULT_RESPONSE_FORMAT = "b64_json"

/**
 * 火山方舟 Coding Plan 图像服务。
 *
 * Plan 与普通 Ark 使用不同的 base URL；其图生图与文生图均使用
 * /images/generations，图生图只需额外提交 image 数组。
 */
class VolcengineImageProvider(
    private val client: OkHttpClient,
    context: Context? = null,
) : ImageProvider<ImageProviderSetting.Volcengine> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override suspend fun generateImage(
        providerSetting: ImageProviderSetting.Volcengine,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        validateImageCount(referenceImageCount = 0, outputImageCount = params.numOfImages)
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString(), providerSetting.keyStrategy)
        val requestBody = createRequestBody(
            modelId = params.model.modelId,
            prompt = params.prompt,
            size = params.size,
            outputImageCount = params.numOfImages,
            referenceImages = emptyList(),
            customBody = params.customBody,
        )
        val items = requestImages(providerSetting, key, requestBody, params.customHeaders)
        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ImageProviderSetting.Volcengine,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        require(params.images.isNotEmpty()) { "Volcengine image editing requires at least one reference image" }
        validateImageCount(
            referenceImageCount = params.images.size,
            outputImageCount = params.numOfImages,
        )
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString(), providerSetting.keyStrategy)
        val requestBody = createRequestBody(
            modelId = params.model.modelId,
            prompt = params.prompt,
            size = params.size,
            outputImageCount = params.numOfImages,
            referenceImages = params.images.map { it.toImageDataUriOrRemote() },
            customBody = params.customBody,
        )
        val items = requestImages(providerSetting, key, requestBody, params.customHeaders)
        items.forEach { emit(it) }
    }

    private fun validateImageCount(referenceImageCount: Int, outputImageCount: Int) {
        require(outputImageCount > 0) { "Volcengine output image count must be greater than 0" }
        require(referenceImageCount + outputImageCount <= MAX_TOTAL_IMAGES) {
            "Volcengine supports at most $MAX_TOTAL_IMAGES images in total (reference images + output images)"
        }
    }

    private fun createRequestBody(
        modelId: String,
        prompt: String,
        size: String,
        outputImageCount: Int,
        referenceImages: List<String>,
        customBody: List<me.rerere.ai.provider.CustomBody>,
    ): String = json.encodeToString(
        buildJsonObject {
            put("model", modelId)
            put("prompt", prompt)
            if (referenceImages.isNotEmpty()) {
                put("image", buildJsonArray {
                    referenceImages.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (outputImageCount != 1) put("n", outputImageCount)
            if (size.isNotBlank() && size != "auto") put("size", size)

            // Plan defaults: no watermark and base64 response for reliable local persistence.
            put("output_format", DEFAULT_OUTPUT_FORMAT)
            put("watermark", false)
            put("response_format", DEFAULT_RESPONSE_FORMAT)
        }.mergeCustomBody(customBody),
    )

    private suspend fun requestImages(
        providerSetting: ImageProviderSetting.Volcengine,
        key: String,
        requestBody: String,
        customHeaders: List<me.rerere.ai.provider.CustomHeader>,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        // Do not log the request body: image-to-image requests embed base64 reference images.
        Log.i(TAG, "Plan image request submitted (${requestBody.length} bytes)")
        val request = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/images/generations")
            .headers(customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()
        val response = client.newCall(request).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (response.code in KeyRoulette.KEY_FAILURE_CODES) {
                keyRoulette.reportFailure(providerSetting.id.toString(), key, response.code)
            }
            error("Failed to generate image from Volcengine Plan: ${response.code} $body")
        }
        parseImageResponse(body)
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: DEFAULT_OUTPUT_FORMAT
        val data = body["data"]?.jsonArray ?: error("No data in Volcengine Plan image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(data = b64Json, mimeType = outputFormat.toImageMimeType())
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in Volcengine Plan image response")
                ImageGenerationItem(
                    mimeType = defaultFormat.toImageMimeType(),
                    url = url,
                )
            }
        }
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}
