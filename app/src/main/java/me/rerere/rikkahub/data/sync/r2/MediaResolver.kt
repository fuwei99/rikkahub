package me.rerere.rikkahub.data.sync.r2

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri

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
        val indexedParts = parts.map { part ->
            runCatching { assetResolver.indexPartForStorage(part) }
                .onFailure {
                    // 索引失败绝不能把附件丢掉: 之前这里是 mapNotNull, 失败的图直接从消息里消失,
                    // 同一条消息里两张图可能一张变 asset:// 一张仍是裸 file://, 后者一路走到
                    // provider 层降级成 [Image unavailable], 而且没人知道为什么。
                    // 现在保留原 part（后续 resolvePartForModel 会尽力内联成 data uri）并上报失败。
                    android.util.Log.w("MediaResolver", "indexPartForStorage failed, keeping raw part", it)
                    failures += "附件索引失败：${it.detailMessage()}"
                }
                .getOrNull()
                ?.also { indexed ->
                    if (indexed != part) indexedCount += 1
                }
                ?: part
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
            // 解析后 URL 已经变成 http/data/file, 原来的 asset id 会丢。
            // 把它存进传输层 metadata, 供 AssetIdAnnotationTransformer 告知模型图片的稳定地址。
            // 仅存在于发送用的消息副本, 不会写回会话。
            ?.withPreservedAssetId(part)

        is UIMessagePart.Tool -> part.copy(
            output = part.output.mapNotNull { resolvePart(it, model) }
        )

        else -> part
    }

    private fun UIMessagePart.withPreservedAssetId(original: UIMessagePart): UIMessagePart {
        val originalUrl = when (original) {
            is UIMessagePart.Image -> original.url
            is UIMessagePart.Document -> original.url
            is UIMessagePart.Video -> original.url
            is UIMessagePart.Audio -> original.url
            else -> return this
        }
        val assetId = AssetUri.parse(originalUrl) ?: return this
        val merged = JsonObject((metadata ?: JsonObject(emptyMap())) + mapOf(KEY_ASSET_ID to JsonPrimitive(assetId)))
        return when (this) {
            is UIMessagePart.Image -> copy(metadata = merged)
            is UIMessagePart.Document -> copy(metadata = merged)
            is UIMessagePart.Video -> copy(metadata = merged)
            is UIMessagePart.Audio -> copy(metadata = merged)
            else -> this
        }
    }

    companion object {
        /** 传输层 metadata key: 被解析掉的原始 asset id。唯一定义在 [AssetResolver] */
        const val KEY_ASSET_ID = AssetResolver.METADATA_ASSET_ID
    }

    private fun Throwable.detailMessage(): String =
        message ?: cause?.message ?: javaClass.simpleName
}
