package me.rerere.ai.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonPrimitive

/**
 * 向量模型服务实现（OpenAI 兼容 /embeddings 端点）。
 *
 * 与生图服务（OpenAIImageProvider）同级：火山方舟（Plan 订阅 /api/plan/v3、
 * 免费额度 /api/v3）、Fireworks、阿里百炼、智谱、OpenAI 等均可直连。
 * 请求带 `dimensions` 时若服务端不支持（部分网关会 400），自动去掉该参数重试一次。
 */
class VectorProvider(
    private val client: OkHttpClient,
) {
    suspend fun generateEmbedding(
        setting: VectorProviderSetting.OpenAI,
        input: List<String>,
        dimensions: Int? = null,
        modelId: String? = null,
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(input.isNotEmpty()) { "Embedding input cannot be empty" }
        val resolvedModelId = modelId?.takeIf { it.isNotBlank() } ?: firstModelId(setting)

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", resolvedModelId)
                if (input.size == 1) {
                    put("input", input.first())
                } else {
                    putJsonArray("input") {
                        input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                dimensions?.let { put("dimensions", it) }
            }
        )

        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/embeddings")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            // 部分 OpenAI 兼容网关不支持 dimensions 参数（400），去掉重试一次
            if (response.code == 400 && dimensions != null) {
                val retryBody = json.encodeToString(
                    buildJsonObject {
                        put("model", resolvedModelId)
                        if (input.size == 1) {
                            put("input", input.first())
                        } else {
                            putJsonArray("input") {
                                input.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                )
                val retryRequest = Request.Builder()
                    .url("${setting.baseUrl.trimEnd('/')}/embeddings")
                    .addHeader("Authorization", "Bearer ${setting.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(retryBody.toRequestBody("application/json".toMediaType()))
                    .build()
                val retryResponse = client.newCall(retryRequest).await()
                if (!retryResponse.isSuccessful) {
                    error("Failed to generate embedding: ${retryResponse.code} ${retryResponse.body.string()}")
                }
                parseEmbeddings(retryResponse.body.string(), setting, resolvedModelId)
            } else {
                error("Failed to generate embedding: ${response.code} ${response.body.string()}")
            }
        } else {
            parseEmbeddings(response.body.string(), setting, resolvedModelId)
        }
    }

    private fun firstModelId(setting: VectorProviderSetting.OpenAI): String =
        setting.models.firstOrNull { it.modelId.isNotBlank() }?.modelId
            ?: error("Vector provider '${setting.name}' has no model configured")

    private fun parseEmbeddings(bodyStr: String, setting: VectorProviderSetting.OpenAI, resolvedModelId: String): EmbeddingGenerationResult {
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: resolvedModelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        return EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings,
        )
    }
}
