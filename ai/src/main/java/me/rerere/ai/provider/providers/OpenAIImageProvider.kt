package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageProvider
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
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

private const val TAG = "OpenAIImageProvider"

class OpenAIImageProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : ImageProvider<ImageProviderSetting> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val ImageProviderSetting.openAICompatibleApiKey: String
        get() = when (this) {
            is ImageProviderSetting.OpenAI -> apiKey
            is ImageProviderSetting.NewAPI -> apiKey
            else -> error("Unsupported chat image provider: ${this::class.simpleName}")
        }

    private val ImageProviderSetting.openAICompatibleBaseUrl: String
        get() = when (this) {
            is ImageProviderSetting.OpenAI -> baseUrl
            is ImageProviderSetting.NewAPI -> baseUrl
            else -> error("Unsupported chat image provider: ${this::class.simpleName}")
        }

    private data class RoutedImageRequest(
        val modelId: String,
        val customBody: List<CustomBody>,
    )

    private fun Model.routeImageRequest(customBody: List<CustomBody>): RoutedImageRequest {
        if (imageModelIdMappings.isEmpty()) {
            return RoutedImageRequest(modelId = modelId, customBody = customBody)
        }
        val routedKeys = imageModelIdMappings.map { it.parameterKey }.filter { it.isNotBlank() }.toSet()
        val selectedModelId = imageModelIdMappings.firstNotNullOfOrNull { mapping ->
            val actualValue = customBody.lastOrNull { it.key == mapping.parameterKey }
                ?.value
                ?.let { value -> runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: value.toString() }
                ?.trim()
            if (actualValue.equals(mapping.parameterValue.trim(), ignoreCase = true)) {
                mapping.modelId.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } ?: modelId
        return RoutedImageRequest(
            modelId = selectedModelId,
            customBody = customBody.filterNot { it.key in routedKeys },
        )
    }

    override suspend fun generateImage(
        providerSetting: ImageProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        val key = keyRoulette.next(providerSetting.openAICompatibleApiKey, providerSetting.id.toString())
        val routedRequest = params.model.routeImageRequest(params.customBody)

        Log.i(TAG, "generateImage task submit")

        val items = withContext(Dispatchers.IO) {
            if (params.model.usesChatCompletionsImageApi(providerSetting)) {
                fallbackChatCompletions(providerSetting, params, key, routedRequest)
            } else {
                val requestBody = json.encodeToString(
                    buildJsonObject {
                        put("model", routedRequest.modelId)
                        put("prompt", params.prompt)
                        put("n", params.numOfImages)
                        if (params.size.isNotBlank() && params.size != "auto") {
                            put("size", params.size)
                        }
                    }.mergeCustomBody(routedRequest.customBody)
                )
                val request = Request.Builder()
                    .url("${providerSetting.openAICompatibleBaseUrl.trimEnd('/')}/images/generations")
                    .headers(params.customHeaders.toHeaders())
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .configureReferHeaders(providerSetting.openAICompatibleBaseUrl)
                    .build()

                val response = client.newCall(request).await()
                val responseBodyStr = response.body.string()
                if (!response.isSuccessful) {
                    // 尝试用 chat/completions 回退（适应类似 Vertex/gemini-3.1-flash-lite-image 这种 OpenAI chat/completions 生图模型）
                    runCatching {
                        fallbackChatCompletions(providerSetting, params, key, routedRequest)
                    }.getOrElse {
                        error("Failed to generate image: ${response.code} $responseBodyStr")
                    }
                } else {
                    parseImageResponse(responseBodyStr)
                }
            }
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ImageProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        val key = keyRoulette.next(providerSetting.openAICompatibleApiKey, providerSetting.id.toString())
        val routedRequest = params.model.routeImageRequest(params.customBody)

        // 优先使用 chat/completions 回退兼容 (适应 Vertex/Gemini 等多模态图生图模型)
        val items = withContext(Dispatchers.IO) {
            runCatching {
                fallbackChatCompletionsEdit(providerSetting, params, key, routedRequest)
            }.getOrElse {
                error("Failed to edit image: ${it.message}")
            }
        }

        items.forEach { emit(it) }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun Model.usesChatCompletionsImageApi(providerSetting: ImageProviderSetting): Boolean =
        providerSetting is ImageProviderSetting.NewAPI || imageSystemPrompt.isNotBlank()

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun fallbackChatCompletions(
        providerSetting: ImageProviderSetting,
        params: ImageGenerationParams,
        key: String,
        routedRequest: RoutedImageRequest,
    ): List<ImageGenerationItem> {
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", routedRequest.modelId)
                put("messages", buildJsonArray {
                    params.model.imageSystemPrompt.takeIf { it.isNotBlank() }?.let { systemPrompt ->
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", params.prompt)
                    })
                })
            }.mergeCustomBody(routedRequest.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.openAICompatibleBaseUrl.trimEnd('/')}/chat/completions")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.openAICompatibleBaseUrl)
            .build()

        val response = client.newCall(request).await()
        val responseBodyStr = response.body.string()
        if (!response.isSuccessful) {
            error("Chat completions image generation failed: ${response.code} $responseBodyStr")
        }

        return parseChatCompletionsImageResponse(responseBodyStr)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun fallbackChatCompletionsEdit(
        providerSetting: ImageProviderSetting,
        params: ImageEditParams,
        key: String,
        routedRequest: RoutedImageRequest,
    ): List<ImageGenerationItem> {
        val contentArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", params.prompt)
            })
            params.images.forEach { imgPath ->
                val b64Data = if (imgPath.startsWith("data:image") || imgPath.startsWith("http://") || imgPath.startsWith("https://")) {
                    imgPath
                } else {
                    val file = java.io.File(imgPath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        "data:image/jpeg;base64,${Base64.encode(bytes)}"
                    } else {
                        imgPath
                    }
                }
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", b64Data)
                    })
                })
            }
        }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", routedRequest.modelId)
                put("messages", buildJsonArray {
                    params.model.imageSystemPrompt.takeIf { it.isNotBlank() }?.let { systemPrompt ->
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", contentArray)
                    })
                })
            }.mergeCustomBody(routedRequest.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.openAICompatibleBaseUrl.trimEnd('/')}/chat/completions")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.openAICompatibleBaseUrl)
            .build()

        val response = client.newCall(request).await()
        val responseBodyStr = response.body.string()
        if (!response.isSuccessful) {
            error("Chat completions image edit failed: ${response.code} $responseBodyStr")
        }

        return parseChatCompletionsImageResponse(responseBodyStr)
    }

    private suspend fun parseChatCompletionsImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val bodyObj = json.parseToJsonElement(bodyStr).jsonObject
        val choices = bodyObj["choices"]?.jsonArray ?: error("No choices in chat completions response")
        val items = mutableListOf<ImageGenerationItem>()

        for (choice in choices) {
            val message = choice.jsonObject["message"]?.jsonObject ?: continue
            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""

            // Extract Markdown images first: ![Image](data:image/png;base64,xxx) or ![Image](https://...).
            // Some NewAPI/Gemini image bridges return a very large Markdown data URI; never log it.
            val markdownImageRegex = Regex(
                pattern = """!\[.*?\]\((data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)|(https?://[^\s)]+))\)""",
                options = setOf(RegexOption.DOT_MATCHES_ALL),
            )
            val markdownMatches = markdownImageRegex.findAll(content)

            for (match in markdownMatches) {
                val mimeSubType = match.groups[2]?.value ?: "jpeg"
                val b64Data = match.groups[3]?.value?.filterNot { it.isWhitespace() }
                val httpUrl = match.groups[4]?.value

                if (!b64Data.isNullOrBlank()) {
                    items.add(ImageGenerationItem(data = b64Data, mimeType = "image/$mimeSubType"))
                } else if (!httpUrl.isNullOrBlank()) {
                    items.add(downloadImageAsBase64(httpUrl))
                }
            }

            if (items.isEmpty()) {
                val dataUriRegex = Regex(
                    pattern = """data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)""",
                    options = setOf(RegexOption.DOT_MATCHES_ALL),
                )
                dataUriRegex.findAll(content).forEach { match ->
                    val mimeSubType = match.groups[1]?.value ?: "jpeg"
                    val b64Data = match.groups[2]?.value?.filterNot { it.isWhitespace() }
                    if (!b64Data.isNullOrBlank()) {
                        items.add(ImageGenerationItem(data = b64Data, mimeType = "image/$mimeSubType"))
                    }
                }
            }
        }

        if (items.isEmpty()) {
            error("No image found in chat completion response content")
        }
        return items
    }
}
