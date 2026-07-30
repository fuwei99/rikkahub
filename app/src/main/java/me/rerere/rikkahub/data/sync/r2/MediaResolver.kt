package me.rerere.rikkahub.data.sync.r2

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.AssetResolver

/**
 * Chat media adapter after the asset refactor.
 *
 * New chat messages persist only `asset://managed-files/<uuid>` references. R2/local/external
 * details are resolved by [AssetResolver] at the boundary where messages are stored or sent to a
 * model. Legacy non-asset attachments are intentionally not migrated: they are omitted when sent
 * to models and rendered as unavailable by the UI.
 */
class MediaResolver(
    private val assetResolver: AssetResolver,
) {
    enum class ImageTransport { URL, BASE64 }

    fun transportFor(model: Model): ImageTransport =
        if (Modality.URL in model.inputModalities) ImageTransport.URL else ImageTransport.BASE64

    data class UploadLocalAttachmentsResult(
        val parts: List<UIMessagePart>,
        val failures: List<String> = emptyList(),
        val uploadedCount: Int = 0,
    )

    suspend fun uploadLocalAttachments(parts: List<UIMessagePart>): List<UIMessagePart> =
        uploadLocalAttachmentsWithReport(parts).parts

    suspend fun uploadLocalAttachmentsWithReport(parts: List<UIMessagePart>): UploadLocalAttachmentsResult {
        val failures = mutableListOf<String>()
        var indexedCount = 0
        val indexedParts = parts.mapNotNull { part ->
            runCatching { assetResolver.indexPartForStorage(part) }
                .onFailure { failures += "附件索引失败：${it.detailMessage()}" }
                .getOrNull()
                ?.also { indexed ->
                    if (indexed != part) indexedCount += 1
                }
        }
        return UploadLocalAttachmentsResult(indexedParts, failures, indexedCount)
    }

    suspend fun prepareOutgoingMessages(
        messages: List<UIMessage>,
        model: Model,
    ): List<UIMessage> {
        val hasMedia = messages.any { msg -> msg.parts.any { it.containsMedia() } }
        if (!hasMedia) return messages
        return messages.map { msg ->
            msg.copy(parts = msg.parts.mapNotNull { part -> resolvePart(part, model) })
        }
    }

    private fun UIMessagePart.containsMedia(): Boolean = when (this) {
        is UIMessagePart.Image -> true
        is UIMessagePart.Document -> true
        is UIMessagePart.Video -> true
        is UIMessagePart.Audio -> true
        is UIMessagePart.Tool -> output.any { it.containsMedia() }
        else -> false
    }

    private suspend fun resolvePart(part: UIMessagePart, model: Model): UIMessagePart? = when (part) {
        is UIMessagePart.Image,
        is UIMessagePart.Document,
        is UIMessagePart.Video,
        is UIMessagePart.Audio -> assetResolver.resolvePartForModel(part, model)

        is UIMessagePart.Tool -> part.copy(
            output = part.output.mapNotNull { resolvePart(it, model) }
        )

        else -> part
    }

    private fun Throwable.detailMessage(): String =
        message ?: cause?.message ?: javaClass.simpleName
}
