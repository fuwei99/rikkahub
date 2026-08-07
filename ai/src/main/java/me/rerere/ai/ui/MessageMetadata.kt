package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.json

/**
 * [UIMessagePart.metadata] 的类型安全 schema
 *
 * metadata 在序列化层仍然是 [JsonObject], 这里只是为读写提供编译期类型:
 * - 读: `part.metadataAs<ClaudeReasoningMetadata>()?.signature`
 * - 写: `part.metadata = ClaudeReasoningMetadata(signature = ...).toMetadata()`
 *
 * 所有字段必须可空且 key 与历史数据保持一致(必要时用 [SerialName]),
 * 否则旧会话中持久化的 metadata 将无法解析
 */
sealed interface PartMetadata

/**
 * Claude thinking block 的元数据, 回传时需要携带 signature
 */
@Serializable
data class ClaudeReasoningMetadata(
    val signature: String? = null,
) : PartMetadata

/**
 * OpenAI Responses API reasoning item 的元数据, 回传时需要携带 id 和 encrypted_content
 */
@Serializable
data class OpenAIReasoningMetadata(
    @SerialName("reasoning_id")
    val reasoningId: String? = null,
    @SerialName("encrypted_content")
    val encryptedContent: String? = null,
) : PartMetadata

/**
 * Google Gemini 部件(functionCall/inlineData)的 thoughtSignature, 回传时需要携带
 */
@Serializable
data class GoogleThoughtMetadata(
    val thoughtSignature: String? = null,
) : PartMetadata

/**
 * 文件编辑类工具(如 workspace_edit_file)输出部件的元数据,
 * 携带 unified diff 文本供 UI 渲染 diff view, 不会发送给 API
 */
@Serializable
data class DiffMetadata(
    val diff: String? = null,
) : PartMetadata

/**
 * 「对话即 Agent」的消息署名（方案 2026-08-07）。
 *
 * 纯文本前缀（`[来自主agent]`）无法让子 agent 区分"真人说的"与"主 agent 说的",
 * 且可被提示注入伪造。给模型看的是文本首行结构化头部, 给 UI 看的是这份 metadata:
 * 气泡按 [senderRole] 渲染不同标签/配色, 用户一眼看出"这条不是我发的"。
 */
@Serializable
data class AgentSenderMetadata(
    /** human | main_agent | sub_agent | peer_agent | system_report */
    @SerialName("sender_role")
    val senderRole: String? = null,
    @SerialName("sender_conversation_id")
    val conversationId: String? = null,
    @SerialName("sender_title")
    val title: String? = null,
    @SerialName("sender_template_id")
    val templateId: String? = null,
    /** 投递类型: task | report | ask | instruction | peer | system */
    @SerialName("agent_message_kind")
    val messageKind: String? = null,
) : PartMetadata

/**
 * 将 metadata 解析为类型化的 [PartMetadata], 解析失败或 metadata 为 null 时返回 null
 *
 * 由于 json 配置了 ignoreUnknownKeys, 不同 provider 的 metadata 互不干扰
 * (例如切换 provider 后, OpenAI 写入的 reasoning 元数据不会影响 Claude 的解析)
 */
inline fun <reified T : PartMetadata> UIMessagePart.metadataAs(): T? = metadata?.let {
    runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull()
}

/**
 * 将类型化的 [PartMetadata] 编码为 metadata [JsonObject]
 *
 * 由于 json 配置了 explicitNulls = false, 值为 null 的字段不会写入
 */
inline fun <reified T : PartMetadata> T.toMetadata(): JsonObject =
    json.encodeToJsonElement(this).jsonObject
