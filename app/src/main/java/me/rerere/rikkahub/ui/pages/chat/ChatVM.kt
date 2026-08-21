package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.model.isConversationLockedNow
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    folderId: String?,
    temporary: Boolean,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val scheduleProtectionGuard: me.rerere.rikkahub.data.ai.schedule.ScheduleProtectionGuard,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState(_conversationId)

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    /** 总结模型调用状态；成功/失败都由 ChatService 清空。 */
    val summaryStatus: StateFlow<String?> =
        chatService.getSummaryStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(
                conversationId = _conversationId,
                folderId = folderId?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it) },
                temporary = temporary,
            )
        }

        // 记住对话ID, 方便下次启动恢复（临时聊天不记录）
        if (!temporary) {
            context.writeStringPreference("lastConversationId", _conversationId.toString())
        }

        // 并发写已改为事后合并（ConversationMerger），打开会话不再探锁，也不再有拦截。
    }

    // ---- 同步合并提示：真分叉且本地另存分支时才出现，仅告知不拦截 ----

    val mergeNotice: StateFlow<ChatService.MergeNotice?> =
        chatService.getMergeNoticeFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissMergeNotice() {
        chatService.dismissMergeNotice(_conversationId)
    }


    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    /** 当前对话是否在监督时段内被锁定。用于在锁生效后立即离开当前页面。 */
    val conversationLockedNow: StateFlow<Boolean> =
        combine(settings, conversation, tickerFlow(SUPERVISION_TICK_MS)) { settings, conv, _ ->
            settings.supervision.isConversationLockedNow(conv.id)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    // ---- 专注监督：本对话是否被禁止发送（2026-08-18 非白名单助手后门修复）----

    /**
     * 监督拦截原因（null = 可发）。
     *
     * `isActiveNow()` 是纯时间函数、没有事件源，所以这里必须自带分钟级 tick，
     * 否则时段刚开始时 UI 不会自动置灰（要等用户切页面才刷新）。
     */
    val supervisionBlockReason: StateFlow<String?> =
        combine(settings, conversation, tickerFlow(SUPERVISION_TICK_MS)) { settings, conv, _ ->
            val sup = settings.supervision
            when {
                !sup.isActiveNow() -> null
                // 对话锁：监工点名封的这一条，比助手白名单更硬
                sup.isConversationLockedNow(conv.id) ->
                    context.getString(R.string.supervision_blocked_conversation_locked)
                sup.allowedAssistantIds.isEmpty() -> null
                conv.assistantId in sup.allowedAssistantIds -> null
                conv.assistantId == sup.unlockGrantorAssistantId -> null
                else -> context.getString(R.string.supervision_blocked_non_study_assistant)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 本对话是否是「受保护的定时任务会话」（监督查岗）。
     *
     * 非 null = 受保护，UI 据此把停止 / 分支 / 重 roll / 删除 入口按灰，
     * 而不是让用户点下去再吃一条报错（硬拦截在 ChatService / 仓库层，UI 只是省事）。
     */
    val scheduleProtection: StateFlow<me.rerere.rikkahub.data.ai.schedule.ScheduleProtection?> =
        combine(
            scheduleProtectionGuard.protectedSessionsFlow,
            conversation,
        ) { protectedMap, conv -> protectedMap[conv.id] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 网络搜索：对话级覆盖 ?? 助手默认（2026-08-18 重构，原先只有助手级 = 改一处影响所有对话）
    val enableWebSearch: StateFlow<Boolean> = combine(
        settings,
        conversation,
    ) { settings: Settings, conv: Conversation ->
        val assistant = settings.getAssistantById(conv.assistantId) ?: settings.getCurrentAssistant()
        conv.effectiveWebSearch(assistant)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型 (优先对话绑定的 modelId -> Assistant 绑定的 chatModelId -> Settings 默认模型)
    val currentChatModel: StateFlow<Model?> = combine(
        settings,
        conversation
    ) { settings: Settings, conversation: Conversation ->
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val effectiveModelId = conversation.modelId ?: assistant.chatModelId ?: settings.chatModelId
        settings.findModelById(effectiveModelId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 本对话所属的助手（而不是「全局当前助手」）。
     *
     * agent 子会话挂在内置 Agents 助手下，用户点进去插话时全局助手通常是别人，
     * 用全局助手组装工具会拿错白名单（plan §3.2 补充项）。
     */
    private fun currentAssistantOfConversation(): Assistant {
        val settings = settings.value
        return settings.getAssistantById(conversation.value.assistantId) ?: settings.getCurrentAssistant()
    }

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(stampChangedAssistants(oldSettings, newSettings))
        }
    }

    private fun stampChangedAssistants(oldSettings: Settings, newSettings: Settings): Settings {
        val oldById = oldSettings.assistants.associateBy { it.id }
        val now = System.currentTimeMillis()
        return newSettings.copy(
            assistants = newSettings.assistants.map { assistant ->
                val old = oldById[assistant.id]
                if (old != null && assistant != old && assistant.updatedAt == old.updatedAt) {
                    assistant.copy(updatedAt = now)
                } else {
                    assistant
                }
            }
        )
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型（针对当前对话）
    fun setChatModel(model: Model) {
        chatService.setConversationModel(_conversationId, model.id)
    }

    // ---- 对话级能力覆盖（2026-08-18 重构：助手只管默认值，实际生效值以对话为准）----
    //
    // 统一走 ChatService.updateConversationOverrides：改内存态 → 异步落库 → 进同步 outbox。
    // 传 null 语义 = 恢复继承助手默认。

    /** 思维链档位（null = 继承助手默认） */
    fun setReasoningLevel(level: ReasoningLevel?) {
        chatService.updateConversationOverrides(_conversationId) { it.copy(reasoningLevel = level) }
    }

    /** 联网搜索（null = 继承助手默认） */
    fun setWebSearchEnabled(enabled: Boolean?) {
        chatService.updateConversationOverrides(_conversationId) { it.copy(enableWebSearch = enabled) }
    }

    /**
     * 切换单个 skill。
     *
     * 首次改动时以助手当前 `enabledSkills` 做**种子物化**（而不是从空集合开始），
     * 否则用户在对话里打开 skill B 会顺手把助手默认开着的 A 关掉。
     */
    fun toggleSkill(name: String, enabled: Boolean) {
        chatService.updateConversationOverrides(_conversationId) { conv ->
            val assistant = currentAssistantOfConversation()
            val base = conv.effectiveSkills(assistant)
            conv.copy(enabledSkills = if (enabled) base + name else base - name)
        }
    }

    /** 切换单个本地工具（含生图 / 子代理 / 信箱），同样以助手值做种子物化 */
    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        chatService.updateConversationOverrides(_conversationId) { conv ->
            val assistant = currentAssistantOfConversation()
            val base = conv.effectiveLocalTools(assistant)
            // 信箱工具 = 收信 + 发信（2026-08-20 合并）：Inbox 与 Send 同开同关，Send 仅作旧数据别名
            val options = if (option == LocalToolOption.Inbox) {
                listOf(LocalToolOption.Inbox, LocalToolOption.Send)
            } else {
                listOf(option)
            }
            val next = when {
                !enabled -> base - options
                // 子代理依赖收件箱收任务/指令/回报：开子代理必须同时开信箱（含发信）
                option == LocalToolOption.Subagent ->
                    (base + option + LocalToolOption.Inbox + LocalToolOption.Send).distinct()
                else -> (base + options).distinct()
            }
            conv.copy(localTools = next)
        }
    }

    /**
     * 切换单个工作区工具。
     *
     * 种子来自 workspace 配置的「默认开启」集合（由调用方算好传进来），
     * 因为工作区工具的默认值不在助手上而在 workspace 配置里。
     */
    fun toggleWorkspaceTool(toolName: String, enabled: Boolean, defaultEnabled: Set<String>) {
        chatService.updateConversationOverrides(_conversationId) { conv ->
            val base = conv.workspaceTools ?: defaultEnabled
            conv.copy(workspaceTools = if (enabled) base + toolName else base - toolName)
        }
    }

    /** 切换单个 MCP 工具（key = "serverId/toolName"），种子来自 MCP 设置里的 enable 集合 */
    fun toggleMcpTool(toolKey: String, enabled: Boolean, defaultEnabled: Set<String>) {
        chatService.updateConversationOverrides(_conversationId) { conv ->
            val base = conv.mcpTools ?: defaultEnabled
            conv.copy(mcpTools = if (enabled) base + toolKey else base - toolKey)
        }
    }

    /** 切换单个 MCP server 的挂载状态（2026-08-21 对话级下沉） */
    fun toggleMcpServer(serverId: Uuid, enabled: Boolean) {
        chatService.updateConversationOverrides(_conversationId) { conv ->
            val assistant = currentAssistantOfConversation()
            val base = conv.effectiveMcpServers(assistant)
            conv.copy(mcpServers = if (enabled) base + serverId else base - serverId)
        }
    }

    /** 记忆选项（对话级持久化，替代原先的内存 map） */
    fun setMemoryOptions(options: MemoryOptions) {
        chatService.updateConversationOverrides(_conversationId) { it.copy(memoryOptions = options) }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        // 2026-08-18 重构：工具/记忆开关已是 Conversation 上的持久字段，
        // ChatService 自己按「对话覆盖 ?? 助手默认」解析，这里不再传任何 enabledXxx。
        // 旧实现从 inputState 的内存 map 取值再传下去，重启即丢且不跨端同步。
        chatService.sendMessage(
            conversationId = _conversationId,
            content = content,
            answer = answer,
        )
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    /**
     * 在 [message] 处插入总结（方案 2026-08-08）：该消息及其之前（从上一条总结起）的内容被总结，
     * 总结消息插入该消息之后；原始消息保留，删除总结即恢复。
     */
    fun summarizeAtMessage(
        message: UIMessage,
        templateId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
    ): Job {
        // 2026-08-21：必须走 ChatService 的 Service 级 scope，不能再挂 viewModelScope ——
        // 切走对话/退出聊天页会销毁 ViewModel，压缩协程当场被 cancel，
        // 表现成「必须一直留在压缩页面，退出压缩就失效、转圈图标消失」。
        return chatService.startSummarizeTask(
            conversationId = _conversationId,
            boundaryMessageId = message.id,
            template = resolveCompressTemplate(templateId),
            additionalPrompt = additionalPrompt,
            targetTokens = targetTokens,
        )
    }

    /**
     * 整段压缩（扩展面板「压缩历史」入口用）。
     *
     * [keepRecent] = 保留最近多少条消息不进总结：分界点取倒数第 keepRecent+1 条；
     * 0 = 全部压到最新一条。分界点会跳过已有的总结消息节点，避免把总结自己当分界点。
     */
    fun summarizeToEnd(
        templateId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecent: Int = 0,
    ): Job {
        val current = conversation.value
        val nodes = current.messageNodes
        val boundaryIndex = (nodes.lastIndex - keepRecent.coerceAtLeast(0))
        val boundary = nodes.getOrNull(boundaryIndex)?.currentMessage
            ?.takeIf { it.summaryMeta == null }
            // 落到总结消息上（或 keepRecent 太大越界）时，往前找最后一条可作分界的普通消息
            ?: nodes.take((boundaryIndex + 1).coerceIn(0, nodes.size))
                .lastOrNull { it.currentMessage.summaryMeta == null }?.currentMessage
            ?: return Job().also { it.cancel() }
        return summarizeAtMessage(boundary, templateId, additionalPrompt, targetTokens)
    }

    private fun resolveCompressTemplate(templateId: Uuid): CompressTemplate {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.value.assistantId)
        return chatService.resolveCompressTemplate(settings, assistant, templateId)
    }

    /** 编辑总结消息（标题 + 正文；方案 2026-08-08 §6.3） */
    fun updateSummaryMessage(message: UIMessage, newTitle: String, newContent: String) {
        viewModelScope.launch {
            chatService.updateSummaryMessage(_conversationId, message.id, newTitle, newContent)
        }
    }

    /** 按分界点重新生成总结（同分界点插入 → 复用多版本机制，最新版本生效） */
    fun summarizeAtBoundary(
        boundaryMessageId: Uuid,
        templateId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
    ): Job {
        val current = conversation.value
        val boundaryMessage = current.messageNodes
            .firstOrNull { node -> node.messages.any { it.id == boundaryMessageId } }
            ?.currentMessage ?: return Job().also { it.cancel() }
        return summarizeAtMessage(boundaryMessage, templateId, additionalPrompt, targetTokens)
    }

    /** 切换总结节点下选中的版本（多条总结以最新为生效，但可切换查看） */
    fun selectSummaryVersion(nodeId: Uuid, index: Int) {
        viewModelScope.launch {
            runCatching { chatService.selectMessageNode(_conversationId, nodeId, index) }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation? {
        if (conversationLockedNow.value) return null
        // 受保护的定时任务会话（监督查岗）不许分叉：ChatService 会抛，这里转成错误条
        return runCatching { chatService.forkConversationAtMessage(_conversationId, message.id) }
            .onFailure { e ->
                chatService.addError(
                    error = e,
                    conversationId = _conversationId,
                    title = context.getString(R.string.error_title_operation),
                )
            }
            .getOrNull()
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            runCatching { chatService.deleteMessage(_conversationId, message) }
                .onFailure { e ->
                    chatService.addError(
                        error = e,
                        conversationId = _conversationId,
                        title = context.getString(R.string.error_title_operation),
                    )
                }
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(
            conversationId = _conversationId,
            message = message,
            regenerateAssistantMsg = regenerateAssistantMsg,
        )
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        me.rerere.common.android.ToolCallDebugLog.askUser(
            "ChatVM.handleToolAnswer",
            "conv=$_conversationId toolCallId=$toolCallId answerLen=${answer.length}",
        )
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            runCatching { conversationRepo.deleteConversation(conversation) }
                .onFailure { e ->
                    chatService.addError(
                        error = e,
                        conversationId = conversation.id,
                        title = context.getString(R.string.error_title_operation),
                    )
                }
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            // 受保护的定时任务不许换助手（换助手 = 连带清空文件夹归属，等于从监督组消失）
            chatService.scheduleProtectionBlockReason(
                conversation.id,
                me.rerere.rikkahub.data.ai.schedule.ScheduleAction.MOVE,
            )?.let { reason ->
                chatService.addError(
                    error = IllegalStateException(reason),
                    conversationId = conversation.id,
                    title = context.getString(R.string.error_title_operation),
                )
                return@launch
            }
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    /**
     * 对话级自动压缩覆盖写入口（2026-08-21 修）。
     *
     * 必须走 [ChatService.updateConversationOverrides] 而不是 `updateConversation(snapshot)`：
     * 后者拿 UI 捕获的整条 Conversation 快照整体回写，压缩/生成期间会把这期间新增的
     * messageNodes 一起吞掉；lambda 形式只改这一项，且内存态即时生效 + 异步落库 + 进同步 outbox。
     */
    fun updateAutoCompressOverride(override: AutoCompressOverride?) {
        chatService.updateConversationOverrides(_conversationId) { conv ->
            conv.copy(autoCompressOverride = override)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}

/** 每 [periodMs] 发一次的心跳流，用来驱动"随时间变化"的 UI 状态（监督时段判定）。 */
private fun tickerFlow(periodMs: Long) = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}

private const val SUPERVISION_TICK_MS = 30_000L
