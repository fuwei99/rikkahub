package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageApiDialect
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageProvider
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.closeOnCodes
import me.rerere.ai.provider.disabledTokens
import me.rerere.ai.provider.keyStrategy
import me.rerere.ai.provider.retryCount
import me.rerere.ai.provider.retryIntervalSec
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.KeyFailureException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.executeWithRetry
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.ai.util.toImageDataUriOrRemote
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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

    /**
     * Resolves which API the model should use. AUTO preserves the legacy heuristic
     * (NewAPI -> chat/completions, other providers -> images API with chat fallback).
     */
    private fun Model.resolveDialect(providerSetting: ImageProviderSetting): ImageApiDialect =
        when (imageCapabilities.apiDialect) {
            ImageApiDialect.AUTO ->
                if (providerSetting is ImageProviderSetting.NewAPI) ImageApiDialect.CHAT_COMPLETIONS
                else ImageApiDialect.AUTO
            else -> imageCapabilities.apiDialect
        }

    override suspend fun generateImage(
        providerSetting: ImageProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        val routedRequest = params.model.routeImageRequest(params.customBody)

        Log.i(TAG, "generateImage task submit")

        val items = withContext(Dispatchers.IO) {
            keyRoulette.executeWithRetry(
                keys = providerSetting.openAICompatibleApiKey,
                providerId = providerSetting.id.toString(),
                strategy = providerSetting.keyStrategy,
                disabledKeys = providerSetting.disabledTokens,
                retryCount = providerSetting.retryCount,
                retryIntervalMs = providerSetting.retryIntervalSec * 1000L,
                closeCodes = providerSetting.closeOnCodes.toSet(),
            ) { key ->
                when (params.model.resolveDialect(providerSetting)) {
                    ImageApiDialect.CHAT_COMPLETIONS ->
                        chatCompletionsGenerate(providerSetting, params, key, routedRequest)

                    ImageApiDialect.IMAGES_API ->
                        imagesApiGenerate(providerSetting, params, key, routedRequest)

                    ImageApiDialect.AUTO -> {
                        // Try the images API first, fall back to chat/completions for
                        // chat-style image models (e.g. Gemini image bridges).
                        try {
                            imagesApiGenerate(providerSetting, params, key, routedRequest)
                        } catch (primary: Exception) {
                            try {
                                chatCompletionsGenerate(providerSetting, params, key, routedRequest)
                            } catch (fallback: Exception) {
                                primary.addSuppressed(fallback)
                                throw primary
                            }
                        }
                    }
                }
            }
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ImageProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        val routedRequest = params.model.routeImageRequest(params.customBody)

        val items = withContext(Dispatchers.IO) {
            keyRoulette.executeWithRetry(
                keys = providerSetting.openAICompatibleApiKey,
                providerId = providerSetting.id.toString(),
                strategy = providerSetting.keyStrategy,
                disabledKeys = providerSetting.disabledTokens,
                retryCount = providerSetting.retryCount,
                retryIntervalMs = providerSetting.retryIntervalSec * 1000L,
                closeCodes = providerSetting.closeOnCodes.toSet(),
            ) { key ->
                when (params.model.resolveDialect(providerSetting)) {
                    ImageApiDialect.CHAT_COMPLETIONS ->
                        chatCompletionsEdit(providerSetting, params, key, routedRequest)

                    ImageApiDialect.IMAGES_API ->
                        imagesApiEdit(providerSetting, params, key, routedRequest)

                    ImageApiDialect.AUTO -> {
                        // Legacy behavior tried chat/completions first for edits; keep that order
                        // but also try the official /images/edits endpoint before giving up.
                        try {
                            chatCompletionsEdit(providerSetting, params, key, routedRequest)
                        } catch (primary: Exception) {
                            try {
                                imagesApiEdit(providerSetting, params, key, routedRequest)
                            } catch (fallback: Exception) {
                                primary.addSuppressed(fallback)
                                throw primary
                            }
                        }
                    }
                }
            }
        }

        items.forEach { emit(it) }
    }

    // ---------------------------------------------------------------------
    // /images/generations & /images/edits
    // ---------------------------------------------------------------------

    private suspend fun imagesApiGenerate(
        providerSetting: ImageProviderSetting,
        params: ImageGenerationParams,
        key: String,
        routedRequest: RoutedImageRequest,
    ): List<ImageGenerationItem> {
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
            // 统一抛给轮换/重试循环
            throw KeyFailureException(response.code, responseBodyStr)
        }
        return parseImageResponse(responseBodyStr)
    }

    /** Official OpenAI image editing: multipart form on /images/edits (gpt-image-1, dall-e-2). */
    private suspend fun imagesApiEdit(
        providerSetting: ImageProviderSetting,
        params: ImageEditParams,
        key: String,
        routedRequest: RoutedImageRequest,
    ): List<ImageGenerationItem> {
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", routedRequest.modelId)
            .addFormDataPart("prompt", params.prompt)
        if (params.numOfImages > 1) {
            bodyBuilder.addFormDataPart("n", params.numOfImages.toString())
        }
        if (params.size.isNotBlank() && params.size != "auto") {
            bodyBuilder.addFormDataPart("size", params.size)
        }
        params.images.forEachIndexed { index, source ->
            val (bytes, mimeType) = resolveImageBytes(source)
            val extension = mimeType.substringAfter('/')
            bodyBuilder.addFormDataPart(
                "image[]",
                "reference_$index.$extension",
                bytes.toRequestBody(mimeType.toMediaType()),
            )
        }
        routedRequest.customBody.forEach { body ->
            if (body.key.isNotBlank()) {
                val value = (body.value as? JsonPrimitive)?.contentOrNull ?: body.value.toString()
                bodyBuilder.addFormDataPart(body.key, value)
            }
        }

        val request = Request.Builder()
            .url("${providerSetting.openAICompatibleBaseUrl.trimEnd('/')}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.openAICompatibleBaseUrl)
            .build()

        val response = client.newCall(request).await()
        val responseBodyStr = response.body.string()
        if (!response.isSuccessful) {
            // 统一抛给轮换/重试循环
            throw KeyFailureException(response.code, responseBodyStr)
        }
        return parseImageResponse(responseBodyStr)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun resolveImageBytes(source: String): Pair<ByteArray, String> {
        val normalized = source.toImageDataUriOrRemote()
        if (normalized.startsWith("data:")) {
            val mimeType = normalized.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
            val base64 = normalized.substringAfter("base64,", "")
            require(base64.isNotBlank()) { "Unsupported data URI for image edit" }
            return Base64.decode(base64) to mimeType
        }
        // Remote URL: download once for the multipart upload.
        val response = client.newCall(Request.Builder().url(normalized).get().build()).await()
        if (!response.isSuccessful) {
            error("Failed to download reference image: ${response.code}")
        }
        val body = response.body
        return body.bytes() to (body.contentType()?.toString() ?: "image/png")
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

    // ---------------------------------------------------------------------
    // chat/completions image bridges (NewAPI / Gemini style)
    // ---------------------------------------------------------------------

    /**
     * NewAPI-style bridges often drop system messages on multimodal requests, so the
     * system prompt is merged into the user prompt there. Applied consistently for
     * both generation and editing.
     */
    private fun buildChatMessages(
        providerSetting: ImageProviderSetting,
        model: Model,
        userContent: JsonElement,
        userPromptText: String,
    ): JsonArray {
        val systemPrompt = model.imageSystemPrompt.takeIf { it.isNotBlank() }
        val mergeIntoUser = providerSetting is ImageProviderSetting.NewAPI && systemPrompt != null
        val effectiveUserContent: JsonElement = if (mergeIntoUser) {
            when (userContent) {
                is JsonPrimitive -> JsonPrimitive("$systemPrompt\n\n$userPromptText")
                is JsonArray -> buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "$systemPrompt\n\n$userPromptText")
                    })
                    userContent.filterNot {
                        (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "text"
                    }.forEach { add(it) }
                }
                else -> userContent
            }
        } else {
            userContent
        }
        val messages = buildJsonArray {
            if (!mergeIntoUser && systemPrompt != null) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", effectiveUserContent)
            })
        }
        return messages
    }

    private suspend fun postChatCompletions(
        providerSetting: ImageProviderSetting,
        customHeaders: okhttp3.Headers,
        key: String,
        requestBody: String,
    ): List<ImageGenerationItem> {
        val request = Request.Builder()
            .url("${providerSetting.openAICompatibleBaseUrl.trimEnd('/')}/chat/completions")
            .headers(customHeaders)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.openAICompatibleBaseUrl)
            .build()

        val response = client.newCall(request).await()
        val responseBodyStr = response.body.string()
        if (!response.isSuccessful) {
            // 统一抛给轮换/重试循环
            throw KeyFailureException(response.code, responseBodyStr)
        }
        return parseChatCompletionsImageResponse(responseBodyStr)
    }

    private suspend fun chatCompletionsGenerate(
        providerSetting: ImageProviderSetting,
        params: ImageGenerationParams,
        key: String,
        routedRequest: RoutedImageRequest,
    ): List<ImageGenerationItem> {
        val messages = buildChatMessages(
            providerSetting = providerSetting,
            model = params.model,
            userContent = JsonPrimitive(params.prompt),
            userPromptText = params.prompt,
        )
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", routedRequest.modelId)
                put("messages", messages)
                if (params.numOfImages > 1) put("n", params.numOfImages)
            }.mergeCustomBody(routedRequest.customBody)
        )
        return postChatCompletions(providerSetting, params.customHeaders.toHeaders(), key, requestBody)
    }

    private suspend fun chatCompletionsEdit(
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
            params.images.forEach { imgSource ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", imgSource.toImageDataUriOrRemote())
                    })
                })
            }
        }
        val messages = buildChatMessages(
            providerSetting = providerSetting,
            model = params.model,
            userContent = contentArray,
            userPromptText = params.prompt,
        )
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", routedRequest.modelId)
                put("messages", messages)
            }.mergeCustomBody(routedRequest.customBody)
        )
        return postChatCompletions(providerSetting, params.customHeaders.toHeaders(), key, requestBody)
    }

    private suspend fun parseChatCompletionsImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val bodyObj = json.parseToJsonElement(bodyStr).jsonObject
        val choices = bodyObj["choices"]?.jsonArray ?: error("No choices in chat completions response")
        val items = mutableListOf<ImageGenerationItem>()

        for (choice in choices) {
            val message = choice.jsonObject["message"]?.jsonObject ?: continue

            // 1. Structured multimodal content: content as an array of parts, or a
            //    non-standard `images` array (both used by Gemini/NewAPI bridges).
            val structuredParts = buildList {
                (message["content"] as? JsonArray)?.let { addAll(it) }
                (message["images"] as? JsonArray)?.let { addAll(it) }
            }
            for (part in structuredParts) {
                val partObj = part as? JsonObject ?: continue
                val url = partObj["image_url"]?.let { imageUrl ->
                    (imageUrl as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
                        ?: (imageUrl as? JsonPrimitive)?.contentOrNull
                } ?: partObj["url"]?.jsonPrimitive?.contentOrNull
                if (url != null) {
                    items.addImageSource(url)
                }
            }

            // 2. Plain text content: extract Markdown images / raw data URIs.
            //    Some bridges return a very large Markdown data URI; never log it.
            val content = (message["content"] as? JsonPrimitive)?.contentOrNull
                ?: (message["content"] as? JsonArray)?.mapNotNull {
                    (it as? JsonObject)?.takeIf { obj ->
                        obj["type"]?.jsonPrimitive?.contentOrNull == "text"
                    }?.get("text")?.jsonPrimitive?.contentOrNull
                }?.joinToString("\n")
                ?: ""

            if (content.isNotBlank()) {
                val markdownImageRegex = Regex(
                    pattern = """!\[.*?\]\((data:image/([a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)|(https?://[^\s)]+))\)""",
                    options = setOf(RegexOption.DOT_MATCHES_ALL),
                )
                for (match in markdownImageRegex.findAll(content)) {
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
        }

        if (items.isEmpty()) {
            error("No image found in chat completion response content")
        }
        return items
    }

    /** Adds an image from a data URI or remote URL string. */
    private suspend fun MutableList<ImageGenerationItem>.addImageSource(source: String) {
        if (source.startsWith("data:image")) {
            val mimeType = source.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
            val b64 = source.substringAfter("base64,", "").filterNot { it.isWhitespace() }
            if (b64.isNotBlank()) {
                add(ImageGenerationItem(data = b64, mimeType = mimeType))
            }
        } else if (source.startsWith("http://") || source.startsWith("https://")) {
            add(downloadImageAsBase64(source))
        }
    }
}
