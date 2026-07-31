package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.files.AssetResolver
import org.koin.java.KoinJavaComponent.getKoin

object Base64ImageToLocalFileTransformer : OutputMessageTransformer {
    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assetResolver = getKoin().get<AssetResolver>()
        return messages.map { message ->
            message.copy(
                parts = message.parts.mapNotNull { part ->
                    assetResolver.indexPartForStorage(part)
                }
            )
        }
    }
}
