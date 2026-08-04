package me.rerere.rikkahub.data.sync

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.ai.provider.ImageProviderSetting
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.datastore.FileProcessingServiceOptions
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

@Serializable
data class ServiceConfigBundle(
    val version: Int = 1,
    val app: String = "RikkaHub",
    val providers: List<ProviderSetting> = emptyList(),
    val imageProviders: List<ImageProviderSetting> = emptyList(),
    val vectorProviders: List<VectorProviderSetting> = emptyList(),
    val searchServices: List<SearchServiceOptions> = emptyList(),
    val ttsProviders: List<TTSProviderSetting> = emptyList(),
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val fileProcessingServices: List<FileProcessingServiceOptions> = emptyList(),
)

object ServiceConfigBundleIO {
    fun export(settings: Settings): String = JsonInstant.encodeToString(
        ServiceConfigBundle(
            providers = settings.providers,
            imageProviders = settings.imageProviders,
            vectorProviders = settings.vectorProviders,
            searchServices = settings.searchServices,
            ttsProviders = settings.ttsProviders,
            asrProviders = settings.asrProviders,
            mcpServers = settings.mcpServers,
            fileProcessingServices = settings.fileProcessingServices,
        )
    )

    fun importInto(settings: Settings, rawJson: String): Settings {
        val bundle = JsonInstant.decodeFromString<ServiceConfigBundle>(rawJson)
        return settings.copy(
            providers = mergeProviderSettings(settings.providers, bundle.providers),
            imageProviders = mergeImageProviderSettings(settings.imageProviders, bundle.imageProviders),
            vectorProviders = mergeVectorProviderSettings(settings.vectorProviders, bundle.vectorProviders),
            searchServices = mergeByKey(settings.searchServices, bundle.searchServices) { identityWithoutId(it) },
            ttsProviders = mergeByKey(settings.ttsProviders, bundle.ttsProviders) { identityWithoutId(it) },
            asrProviders = mergeByKey(settings.asrProviders, bundle.asrProviders) { identityWithoutId(it) },
            mcpServers = mergeByKey(settings.mcpServers, bundle.mcpServers) { mcpIdentity(it) },
            fileProcessingServices = mergeByKey(settings.fileProcessingServices, bundle.fileProcessingServices) { identityWithoutId(it) },
        )
    }

    private fun mergeProviderSettings(
        existing: List<ProviderSetting>,
        imported: List<ProviderSetting>,
    ): List<ProviderSetting> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.providerIdentity() == incoming.providerIdentity() }
            if (index >= 0) {
                val current = result[index]
                result[index] = current.copyProvider(models = mergeModels(current.models, incoming.models))
            } else {
                result += incoming.copyProvider(id = Uuid.random(), models = incoming.models.distinctBy { it.identityKey() })
            }
        }
        return result
    }

    private fun mergeImageProviderSettings(
        existing: List<ImageProviderSetting>,
        imported: List<ImageProviderSetting>,
    ): List<ImageProviderSetting> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.imageProviderIdentity() == incoming.imageProviderIdentity() }
            if (index >= 0) {
                val current = result[index]
                result[index] = current.copyProvider(models = mergeModels(current.models, incoming.models))
            } else {
                result += incoming.copyProvider(id = Uuid.random(), models = incoming.models.distinctBy { it.identityKey() })
            }
        }
        return result
    }

    private fun mergeVectorProviderSettings(
        existing: List<VectorProviderSetting>,
        imported: List<VectorProviderSetting>,
    ): List<VectorProviderSetting> {
        val result = existing.toMutableList()
        imported.forEach { incoming ->
            val index = result.indexOfFirst { it.vectorProviderIdentity() == incoming.vectorProviderIdentity() }
            if (index >= 0) {
                val current = result[index]
                result[index] = current.copyProvider(models = mergeModels(current.models, incoming.models))
            } else {
                result += incoming.copyProvider(id = Uuid.random(), models = incoming.models.distinctBy { it.identityKey() })
            }
        }
        return result
    }

    private fun mergeModels(existing: List<Model>, imported: List<Model>): List<Model> {
        val keys = existing.mapTo(mutableSetOf()) { it.identityKey() }
        return existing + imported.filter { keys.add(it.identityKey()) }
    }

    private fun Model.identityKey(): String = "${modelId.trim()}\u0000${displayName.trim()}"

    private fun ProviderSetting.providerIdentity(): String = when (this) {
        is ProviderSetting.OpenAI -> listOf("openai", name, apiKey, baseUrl, chatCompletionsPath, useResponseApi.toString()).joinToString("\u0000")
        is ProviderSetting.Google -> listOf("google", name, apiKey, baseUrl, vertexAI.toString(), location, projectId).joinToString("\u0000")
        is ProviderSetting.Claude -> listOf("claude", name, apiKey, baseUrl).joinToString("\u0000")
    }

    private fun ImageProviderSetting.imageProviderIdentity(): String = when (this) {
        is ImageProviderSetting.OpenAI -> listOf("openai-img", name, apiKey, baseUrl).joinToString("\u0000")
        is ImageProviderSetting.NewAPI -> listOf("newapi-img", name, apiKey, baseUrl).joinToString("\u0000")
        is ImageProviderSetting.Volcengine -> listOf("volcengine-img", name, apiKey, baseUrl).joinToString("\u0000")
        is ImageProviderSetting.Wavespeed -> listOf("wavespeed-img", name, apiKey, baseUrl).joinToString("\u0000")
    }

    private fun VectorProviderSetting.vectorProviderIdentity(): String = when (this) {
        is VectorProviderSetting.OpenAI -> listOf("openai-vec", name, apiKey, baseUrl).joinToString("\u0000")
    }

    private inline fun <reified T> mergeByKey(existing: List<T>, imported: List<T>, key: (T) -> String): List<T> {
        val keys = existing.mapTo(mutableSetOf(), key)
        return existing + imported.filter { keys.add(key(it)) }
    }

    private inline fun <reified T> identityWithoutId(value: T): String {
        val json = JsonInstant.encodeToJsonElement(value)
        return removeKeys(json, setOf("id")).toString()
    }

    private fun mcpIdentity(server: McpServerConfig): String = listOf(
        when (server) {
            is McpServerConfig.SseTransportServer -> "sse"
            is McpServerConfig.StreamableHTTPServer -> "streamable_http"
        },
        server.commonOptions.name,
        server.serverUrl,
        server.commonOptions.headers.joinToString(";") { (key, value) -> "${key.trim()}=${value.trim()}" },
    ).joinToString("\u0000")

    private fun removeKeys(element: JsonElement, keys: Set<String>): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element
                .filterKeys { it !in keys }
                .mapValues { (_, value) -> removeKeys(value, keys) }
        )
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map { removeKeys(it, keys) })
        else -> element
    }
}
