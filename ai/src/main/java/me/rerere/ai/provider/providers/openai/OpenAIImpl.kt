package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.Flow
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage

interface OpenAIImpl {
    /**
     * 非流式生成；[key] 为本次尝试使用的 Token（由上层轮换/重试循环选定）。
     * HTTP 失败统一抛 [me.rerere.ai.util.KeyFailureException]。
     */
    suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        key: String,
    ): MessageChunk

    /**
     * 流式生成；[key] 为本次尝试使用的 Token（由上层轮换/重试循环选定）。
     * 连接阶段失败统一抛 [me.rerere.ai.util.KeyFailureException]。
     */
    suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        key: String,
    ): Flow<MessageChunk>
}
