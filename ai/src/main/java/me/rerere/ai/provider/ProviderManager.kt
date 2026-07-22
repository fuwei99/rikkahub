package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.provider.providers.OpenAIImageProvider
import me.rerere.ai.provider.providers.VolcengineImageProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(client: OkHttpClient, context: Context) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()
    private val imageProviders = mutableMapOf<String, ImageProvider<*>>()

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))

        // 注册生图Provider
        registerImageProvider("openai-imggen", OpenAIImageProvider(client, context))
        registerImageProvider("volcengine-imggen", VolcengineImageProvider(client, context))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    fun registerImageProvider(name: String, provider: ImageProvider<*>) {
        imageProviders[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    fun getImageProvider(name: String): ImageProvider<*> {
        return imageProviders[name] ?: throw IllegalArgumentException("Image Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
    }

    fun <T : ImageProviderSetting> getImageProviderByType(setting: T): ImageProvider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ImageProviderSetting.OpenAI -> getImageProvider("openai-imggen")
            is ImageProviderSetting.Volcengine -> getImageProvider("volcengine-imggen")
        } as ImageProvider<T>
    }
}
