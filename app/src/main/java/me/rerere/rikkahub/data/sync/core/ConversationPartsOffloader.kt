package me.rerere.rikkahub.data.sync.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref

private const val LARGE_PART_THRESHOLD_BYTES = 20_000 // 20 KB per message node

/**
 * 会话部件分层存储（P4）：
 * 当单条消息节点 parts JSON 超过 20KB 时，自动拆分并上推至 R2
 * key = snapshots/{convId}/msgs/{msgId}/parts.json，
 * D1 conversations 行仅保留轻量骨架，解决超长回复重论撑爆 D1 2MB 限制。
 */
object ConversationPartsOffloader {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun offloadIfNeeded(
        conv: Conversation,
        r2MediaStore: R2MediaStore,
    ): Conversation {
        if (!r2MediaStore.isConfigured()) return conv

        var modified = false
        val newNodes = conv.messageNodes.map { node ->
            val newMessages = node.messages.map { msg ->
                val partsJson = json.encodeToString(msg.parts)
                if (partsJson.length > LARGE_PART_THRESHOLD_BYTES) {
                    val key = "snapshots/${conv.id}/msgs/${msg.id}/parts.json"
                    val bytes = partsJson.toByteArray(Charsets.UTF_8)
                    val ref = r2MediaStore.uploadWithKey(key, bytes, "application/json").getOrNull()
                    if (ref != null) {
                        modified = true
                        msg.copy(
                            parts = listOf(
                                UIMessagePart.Text(
                                    text = "r2_parts:$ref",
                                    metadata = null
                                )
                            )
                        )
                    } else msg
                } else {
                    msg
                }
            }
            if (newMessages !== node.messages) node.copy(messages = newMessages) else node
        }

        return if (modified) conv.copy(messageNodes = newNodes) else conv
    }

    suspend fun hydrateIfNeeded(
        conv: Conversation,
        r2MediaStore: R2MediaStore,
    ): Conversation {
        var modified = false
        val newNodes = conv.messageNodes.map { node ->
            val newMessages = node.messages.map { msg ->
                val firstPart = msg.parts.firstOrNull()
                if (firstPart is UIMessagePart.Text && firstPart.text.startsWith("r2_parts:")) {
                    val rawRef = firstPart.text.removePrefix("r2_parts:")
                    val ref = R2Ref.parse(rawRef)
                    if (ref != null) {
                        val bytes = r2MediaStore.downloadBytes(ref).getOrNull()
                        if (bytes != null) {
                            val parts = runCatching {
                                json.decodeFromString<List<UIMessagePart>>(bytes.toString(Charsets.UTF_8))
                            }.getOrNull()
                            if (parts != null) {
                                modified = true
                                msg.copy(parts = parts)
                            } else msg
                        } else msg
                    } else msg
                } else msg
            }
            if (newMessages !== node.messages) node.copy(messages = newMessages) else node
        }

        return if (modified) conv.copy(messageNodes = newNodes) else conv
    }
}
