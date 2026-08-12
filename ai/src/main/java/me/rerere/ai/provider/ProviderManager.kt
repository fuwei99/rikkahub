package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.ComfyUIImageProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.provider.providers.OpenAIImageProvider
import me.rerere.ai.provider.providers.TokenRhythmImageProvider
import me.rerere.ai.provider.providers.VolcengineImageProvider
import me.rerere.ai.provider.providers.WavespeedImageProvider
import okhttp3.OkHttpClient
import kotlin.reflect.KClass

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(client: OkHttpClient, context: Context, private val sanitizer: MessageSanitizer = MessageSanitizer.NoOp) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()
    private val imageProviders = mutableMapOf<KClass<out ImageProviderSetting>, ImageProvider<*>>()

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context, sanitizer))
        registerProvider("google", GoogleProvider(client, context, sanitizer))
        registerProvider("claude", ClaudeProvider(client, context, sanitizer))

        // 注册生图Provider（按设置类型注册，新增类型只需在这里加一行）
        val openAIImageProvider = OpenAIImageProvider(client, context)
        registerImageProvider(ImageProviderSetting.OpenAI::class, openAIImageProvider)
        registerImageProvider(ImageProviderSetting.NewAPI::class, openAIImageProvider)
        registerImageProvider(ImageProviderSetting.Volcengine::class, VolcengineImageProvider(client, context))
        registerImageProvider(ImageProviderSetting.Wavespeed::class, WavespeedImageProvider(client, context))
        registerImageProvider(ImageProviderSetting.TokenRhythm::class, TokenRhythmImageProvider(client, context))
        registerImageProvider(ImageProviderSetting.ComfyUI::class, ComfyUIImageProvider(client))
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

    fun registerImageProvider(type: KClass<out ImageProviderSetting>, provider: ImageProvider<*>) {
        imageProviders[type] = provider
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
        return (imageProviders[setting::class]
            ?: throw IllegalArgumentException("Image Provider not registered: ${setting::class.simpleName}")) as ImageProvider<T>
    }
}
