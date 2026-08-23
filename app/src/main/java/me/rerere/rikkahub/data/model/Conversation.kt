package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    /**
     * 对话级记忆图绑定（方案 2026-08-07 多图体系）。
     *
     * 三态语义，**必须 nullable**：
     * - `null`   = 未设置，继承助手配置；
     * - `[]`     = 明确关闭所有图；
     * - 非空     = 显式绑定。
     *
     * lorebook 的既有语义是「allowConversationPromptInjection 一开，会话侧即唯一真源」，
     * 若用 emptyList 表示未设置，用户打开开关的瞬间绑定会全部消失 —— lorebook 消失只是人设淡了，
     * 记忆图消失是模型当场失忆。
     */
    val memoryGraphBindings: List<MemoryGraphBinding>? = null,
    /**
     * 对话级挂载的工作区（2026-08-22 从 assistant.workspaceId 下沉；2026-08-23 补第三态）。
     *
     * 三态语义，**必须 nullable**：
     * - `null` = 未设置，继承助手默认（`Assistant.workspaceId`，即「新对话默认值」）；
     * - [WORKSPACE_ID_UNBOUND]（全零 Uuid 哨兵）= 本对话**明确不挂载**任何工作区，
     *   即使助手绑了默认工作区也不继承；
     * - 其他任意 `Uuid` = 本对话显式绑定该工作区，与助手解耦。
     *
     * 早期下沉版本误以为「选未绑定写 null 就行」，但 null 在模型语义里是「继承」，
     * 助手一旦绑了工作区，对话就永远解绑不了 —— 于是补这个哨兵，正经区分
     * 「未设置（继承）」与「明确不挂」，与 memoryGraphBindings 的三态同口径。
     *
     * 读取实际挂载用 [resolveWorkspaceId] / [effectiveWorkspaceId]，**不要直接判空**。
     *
     * 与 mcpServers / enabledSkills / workspaceTools 同口径，避免再出现
     * 「在对话里改了工作区，结果该助手全部对话一起被改」的全局污染。
     */
    val workspaceId: Uuid? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 对话独立关联的模型 ID（null 表示继承助手/全局默认模型）
    val modelId: Uuid? = null,
    // 对话级自动压缩覆盖（null = 继承助手配置；方案 2026-08-08 §5.1，对话页可改）
    val autoCompressOverride: AutoCompressOverride? = null,
    // ---- 对话级能力覆盖（2026-08-18 重构：助手只提供默认值，实际生效值以对话为准）----
    /**
     * 以下字段统一遵循 **三态语义**，且必须 nullable：
     * - `null` = 未设置，继承助手默认（`Assistant` 上的同名字段）；
     * - 非 null = 本对话显式覆盖，随对话落库并参与云同步。
     *
     * 之所以不用 emptySet/emptyList 表示「未设置」：那样用户在对话里「全部关掉」
     * 就无法与「继承助手」区分，下一轮又会被助手默认值悄悄打开（与 memoryGraphBindings
     * 同一个坑，见上方注释）。
     *
     * 首次在对话里改动时由 UI 以助手当前值做**种子物化**写入全量集合，
     * 此后该对话与助手默认值彻底解耦。
     */
    val reasoningLevel: ReasoningLevel? = null,
    /** 联网搜索（null = 继承 assistant.enableWebSearch） */
    val enableWebSearch: Boolean? = null,
    /** 启用的 skill 名称全量集合（null = 继承 assistant.enabledSkills） */
    val enabledSkills: Set<String>? = null,
    /** 本地工具全量集合，含生图/子代理/信箱等（null = 继承 assistant.localTools） */
    val localTools: List<LocalToolOption>? = null,
    /** 工作区工具全量集合（null = 继承 workspace 配置里的「默认开启」） */
    val workspaceTools: Set<String>? = null,
    /** MCP 工具 key（"serverId/toolName"）全量集合（null = 继承 MCP 设置里的 enable） */
    val mcpTools: Set<String>? = null,
    /**
     * 挂载哪些 MCP server 的全量集合（null = 继承 assistant.mcpServers）。
     *
     * 2026-08-21 下沉：原先只有 `assistant.mcpServers`，一改就是该助手**所有对话**
     * 一起变（用户反馈「MCP 开关是全局的」）。现在与其他能力字段同一套三态语义，
     * `[]` = 本对话明确一个都不挂，与「未设置」严格区分。
     * 助手上的那份退化为「新对话默认值」，与 skills / localTools 口径一致。
     */
    val mcpServers: Set<Uuid>? = null,
    /** 记忆参考 / 编辑权限（null = 用 MemoryOptions() 默认值再 effective(assistant)） */
    val memoryOptions: MemoryOptions? = null,
    // 临时聊天：仅存在于内存，永不写入数据库，退出后即销毁
    @Transient
    val isTemporary: Boolean = false,
    @Transient
    val newConversation: Boolean = false
) {
    // ---- 生效值解析（唯一真源：对话覆盖 ?? 助手默认）----
    // 所有读取方必须走这几个方法，禁止直接读 assistant.xxx，
    // 否则又会退化成「改一处影响该助手的所有对话」。

    fun effectiveReasoningLevel(assistant: Assistant): ReasoningLevel =
        reasoningLevel ?: assistant.reasoningLevel

    fun effectiveWebSearch(assistant: Assistant): Boolean =
        enableWebSearch ?: assistant.enableWebSearch

    fun effectiveSkills(assistant: Assistant): Set<String> =
        enabledSkills ?: assistant.enabledSkills

    fun effectiveLocalTools(assistant: Assistant): List<LocalToolOption> =
        localTools ?: assistant.localTools

    fun effectiveMcpServers(assistant: Assistant): Set<Uuid> =
        mcpServers ?: assistant.mcpServers

    /**
     * 本对话生效的 workspaceId：对话级覆盖 ?? 助手默认（2026-08-22 下沉）。
     *
     * 助手上的 `workspaceId` 从此只是「新对话默认值」，与 mcpServers / workspaceTools 口径一致。
     * 读取方一律走这个方法，禁止直接 `assistant.workspaceId`，否则又退回「改一处影响该助手所有对话」。
     *
     * 返回值已把 [WORKSPACE_ID_UNBOUND] 哨兵规整为 null（明确不挂 → 没有工作区），
     * 调用方可以直接把它当成「真正要挂载的 workspaceId」使用。
     */
    fun effectiveWorkspaceId(assistant: Assistant): Uuid? {
        val explicit = workspaceId
        return when {
            explicit == null -> assistant.workspaceId
            explicit == WORKSPACE_ID_UNBOUND -> null
            else -> explicit
        }
    }

    /**
     * 仅解析对话自身的 workspaceId 覆盖，不回退助手默认。
     *
     * 与 [effectiveWorkspaceId] 的区别：这里不知道 assistant，只把哨兵规整成 null，
     * 用于「本对话是否显式设置过挂载」「UI 勾选项」这类只关心对话自身状态的场景。
     * - `null` = 未设置（继承）；
     * - [WORKSPACE_ID_UNBOUND] → null（明确不挂）；
     * - 其他 Uuid 原样返回。
     */
    fun resolveWorkspaceId(): Uuid? =
        if (workspaceId == WORKSPACE_ID_UNBOUND) null else workspaceId

    fun effectiveMemoryOptions(): MemoryOptions = memoryOptions ?: MemoryOptions()

    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        /**
         * 「本对话明确不挂载任何工作区」的哨兵 Uuid（全零）。
         *
         * Room 列是 `TEXT NOT NULL DEFAULT ''`，空串承载「未设置/继承」语义，
         * 没法再用空串表示「明确不挂」，于是用一个不可能是真实工作区主键的全零 Uuid 占这第三态。
         * 落库就是它的字符串形式；读取时由 [resolveWorkspaceId]/[effectiveWorkspaceId] 规整回 null。
         */
        val WORKSPACE_ID_UNBOUND: Uuid = Uuid.fromLongs(0L, 0L)

        /** 哨兵落库后的字符串形式，DAO/Repository 比较时用，别到处手写字面量。 */
        const val WORKSPACE_ID_UNBOUND_STR: String = "00000000-0000-0000-0000-000000000000"

        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false,
            modelId: Uuid? = null
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
            modelId = modelId,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
