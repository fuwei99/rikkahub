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
     * 本对话挂载的工作区（2026-08-23 重写：**创建时物化**，运行时不继承）。
     *
     * **两态**，与其余「三态覆盖」字段刻意不同：
     * - `null`  = 本对话不挂载任何工作区；
     * - `Uuid`  = 本对话挂载该工作区。
     *
     * `Assistant.workspaceId` 只在**新建对话那一刻**被复制进来当默认值，此后两者彻底解耦：
     * 改助手默认不影响任何已存在的对话，在对话里换工作区也绝不回写助手。
     *
     * 为什么不做成「null = 继承助手」的三态：那样 null 同时意味着「没设过」和「明确不挂」，
     * 助手绑了工作区的对话就永远解绑不掉。上一版为此引入了全零 Uuid 哨兵
     * （WORKSPACE_ID_UNBOUND）来表达第三态，结果哨兵在装配路径上被 `?:` 链吃掉、
     * 继续回退助手默认 —— UI 显示「未挂载」而模型实际仍在用助手的工作区。
     * 既然「继承」只在创建瞬间有意义，就在创建时物化掉，运行时只剩两态，哨兵随之消失。
     *
     * 直接判空即可，没有 effective/resolve 包装层。
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
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false,
            modelId: Uuid? = null,
            /**
             * 新对话的工作区：由调用方传 `assistant.workspaceId` 做**创建时物化**。
             * 之后该对话与助手默认值彻底解耦（见 [workspaceId] 注释）。
             */
            workspaceId: Uuid? = null,
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
            modelId = modelId,
            workspaceId = workspaceId,
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
