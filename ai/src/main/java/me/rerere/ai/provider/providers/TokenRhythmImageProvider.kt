package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ImageProvider
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.apiKeyTokens
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

private const val TAG = "TokenRhythmImageProvider"

/**
 * TokenRhythm Studio 生图渠道。
 *
 * OpenAI 兼容的同步接口（非流式）：
 * `POST {baseUrl}/images/generations`，仅支持 model / prompt / size / n / response_format
 * 五个字段，多余字段一律 400。**仅文生图**：不支持图生图/图像编辑，prompt 里嵌图片 URL 无效。
 *
 * - 同步返回，无需轮询；
 * - 图片 URL 有效期 1 天（x-oss-expires=86400），这里在 provider 内立即下载转 Base64，
 *   避免上层拿到 1 天后失效的引用；
 * - 多 Token 轮换与 Wavespeed 一致：仅 401/403/422/429 切换到下一个 Token
 *   （422 视为额度耗尽，永久剔除并交由调用方从设置中删除），其它错误码立即抛出。
 */
class TokenRhythmImageProvider(
    private val client: OkHttpClient,
    context: Context? = null,
) : ImageProvider<ImageProviderSetting.TokenRhythm> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override suspend fun generateImage(
        providerSetting: ImageProviderSetting.TokenRhythm,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        Log.i(TAG, "generateImage request")

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                if (params.numOfImages > 1) put("n", params.numOfImages)
                if (params.size.isNotBlank() && params.size != "auto") {
                    put("size", params.size)
                }
            }.mergeCustomBody(params.customBody)
        )

        val items = withContext(Dispatchers.IO) {
            generateWithKeyRotation(providerSetting, requestBody, params.customHeaders)
        }
        items.forEach { emit(it) }
    }

    // TokenRhythm 仅支持文生图，不重写 editImage：基类默认抛出 "Image edit is not supported"。

    /**
     * 内置 Token 轮换重试：最多尝试 Token 数个数的次数，只有
     * 401/403/422/429 才换下一个（422 永久剔除并记录），其它错误码立即抛出。
     */
    private suspend fun generateWithKeyRotation(
        providerSetting: ImageProviderSetting.TokenRhythm,
        requestBody: String,
        customHeaders: List<CustomHeader>,
    ): List<ImageGenerationItem> {
        val providerId = providerSetting.id.toString()
        val tokens = providerSetting.apiKeyTokens
        if (tokens.isEmpty()) {
            error("No API tokens configured for provider: ${providerSetting.name}")
        }
        val keyCount = tokens.size
        var lastCode = 0
        var lastBody = ""

        for (attempt in 1..keyCount) {
            val key = keyRoulette.next(providerSetting.apiKey, providerId, providerSetting.keyStrategy)
            try {
                val items = requestOnce(providerSetting, key, requestBody, customHeaders)
                Log.i(TAG, "image generated with ${key.take(6)}…")
                return items
            } catch (e: TokenFailureException) {
                keyRoulette.reportFailure(providerId, key, e.code)
                lastCode = e.code
                lastBody = e.body
                Log.w(TAG, "TokenRhythm key failed (HTTP ${e.code}), rotating: ${key.take(6)}…")
            }
        }
        error("All TokenRhythm tokens failed (HTTP $lastCode): $lastBody")
    }

    private suspend fun requestOnce(
        providerSetting: ImageProviderSetting.TokenRhythm,
        key: String,
        requestBody: String,
        customHeaders: List<CustomHeader>,
    ): List<ImageGenerationItem> {
        val request = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/images/generations")
            .headers(customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val responseBodyStr = response.body.string()
        if (!response.isSuccessful) {
            // 只把 token 相关的失败码抛给轮换循环；400（参数问题）等其它错误立即向上抛。
            if (response.code in KeyRoulette.KEY_FAILURE_CODES) {
                throw TokenFailureException(response.code, responseBodyStr)
            }
            error("Failed to generate image from TokenRhythm: ${response.code} $responseBodyStr")
        }
        return parseImageResponse(responseBodyStr)
    }

    /** 解析 data[].url / data[].b64_json；URL 模式立即下载转 Base64 以防 1 天过期。 */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val data = body["data"]?.jsonArray ?: error("No data in TokenRhythm image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                ImageGenerationItem(data = b64Json, mimeType = "image/png")
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in TokenRhythm image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val response = client.newCall(Request.Builder().url(url).get().build()).await()
        if (!response.isSuccessful) {
            error("Failed to download TokenRhythm image: ${response.code} ${response.body.string()}")
        }
        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        return ImageGenerationItem(
            data = Base64.encode(body.bytes()),
            mimeType = mimeType,
        )
    }

    /** 携带失败码的内部异常，仅用于 Token 轮换循环内流转。 */
    private class TokenFailureException(
        val code: Int,
        val body: String,
    ) : Exception("HTTP $code")
}
