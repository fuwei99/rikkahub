package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.sync.r2.MediaResolver

/**
 * 为带图片附件的消息追加一行 asset id 清单, 让模型能精确指认「第几张图」。
 *
 * 背景: 图片发给模型时只是一串 base64 / 临时 URL, 模型无法回指其中某一张。
 * 过去靠 `assistant-round-<N>-ref-<M>.png` 这类 round tag 编址, 但轮号由上下文推导,
 * 会随消息连发、分支重生成、上下文裁剪而漂移, 已废除。现在统一用 Asset ID。
 *
 * 注入形态(附加在该消息最后一个文本片段之后):
 * ```
 * [attached_images] #1 66689a77-...  #2 d465c492-...
 * ```
 * 序号与消息内图片的出现顺序严格一致。
 *
 * 该改写只作用于**发送给模型的消息副本**, 不写回会话存储, UI 也不显示。
 * asset id 来自 [MediaResolver] 在解析附件时留在 `metadata["asset_id"]` 的记录。
 */
object AssetIdAnnotationTransformer : InputMessageTransformer {

    private const val MARKER = "[attached_images]"

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (messages.none { it.parts.any { part -> part.assetIdOrNull() != null } }) return messages
        return messages.map { message -> message.annotate() }
    }

    private fun UIMessage.annotate(): UIMessage {
        val assetIds = parts.mapNotNull { it.assetIdOrNull() }
        if (assetIds.isEmpty()) return this
        // 幂等: 已经注入过就不重复追加
        if (parts.any { it is UIMessagePart.Text && it.text.contains(MARKER) }) return this

        val line = assetIds.mapIndexed { index, id -> "#${index + 1} $id" }
            .joinToString("  ", prefix = "$MARKER ")

        val lastTextIndex = parts.indexOfLast { it is UIMessagePart.Text }
        val newParts = if (lastTextIndex >= 0) {
            val existing = parts[lastTextIndex] as UIMessagePart.Text
            parts.toMutableList().apply {
                this[lastTextIndex] = existing.copy(
                    text = if (existing.text.isBlank()) line else "${existing.text}\n\n$line"
                )
            }
        } else {
            parts + UIMessagePart.Text(line)
        }
        return copy(parts = newParts)
    }

    private fun UIMessagePart.assetIdOrNull(): String? {
        val url = when (this) {
            is UIMessagePart.Image -> url
            is UIMessagePart.Document -> url
            is UIMessagePart.Video -> url
            is UIMessagePart.Audio -> url
            else -> return null
        }
        // 优先取 MediaResolver 留下的原始 id; 消息尚未解析时 url 本身就是 asset uri。
        runCatching { metadata?.get(MediaResolver.KEY_ASSET_ID)?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return AssetUri.parse(url)
    }
}
