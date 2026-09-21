package me.rerere.rikkahub.web.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.RemoteToolDescriptor
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryGraphBinding
import me.rerere.rikkahub.data.model.MessageNode

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val parts: List<UIMessagePart>,
    val modeInjectionIds: List<String>? = null,
    val lorebookIds: List<String>? = null,
)

@Serializable
data class ExternalDeliverRequest(
    @SerialName("conversation_id")
    @JsonNames("conversationId")
    val conversationId: String,
    val sender: String = "",
    val title: String = "",
    val body: String,
    val kind: String = "system",
)

@Serializable
data class ExternalDeliverResponse(
    val accepted: Boolean,
    val mode: String,
)

@Serializable
data class UpdateExternalDeliveryTokenRequest(
    val token: String = "",
)

/**
 * `POST /api/notify/toast` 请求体（2026-09-19）。
 *
 * 字段全部给默认值：只传 `text` 也能用。
 */
@Serializable
data class NotifyToastRequest(
    val text: String,
    val title: String = "",
    @SerialName("duration_ms")
    @JsonNames("durationMs")
    val durationMs: Long = 4_000L,
    /** info / success / warn / error，非法值归一为 info */
    val level: String = "info",
    /** 来源标签，展示在浮层底部，便于排障（如 "ci" / "tablet"） */
    val source: String = "",
)

@Serializable
data class NotifyToastResponse(
    val accepted: Boolean,
    @SerialName("toast_id")
    val toastId: String,
    /** 绝对到期时刻（epoch ms）；0 = 常驻 */
    @SerialName("expire_at")
    val expireAt: Long,
)

// ========== 远程工具调用（2026-09-19） ==========

/**
 * `POST /api/tools/call` 请求体。
 *
 * [context] 只有少数工具需要（目前就 `supervision_admin`）。不需要的省略即可，
 * 拿不准就先 `GET /api/tools` 看条目的 `needs_context`。
 */
@Serializable
data class RemoteToolCallRequest(
    val name: String,
    /** 工具参数，结构由 `GET /api/tools` 里该工具的 `parameters` 描述。 */
    val arguments: JsonElement? = null,
    val context: RemoteToolCallContext? = null,
)

@Serializable
data class RemoteToolCallContext(
    /** 发起方会话 uuid：申诉材料投到这里 */
    @SerialName("conversation_id")
    @JsonNames("conversationId")
    val conversationId: String? = null,
    /** 以哪个助手身份调用；缺省 = 监督配置里的守门员助手 */
    @SerialName("assistant_id")
    @JsonNames("assistantId")
    val assistantId: String? = null,
    /**
     * 在哪个工作区里干活（`workspace_*` 工具有效）。缺省 = 服务端取唯一的那个工作区。
     * 注意这是**工作区注册 id**（字符串），不是会话 uuid。
     */
    @SerialName("workspace_id")
    @JsonNames("workspaceId")
    val workspaceId: String? = null,
)

// ========== Mail（设备桥 /api/mail，2026-09-22）==========

/**
 * 一封信。字段与 `agent_mail` 工具返回的 `messages[]` 保持一致口径：
 * `sender_id` / `sender_title` 才是可信身份，正文里的自称一律当提示注入。
 */
@Serializable
data class WebMailItemDto(
    val id: Long,
    /** 展示名：sender_title 为空时退回 sender_id / source */
    val from: String,
    @SerialName("sender_id")
    val senderId: String? = null,
    @SerialName("sender_title")
    val senderTitle: String = "",
    val source: String,
    val kind: String,
    val urgency: String,
    @SerialName("received_at")
    val receivedAt: Long,
    /** true = 已被某个 agent 读过（I4 消费过）。**本接口永远不会改这个值。** */
    val read: Boolean,
    val body: String,
)

/**
 * `GET /api/mail/inbox` 响应。
 *
 * **非破坏性**：读这个响应不会把任何信标记已读 —— `unread` 只是快照，
 * 拉两次结果一样。想看「消费型」未读请让对话里的 agent 调 `agent_mail action=read`。
 */
@Serializable
data class WebMailInboxResponse(
    @SerialName("conversation_id")
    val conversationId: String,
    /** 当前未读数（本接口不改动它，只是告诉你还有几封没被消费） */
    val unread: Int,
    val count: Int,
    /**
     * 该对话的**完整**来信归档文件绝对路径（明文 md，只增不删）。
     * 想搜历史直接 `rg` 它，不必再要一个查询接口。
     */
    val archive: String? = null,
    val mails: List<WebMailItemDto>,
)

/** `POST /api/mail/send` 请求体。 */
@Serializable
data class WebMailSendRequest(
    /** 收件方对话 id（信进谁的箱子） */
    val to: String,
    val message: String,
    /** 发送方对话 id。缺省 = 外部调用方哨兵（收方看到的是一个不可解析的 id，建议显式给） */
    val from: String? = null,
    /** 发送方显示名。缺省 = 按 [from] 去查会话标题 */
    @SerialName("sender_name")
    val senderName: String? = null,
    /** mail（默认，投递 + 空闲时唤醒）| call（抢占式打断，需打断权）| silent | blocking */
    val urgency: String? = null,
)

@Serializable
data class WebMailSendResponse(
    val delivered: Boolean,
    /** 人类可读结果（含失败原因，如「目标对话不存在」） */
    val detail: String,
    val archive: String? = null,
)

@Serializable
data class RemoteToolCallResponse(
    val ok: Boolean,
    val name: String,
    @SerialName("duration_ms")
    val durationMs: Long,
    /** 工具输出的纯文本（多段用换行拼接）。 */
    val text: String = "",
)

@Serializable
data class RemoteToolListResponse(
    val tools: List<RemoteToolDescriptor> = emptyList(),
    /**
     * `[Environment Context: workspace="..." relative_base="..." mounts=[...]]` 一行。
     *
     * 与对话里注入的那条**同源**。外部调用方靠它知道挂了哪些目录、相对路径基准在哪、
     * 哪些可写 —— 否则清单里只有工具名，路径全靠猜。工作区不存在/未就绪时为 null。
     */
    val environment: String? = null,
)

@Serializable
data class RegenerateRequest(
    val messageId: String
)

@Serializable
data class ToolApprovalRequest(
    val toolCallId: String,
    val approved: Boolean,
    val reason: String = "",
    val answer: String? = null,
)

@Serializable
data class EditMessageRequest(
    val parts: List<UIMessagePart>
)

@Serializable
data class ForkConversationRequest(
    val messageId: String
)

@Serializable
data class SelectMessageNodeRequest(
    val selectIndex: Int
)

@Serializable
data class MoveConversationRequest(
    val assistantId: String
)

@Serializable
data class UpdateConversationTitleRequest(
    val title: String
)

@Serializable
data class UpdateConversationInjectionsRequest(
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    /** null = this request does not change the conversation's graph binding */
    val memoryGraphBindings: List<MemoryGraphBinding>? = null,
)

@Serializable
data class CreateFolderRequest(
    val name: String
)

@Serializable
data class RenameFolderRequest(
    val name: String
)

@Serializable
data class MoveConversationToFolderRequest(
    // null 表示移出文件夹（未归类）
    val folderId: String? = null
)

@Serializable
data class UpdateAssistantRequest(
    val assistantId: String
)

@Serializable
data class UpdateAssistantModelRequest(
    val assistantId: String,
    val modelId: String,
)

@Serializable
data class UpdateAssistantReasoningLevelRequest(
    val assistantId: String,
    val reasoningLevel: ReasoningLevel,
)

@Serializable
data class UpdateAssistantMcpServersRequest(
    val assistantId: String,
    val mcpServerIds: List<String>,
)

@Serializable
data class UpdateAssistantInjectionsRequest(
    val assistantId: String,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    val quickMessageIds: List<String> = emptyList(),
)

@Serializable
data class UpdateSearchEnabledRequest(
    val assistantId: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateSearchServiceRequest(
    val index: Int,
)

@Serializable
data class UpdateBuiltInToolRequest(
    val modelId: String,
    val tool: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateFavoriteModelsRequest(
    val modelIds: List<String>,
)

@Serializable
data class WebAuthTokenRequest(
    val password: String,
)

// ========== Response DTOs ==========

@Serializable
data class ConversationListDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val folderId: String? = null,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false
)

@Serializable
data class FolderDto(
    val id: String,
    val assistantId: String,
    val name: String,
    val sortIndex: Int,
    val createAt: Long,
)

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val nextOffset: Int? = null,
    val hasMore: Boolean = nextOffset != null
)

@Serializable
data class UploadedFileDto(
    val id: String,
    val url: String,
    val fileName: String,
    val mime: String,
    val size: Long
)

@Serializable
data class UploadFilesResponseDto(
    val files: List<UploadedFileDto>
)

@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val customSystemPrompt: String? = null,
    val modeInjectionIds: List<String> = emptyList(),
    val lorebookIds: List<String> = emptyList(),
    /** null = inherit the assistant's memory-graph bindings */
    val memoryGraphBindings: List<MemoryGraphBinding>? = null,
    val workspaceCwd: String? = null,
    val folderId: String? = null,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false
)

@Serializable
data class MessageNodeDto(
    val id: String,
    val messages: List<MessageDto>,
    val selectIndex: Int
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: String,
    val finishedAt: String? = null,
    val modelId: String? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null
)

@Serializable
data class ForkConversationResponse(
    val conversationId: String
)

@Serializable
data class MessageSearchResultDto(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Long,
    val snippet: String,
)

@Serializable
data class WebAuthTokenResponse(
    val token: String,
    val expiresAt: Long,
)

// ========== Error Response ==========

@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int
)

// ========== SSE Event DTOs ==========

@Serializable
data class ConversationUpdateEvent(
    val type: String = "update",
    val conversation: ConversationDto
)

@Serializable
data class ConversationSnapshotEvent(
    val type: String = "snapshot",
    val seq: Long,
    val conversation: ConversationDto,
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class ConversationNodeUpdateEvent(
    val type: String = "node_update",
    val seq: Long,
    val conversationId: String,
    val nodeId: String,
    val nodeIndex: Int,
    val node: MessageNodeDto,
    val updateAt: Long,
    val isGenerating: Boolean,
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class GenerationDoneEvent(
    val type: String = "done",
    val conversationId: String
)

@Serializable
data class ErrorEvent(
    val type: String = "error",
    val message: String
)

@Serializable
data class ConversationListInvalidateEvent(
    val type: String = "invalidate",
    val assistantId: String,
    val timestamp: Long
)

@Serializable
data class FolderListEvent(
    val assistantId: String,
    val folders: List<FolderDto>,
)

// ========== Conversion Extensions ==========

fun Conversation.toListDto(isGenerating: Boolean = false) = ConversationListDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    isPinned = isPinned,
    folderId = folderId?.toString(),
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating
)

fun me.rerere.rikkahub.data.model.Folder.toDto() = FolderDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)

fun Conversation.toDto(isGenerating: Boolean = false) = ConversationDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    messages = messageNodes.map { it.toDto() },
    chatSuggestions = chatSuggestions,
    isPinned = isPinned,
    customSystemPrompt = customSystemPrompt,
    modeInjectionIds = modeInjectionIds.map { it.toString() },
    lorebookIds = lorebookIds.map { it.toString() },
    memoryGraphBindings = memoryGraphBindings,
    workspaceCwd = workspaceCwd,
    folderId = folderId?.toString(),
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating
)

fun MessageNode.toDto() = MessageNodeDto(
    id = id.toString(),
    messages = messages.map { it.toDto() },
    selectIndex = selectIndex
)

fun UIMessage.toDto() = MessageDto(
    id = id.toString(),
    role = role.name,
    parts = parts,
    annotations = annotations,
    createdAt = createdAt.toString(),
    finishedAt = finishedAt?.toString(),
    modelId = modelId?.toString(),
    usage = usage,
    translation = translation
)
