package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ToolCallingStrategy
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.SummaryMeta
import me.rerere.ai.util.estimateMessagesTokens
import me.rerere.ai.util.estimateTokens
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.schedule.ScheduleAction
import me.rerere.rikkahub.data.ai.schedule.ScheduleProtectionGuard
import me.rerere.rikkahub.data.ai.prompts.CompressTemplate
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_TEMPLATES
import me.rerere.rikkahub.data.ai.prompts.mergeOverride
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentInboxStore
import me.rerere.rikkahub.data.ai.agent.createAgentTools
import me.rerere.rikkahub.data.ai.agent.createAgentMailTool
import me.rerere.rikkahub.data.ai.agent.createSubAgentSideTools
import me.rerere.rikkahub.data.ai.agent.parseLocalTool
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.ai.memory.MemoryGraphBindingResolver
import me.rerere.rikkahub.data.ai.subagent.SubagentJobManager
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.SubagentTemplateManager
import me.rerere.rikkahub.data.ai.tools.MemoryGraphManageOp
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.SUPERVISION_ADMIN_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.buildSupervisionAdminTool
import me.rerere.rikkahub.data.ai.tools.local.SupervisionLockCoordinator
import me.rerere.rikkahub.data.db.dao.MemoryAutoSaveCandidateDAO
import me.rerere.rikkahub.data.db.entity.MemoryAutoSaveCandidateEntity
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.buildConversationImageReferences
import me.rerere.rikkahub.data.ai.tools.ImageFileReader
import me.rerere.rikkahub.data.ai.tools.readToolFileBytes
import me.rerere.rikkahub.data.ai.tools.createImageGenerationTool
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.IMAGE_GENERATION_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolNames
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolDefaultEnabled
import me.rerere.rikkahub.data.ai.tools.local.ToolManageContext
import me.rerere.rikkahub.data.ai.tools.local.ToolManageOp
import me.rerere.rikkahub.data.ai.tools.local.ToolManageSource
import me.rerere.rikkahub.data.ai.tools.local.buildToolManageTool
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.AssetIdAnnotationTransformer
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.ClearHistorySearchTransformer
import me.rerere.rikkahub.data.ai.transformers.CodeActionTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.UnreadHintTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryGraphBinding
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.model.ScopedMemories
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.sync.r2.MediaResolver
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.model.isConversationLockedNow
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.currentDeviceInfo
import me.rerere.common.android.ToolCallDebugLog
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** ask_user 工具名（弹窗化 / 超时兜底只针对它，见 notifyAskUserPending）。 */
private const val ASK_USER_TOOL_NAME = "ask_user"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        ClearHistorySearchTransformer,
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        CodeActionTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    private val subagentRunner: SubagentRunner,
    private val subagentJobManager: SubagentJobManager,
    private val subagentTemplateManager: SubagentTemplateManager,
    private val agentBridge: AgentBridge,
    private val agentSessionDao: AgentSessionDAO,
    private val agentInboxStore: AgentInboxStore,
    private val mediaResolver: MediaResolver,
    private val candidateDAO: MemoryAutoSaveCandidateDAO,
    private val memoryGraphBindingResolver: MemoryGraphBindingResolver,
    /** 监督管理工具用：导出 / 导入 setting-json（与偏好设置页两个按钮同一实例） */
    private val settingsJsonExchange: SettingsJsonExchange,
    /** 监督管理工具用：上锁前的申诉倒计时协调器（工具 execute 不能自己等 120 秒） */
    private val supervisionLockCoordinator: SupervisionLockCoordinator,
    /** 定时任务会话保护（禁 cancel / fork / 重 roll / 删改移动，2026-08-21） */
    private val scheduleProtectionGuard: ScheduleProtectionGuard,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()
    private val memoryOptionsByConversation = ConcurrentHashMap<Uuid, MemoryOptions>()
    private val localToolsByConversation = ConcurrentHashMap<Uuid, List<LocalToolOption>>()
    private val workspaceToolsByConversation = ConcurrentHashMap<Uuid, Set<String>>()
    private val mcpToolsByConversation = ConcurrentHashMap<Uuid, Set<String>>()

    // ---- 对话级能力覆盖的解析口径（2026-08-18 重构）----
    //
    // 上面四张 ConcurrentHashMap 原先是「UI 临时开关」的唯一载体（仅内存，杀进程即丢）。
    // 重构后用户改的是 Conversation 上的持久字段，这些 map 退化为 **agent 覆盖通道**：
    // AgentBridge 在 spawn / resume 时用 setConversationTools 把模板声明的工具集塞进来，
    // 而 agent 子对话的 Conversation 覆盖字段是 null，所以拿到的仍是模板值。
    //
    // 优先级（统一约定，别再各处自己拼）：
    //   普通对话： Conversation 持久覆盖 > 运行时/agent map > 助手默认
    //   agent 会话：模板快照 ∪ 上面那套结果（并集去重，2026-08-21）——
    //     模板声明的工具无论对话怎么关都可用，对话另开的工具照样并进来。

    private fun resolveLocalTools(conversation: Conversation, assistant: Assistant): List<LocalToolOption> =
        conversation.localTools
            ?: localToolsByConversation[conversation.id]
            ?: assistant.localTools

    private fun resolveWorkspaceTools(conversation: Conversation): Set<String>? =
        conversation.workspaceTools ?: workspaceToolsByConversation[conversation.id]

    private fun resolveMcpTools(conversation: Conversation): Set<String>? =
        conversation.mcpTools ?: mcpToolsByConversation[conversation.id]

    /**
     * 本对话挂载哪些 MCP server：对话级覆盖 ?? 助手默认（2026-08-21 下沉）。
     *
     * 助手上的 `mcpServers` 从此只是「新对话默认值」，与 skills / localTools 同口径。
     */
    private fun resolveMcpServers(
        conversation: Conversation,
        assistant: Assistant,
        supervision: SupervisionSettings,
    ): Set<Uuid> {
        // 监督期挂载锁：SupervisionGate 只看得见 Settings，管不到 Conversation 上的覆盖，
        // 不在这里收口的话「对话级挂载」就是一条绕过 lockMcpServers 的后门（§6 洞①同型）。
        val mountLocked = supervision.isActiveNow() &&
            supervision.allowedAssistantIds.isNotEmpty() &&
            assistant.id in supervision.allowedAssistantIds &&
            (supervision.lockMcpServers || supervision.lockMcpToolToggles)
        return if (mountLocked) assistant.mcpServers else conversation.effectiveMcpServers(assistant)
    }

    private fun resolveMemoryOptions(conversation: Conversation, assistant: Assistant): MemoryOptions =
        (conversation.memoryOptions
            ?: memoryOptionsByConversation[conversation.id]
            ?: MemoryOptions()).effective(assistant)

    /**
     * per-conversation workspace 身份覆盖（方案 2026-08-07 §4.8，2026-08-22 转为 agent 通道）。
     *
     * 普通对话的 workspaceId 已下沉到 `Conversation.workspaceId`（持久字段），这里只服务
     * agent 子会话：AgentBridge 在 spawn / resume 时把 profile 快照里的 workspaceId 塞进来，
     * 因为子对话虽然自己也物化了 workspaceId，但 schedule agent 的复用会话需要重启后仍能
     * 从 profile 恢复（restoreProfile 走的就是这个 map）。
     *
     * 解析顺序见 ChatService 里 effectiveWorkspaceId 的组装：
     *   agentProfile > conversation.workspaceId > 本 map > assistant.workspaceId
     */
    private val workspaceIdByConversation = ConcurrentHashMap<Uuid, String>()

    // ---- ask_user 待回答（2026-08-10 弹窗化 + 超时兜底）----

    /**
     * 正在等回答的 ask_user 超时任务，key = toolCallId。
     *
     * ask_user 的 needsApproval 恒为 true，GenerationHandler 撞到 Pending 就
     * break 停下等人；原来只在对话流里内联渲染输入框，用户没看见就是永久卡死。
     * 现在：进入 Pending 时发 [AppEvent.AskUserPending]（全局弹窗 + 系统通知），
     * 同时起一个超时 job，到点自动以「未回答」结掉，生成得以继续。
     */
    private val askUserTimeoutJobs = ConcurrentHashMap<String, Job>()

    /**
     * 正在把跨对话 ask_user 答案写回并恢复生成的会话。
     *
     * schedule-agent/subagent 的系统唤醒也会走 sendMessage()，而 sendMessage 原本会
     * 无条件取消当前 Job。若它恰好撞在用户提交答案后的恢复窗口，就会把 Answered
     * 状态再次收尾成 cancelled。唤醒必须等这条恢复链完成，不能抢占它。
     */
    private val askUserRecoveryCompletions = ConcurrentHashMap<Uuid, CompletableDeferred<Unit>>()

    // ---- 并发写提示（取代 P2 会话互斥锁）----

    /**
     * 同步合并提示：仅当云端真分叉、本地另存了一份分支时投递一次，
     * 属于"事后告知"，永不拦截用户操作。
     */
    data class MergeNotice(
        val branchTitle: String,
    )

    private val _mergeNotices = MutableStateFlow<Map<Uuid, MergeNotice>>(emptyMap())
    val mergeNotices: StateFlow<Map<Uuid, MergeNotice>> = _mergeNotices.asStateFlow()

    fun getMergeNoticeFlow(conversationId: Uuid): Flow<MergeNotice?> =
        mergeNotices.map { it[conversationId] }

    fun notifyMergeBranch(conversationId: Uuid, branchTitle: String) {
        _mergeNotices.update { it + (conversationId to MergeNotice(branchTitle)) }
    }

    fun dismissMergeNotice(conversationId: Uuid) {
        _mergeNotices.update { it - conversationId }
    }

    /**
     * 携本地 Job 启动会话改写。
     *
     * 旧版在这里先发 2~3 次 D1 请求抢会话互斥锁，发消息必须等网络往返；
     * 单人多设备场景下那把锁只产生延迟和误报。现在发送链路完全不碰网络，
     * 并发写由 [me.rerere.rikkahub.data.sync.core.ConversationMerger] 事后合并。
     */
    private fun launchLocalJob(
        errorHandler: (Exception) -> Unit = {},
        body: suspend () -> Unit,
    ): Job = appScope.launch {
        runCatching { body() }.onFailure { e ->
            if (e is CancellationException) throw e
            (e as? Exception)?.let(errorHandler)
        }
    }

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    /**
     * 专注监督：判断这条对话此刻是否被禁止生成，返回拦截原因（null = 允许）。
     *
     * 2026-08-18 修复的后门：原来白名单只挡「切换助手」这个**动作**
     * （PreferencesStore.updateAssistant / AssistantPicker 置灰 / Gate 的
     * incoming.assistantId 回滚），而「监督开始前就已经停在非白名单助手」这个
     * **状态**没人管 —— 监督期内照样能发消息、照样生成，监工完全失效。
     * 所以必须在生成入口做执行级拦截。
     *
     * 豁免（否则会把自己锁死）：
     * - 守门员助手：申诉 / 申请解锁的唯一通道，拦了就没人能救场；
     * - agent 会话（子代理 / 定时任务）：它们本就是监督体系的执行者，
     *   是否运行由 scheduleAgentsEnabledDuringSupervision 与工具过滤器决定。
     */
    suspend fun supervisionBlockReason(conversationId: Uuid): String? {
        val settings = settingsStore.settingsFlow.first()
        val sup = settings.supervision
        if (!sup.isActiveNow()) return null
        // 对话锁比白名单更硬：它是监工点名封的这一条，守门员也不例外
        // （工具已拒绝锁自己所在对话，申诉通道不会被自断）。
        if (sup.isConversationLockedNow(conversationId)) {
            return context.getString(R.string.supervision_blocked_conversation_locked)
        }
        val lockedIds = sup.allowedAssistantIds
        if (lockedIds.isEmpty()) return null

        val assistantId = getConversationFlow(conversationId).value.assistantId
        if (assistantId in lockedIds) return null
        // 守门员永远可用：解锁 / 申诉通道不能被自己掐死
        if (assistantId == sup.unlockGrantorAssistantId) return null
        // agent 子会话（含定时任务）不受助手白名单限制
        val isAgentSession = runCatching {
            agentSessionDao.getByChildId(conversationId.toString()) != null
        }.getOrDefault(false)
        if (isAgentSession) return null

        return context.getString(R.string.supervision_blocked_non_study_assistant)
    }

    /**
     * 定时任务会话保护（2026-08-21）：受保护会话上的破坏性动作一律拒绝。
     *
     * 单一收口点在 [ScheduleProtectionGuard]；这里只负责「拒绝 + 把原因喂给 UI 错误条」。
     * 与监督锁的区别：监督锁按**时段 + 名单**拦发送，这一层按**会话身份**拦破坏，
     * 监督时段之外同样生效（查岗记录任何时候都不许被抹）。
     */
    suspend fun scheduleProtectionBlockReason(
        conversationId: Uuid,
        action: ScheduleAction,
    ): String? = scheduleProtectionGuard.blockReason(conversationId, action)

    /** 拦下就顺手报错到 UI；@return true = 已拦截。 */
    private suspend fun rejectIfProtected(conversationId: Uuid, action: ScheduleAction): Boolean {
        val reason = scheduleProtectionGuard.blockReason(conversationId, action) ?: return false
        addError(
            error = IllegalStateException(reason),
            conversationId = conversationId,
            title = context.getString(R.string.error_title_operation),
        )
        return true
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    init {
        // AgentBridge 需要 ChatService 的公共能力，但 ChatService 也要用 bridge 装工具；
        // 用窄接口注入打断 Koin 循环依赖，bridge 本身不碰生成内核。
        agentBridge.attach(object : AgentBridge.Deps {
            override suspend fun initializeConversation(conversationId: Uuid, preserveCurrentAssistant: Boolean) {
                this@ChatService.initializeConversation(
                    conversationId = conversationId,
                    preserveCurrentAssistant = preserveCurrentAssistant,
                )
            }

            override fun sendMessage(
                conversationId: Uuid,
                content: List<UIMessagePart>,
                answer: Boolean,
                memoryOptions: MemoryOptions?,
                enabledLocalTools: List<LocalToolOption>?,
                enabledWorkspaceTools: Set<String>?,
                enabledMcpTools: Set<String>?,
            ) {
                this@ChatService.sendMessage(
                    conversationId = conversationId,
                    content = content,
                    answer = answer,
                    memoryOptions = memoryOptions,
                    enabledLocalTools = enabledLocalTools,
                    enabledWorkspaceTools = enabledWorkspaceTools,
                    enabledMcpTools = enabledMcpTools,
                )
            }

            override fun isGenerating(conversationId: Uuid): Boolean =
                sessions[conversationId]?.isGenerating == true

            override suspend fun awaitGenerationDone(conversationId: Uuid) {
                generationDoneFlow.first { it == conversationId }
            }

            override fun currentConversation(conversationId: Uuid): Conversation? =
                sessions[conversationId]?.state?.value

            override suspend fun stopGeneration(conversationId: Uuid) {
                // bridge 侧是系统动作（stop/archive/轮换/CALL 抢占），必须绕过用户级保护，
                // 否则受保护的定时会话连轮换都做不了。
                this@ChatService.stopGenerationInternal(conversationId)
            }

            override suspend fun finishPendingTools(conversationId: Uuid, reason: String) {
                // 先落库当前内存态，再请求优雅停轮（2026-08-08 子代理历史丢失事故）：
                // 子 agent 回报（agent_report 工具 / 自动回报）走这里结束本轮。
                // 不能 job.cancel()：那是从生成协程内部取消自己——正在执行的 agent_report 的
                // 结果合并（GenerationHandler 的 merge+emit）会被 CancellationException 掐掉，
                // 工具永远停在「未执行」，下一轮 sendMessage 的兜底 finishInterruptedPendingTools
                // 会用默认的 "Generation cancelled by user" 把它误标成用户取消
                // （2026-08-13 用户反馈：没点取消却显示 cancelled）。
                // 改为置 stopAfterCurrentStep：本轮工具执行完后优雅 break，正常走 onSuccess 落库。
                runCatching {
                    sessions[conversationId]?.state?.value?.let { saveConversation(conversationId, it) }
                }.onFailure { Log.w(TAG, "finishPendingTools save failed for $conversationId", it) }
                sessions[conversationId]?.stopAfterCurrentStep?.value = true
                // 兜底收尾：把本轮未执行工具标成 Denied(真实原因)，进程崩溃/异常时不残留悬挂工具。
                // 正常路径下其落库会被随后 merge 出的「已执行」状态覆盖，幂等。
                finishInterruptedPendingTools(conversationId, interruptReason = reason)
            }

            override fun handleToolApproval(
                conversationId: Uuid,
                toolCallId: String,
                approved: Boolean,
                reason: String,
                answer: String?,
            ) {
                this@ChatService.handleToolApproval(conversationId, toolCallId, approved, reason, answer)
            }

            override fun setConversationWorkspace(conversationId: Uuid, workspaceId: Uuid?) {
                if (workspaceId == null) {
                    workspaceIdByConversation.remove(conversationId)
                } else {
                    workspaceIdByConversation[conversationId] = workspaceId.toString()
                }
            }

            override fun setConversationTools(
                conversationId: Uuid,
                localTools: List<LocalToolOption>?,
                workspaceTools: Set<String>?,
                mcpTools: Set<String>?,
            ) {
                localTools?.let { localToolsByConversation[conversationId] = it }
                workspaceTools?.let { workspaceToolsByConversation[conversationId] = it }
                mcpTools?.let { mcpToolsByConversation[conversationId] = it }
            }

            override fun mergeConversationTools(
                conversationId: Uuid,
                localTools: List<LocalToolOption>?,
                workspaceTools: Set<String>?,
                mcpTools: Set<String>?,
            ) {
                localTools?.let { new ->
                    localToolsByConversation[conversationId] = (localToolsByConversation[conversationId].orEmpty() + new).distinct()
                }
                workspaceTools?.let { new ->
                    workspaceToolsByConversation[conversationId] = workspaceToolsByConversation[conversationId].orEmpty() + new
                }
                mcpTools?.let { new ->
                    mcpToolsByConversation[conversationId] = mcpToolsByConversation[conversationId].orEmpty() + new
                }
            }
        })

        // 自动回报：agent 子会话跑完 → 摘要投递回父对话（暂停态判定在 bridge 内做）
        // 必须显式 Dispatchers.Default：appScope 是 Main，onGenerationDone 里有 DAO 查询、
        // 摘要拼装和 reportToParent 投递，挂在主线程上三路 agent 同时回报会把主线程烧满（ANR）。
        appScope.launch(Dispatchers.Default) {
            generationDoneFlow.collect { id ->
                runCatching { agentBridge.onGenerationDone(id) }
                    .onFailure { Log.w(TAG, "agent auto report failed: $id", it) }
            }
        }

        // 归档 agent 会话的保留期清理（默认 7 天）
        agentBridge.scheduleCleanup()

        // 监督管理工具把某个对话锁了 → 正在生成的那条得当场掐掉
        // （只拦 sendMessage 不够：锁下来时 agent 可能已经在跑，用户就能眼看着它写完）。
        appScope.launch(Dispatchers.Default) {
            settingsStore.settingsFlow
                .map { it.supervision.lockedConversationIds }
                .distinctUntilChanged()
                .collect { locked -> cancelGenerationsInLocked(locked) }
        }
    }

    /**
     * 掐断被监督锁定的对话里正在跑的生成。
     *
     * 不走 [stopGeneration]：那个会 saveConversation 整条状态，而这里可能在
     * 设置流回调里被高频触发；只做 cancel + 未执行工具收尾（收尾内部自己落库）。
     */
    private suspend fun cancelGenerationsInLocked(locked: Set<Uuid>) {
        if (locked.isEmpty()) return
        // 时段外锁不生效，别把人正常的生成给砍了
        if (!settingsStore.settingsFlow.value.supervision.isActiveNow()) return
        locked.forEach { id ->
            val session = sessions[id] ?: return@forEach
            val job = session.getJob() ?: return@forEach
            if (!job.isActive) return@forEach
            job.cancel()
            runCatching { job.join() }
            runCatching { finishInterruptedPendingTools(id, "Locked by supervision") }
                .onFailure { Log.w(TAG, "cancelGenerationsInLocked cleanup failed: $id", it) }
            Log.i(TAG, "generation cancelled by supervision lock: $id")
        }
    }

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        return getOrCreateSession(conversationId).generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    /** 当前会话的总结任务状态；总结完成/失败后回到 null。 */
    fun getSummaryStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).summaryStatus
    }

    /** 当前会话的压缩 Job；非 null 且 active = 后台正在压缩（UI 转圈的真源）。 */
    fun getSummaryJobFlow(conversationId: Uuid): StateFlow<Job?> {
        return getOrCreateSession(conversationId).summaryJob
    }

    /** 取消该会话正在跑的后台压缩。 */
    fun cancelSummarize(conversationId: Uuid) {
        sessions[conversationId]?.cancelSummaryJob()
    }

    /**
     * 后台启动压缩任务（2026-08-21 下沉，修「切走对话压缩暴毙」）。
     *
     * 挂在 [appScope]（Service 单例）而非 ChatVM.viewModelScope：切对话/退出聊天页销毁 VM
     * 不再影响这条协程。期间持有会话引用，防止 idle 回收把 session 连同 Job 一起清掉。
     *
     * 同一会话已有活跃压缩时直接返回那条 Job，不并发起第二次（重复点按钮不烧双倍 token）。
     */
    fun startSummarizeTask(
        conversationId: Uuid,
        boundaryMessageId: Uuid,
        template: CompressTemplate,
        additionalPrompt: String = "",
        targetTokens: Int = 2000,
    ): Job {
        val session = getOrCreateSession(conversationId)
        session.summaryJob.value?.takeIf { it.isActive }?.let { return it }
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            addConversationReference(conversationId)
            try {
                summarizeConversation(
                    conversationId = conversationId,
                    conversation = getConversationFlow(conversationId).value,
                    boundaryMessageId = boundaryMessageId,
                    template = template,
                    additionalPrompt = additionalPrompt,
                    targetTokens = targetTokens,
                ).onFailure { e ->
                    addError(e, conversationId, title = context.getString(R.string.error_title_compress_conversation))
                }
            } finally {
                removeConversationReference(conversationId)
            }
        }
        // LAZY + 先登记后 start：避免任务在 trySetSummaryJob 之前就跑完，
        // 导致 invokeOnCompletion 清空的是一个还没装上的槽，UI 永远停在「压缩中」。
        if (!session.trySetSummaryJob(job)) {
            job.cancel()
            return session.summaryJob.value ?: job
        }
        job.start()
        return job
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(
        conversationId: Uuid,
        folderId: Uuid? = null,
        temporary: Boolean = false,
        /**
         * 不把全局当前助手切成本会话的助手。
         *
         * 用户点进 agent 子对话看一眼，不应该把全局助手静默换成 `Agents`。
         * 默认 false 保持原行为（零回归面）。
         */
        preserveCurrentAssistant: Boolean = false,
    ) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        val currentState = session.state.value
        // Do not overwrite an in-memory conversation that is actively generating or already loaded.
        // Re-entering a chat page while a tool call is running used to reload the stale DB copy and
        // erase in-flight tool state/results from memory.
        if (session.isGenerating || currentState.messageNodes.isNotEmpty() || currentState.newConversation) {
            if (!preserveCurrentAssistant) settingsStore.updateAssistant(currentState.assistantId)
            return
        }
        val conversation = if (temporary) null else conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            if (!preserveCurrentAssistant) settingsStore.updateAssistant(conversation.assistantId)
            // agent 会话：回填执行快照（workspace 身份 / 工具白名单），
            // per-conversation 覆盖 map 全内存，重启后不回填就会拿到全局助手的配置。
            runCatching { agentBridge.restoreProfile(conversationId) }
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            // 继承上一个 active 对话的模型；若当前为空，则继承 assistant.chatModelId 或 全局模型
            val inheritedModelId = session.state.value.modelId
                ?: assistant.chatModelId
                ?: currentSettings.chatModelId
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true,
                modelId = inheritedModelId
            ).updateCurrentMessages(assistant.presetMessages)
                .copy(folderId = folderId, isTemporary = temporary)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        /** null = 用对话自己的持久设置（UI 常态）；非 null = 调用方显式覆盖（agent / web API） */
        memoryOptions: MemoryOptions? = null,
        enabledLocalTools: List<LocalToolOption>? = null,
        enabledWorkspaceTools: Set<String>? = null,
        enabledMcpTools: Set<String>? = null,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        val askUserRecovery = askUserRecoveryCompletions[conversationId]
        // 用户刚提交 ask_user 时，previousJob 是原来产生 Pending 的生成；恢复 Job
        // 由 deferred 标识。系统唤醒不能取消原生成或恢复链，必须先等答案落库。
        if (askUserRecovery == null) {
            previousJob?.cancel()
        }

        // 注：旧版这里会 agentBridge.onUserMessage() 丢弃待投递的回报（可延后项）——
        // 收件箱内核（I2）后消息无条件先落库，用户发言不再丢任何回报，该钩子已删除。

        val job = launchLocalJob {
            try {
                runCatching { askUserRecovery?.await() }
                runCatching { previousJob?.join() }
                // 专注监督：非白名单助手直接拒绝发送（连用户消息都不落库）
                supervisionBlockReason(conversationId)?.let { reason ->
                    addError(
                        error = IllegalStateException(reason),
                        conversationId = conversationId,
                        title = context.getString(R.string.supervision_blocked_title),
                    )
                    return@launchLocalJob
                }
                // Sending a new message cancels the previous generation, but that is not the
                // same as the user pressing the explicit Stop button. Keep the interruption
                // cause truthful so a pending ask_user/tool is not reported as user-cancelled.
                finishInterruptedPendingTools(
                    conversationId,
                    interruptReason = "Generation interrupted by a new user message",
                )

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)
                // 附件资产索引化：聊天消息只持久化 asset://managed-files/<uuid>。
                // R2 只作为后台同步副本，不再阻塞发送。
                val uploadResult = mediaResolver.uploadLocalAttachmentsWithReport(processedContent)
                val uploadedContent = uploadResult.parts
                if (uploadResult.failures.isNotEmpty()) {
                    addError(
                        error = IllegalStateException(uploadResult.failures.distinct().joinToString("；")),
                        conversationId = conversationId,
                        title = "附件索引失败",
                    )
                }

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = uploadedContent,
                        // 结构化记录发信设备（不进正文）：历史工具/查岗 agent 靠它 + createdAt
                        // 判断「这条是在哪台机器上、什么时候发的」。
                        device = currentDeviceInfo(),
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 自动压缩（方案 2026-08-08 §5）：消息入库后、生成前检查，命中则先压缩再生成（本轮生效）
                maybeAutoCompress(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    // 2026-08-18 重构：能力开关已下沉为 Conversation 持久字段，
                    // 这几个 map 只作为 agent/外部调用方的显式覆盖通道。
                    // 必须用 `?.let` 而非 `?:` 兜助手默认 —— 否则 UI 不传参时会把
                    // 「助手默认」固化进 map，反而盖掉对话自己的持久覆盖。
                    memoryOptions?.let { memoryOptionsByConversation[conversationId] = it.effective(assistant) }
                    enabledLocalTools?.let { localToolsByConversation[conversationId] = it }
                    enabledWorkspaceTools?.let { workspaceToolsByConversation[conversationId] = it }
                    enabledMcpTools?.let { mcpToolsByConversation[conversationId] = it }
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job, cancelPrevious = askUserRecovery == null)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
        enabledLocalTools: List<LocalToolOption>? = null,
        enabledWorkspaceTools: Set<String>? = null,
        enabledMcpTools: Set<String>? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = launchLocalJob {
            try {
                // 专注监督：非白名单助手不许重新生成（否则拦了发送还能靠重生成绕过）
                supervisionBlockReason(conversationId)?.let { reason ->
                    addError(
                        error = IllegalStateException(reason),
                        conversationId = conversationId,
                        title = context.getString(R.string.supervision_blocked_title),
                    )
                    return@launchLocalJob
                }
                // 定时任务保护：受保护会话禁止重 roll（重 roll = 把查岗结论重新摇一遍）
                if (rejectIfProtected(conversationId, ScheduleAction.REGENERATE)) return@launchLocalJob
                val conversation = session.state.value

                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: settings.getCurrentAssistant()
                // 同 sendMessage：只有显式传入才写覆盖 map，不传就走对话持久设置
                enabledLocalTools?.let { localToolsByConversation[conversationId] = it }
                enabledWorkspaceTools?.let { workspaceToolsByConversation[conversationId] = it }
                enabledMcpTools?.let { mcpToolsByConversation[conversationId] = it }

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    // ---- ask_user：弹窗化 + 超时兜底（2026-08-10）----

    /** 同一个 ask_user 只提醒一次（Messages chunk 每个 token 都来一发）。 */
    private val notifiedAskUsers = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )

    /**
     * 发现 ask_user 进入 Pending → 弹全局弹窗 + 系统通知 + 起超时兜底。
     *
     * 只认 ask_user：普通危险工具的审批本来就有卡片按钮，且默默超时放行
     * 危险操作是灾难；ask_user 的语义是「问一句话」，超时不回答只是拿不到
     * 答案，让模型自己决策比永久卡死好得多。
     */
    private fun notifyAskUserPending(conversationId: Uuid, lastMessage: UIMessage) {
        val timeoutMinutes = settingsStore.settingsFlow.value
            .displaySetting.askUserTimeoutMinutes
        lastMessage.getTools()
            .filter { it.toolName == ASK_USER_TOOL_NAME && it.isPending }
            .forEach { tool ->
                // 每个 chunk 都会走到这儿，靠 set 去重，只在首次进入 Pending 时发
                if (!notifiedAskUsers.add(tool.toolCallId)) return@forEach
                ToolCallDebugLog.askUserLazy("ChatService.notifyPending") {
                    "conv=$conversationId toolCallId=${tool.toolCallId} " +
                        "timeoutMin=$timeoutMinutes inputLen=${tool.input.length}"
                }

                val firstQuestion = runCatching {
                    tool.inputAsJson().jsonObject["questions"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("question")?.jsonPrimitive?.contentOrNull
                }.getOrNull().orEmpty()

                val deadlineAt = if (timeoutMinutes > 0) {
                    System.currentTimeMillis() + timeoutMinutes * 60_000L
                } else {
                    0L   // 0 = 用户关掉了超时，永久等（老行为）
                }

                appEventBus.tryEmit(
                    AppEvent.AskUserPending(
                        conversationId = conversationId,
                        toolCallId = tool.toolCallId,
                        argumentsJson = tool.input.ifBlank { "{}" },
                        firstQuestion = firstQuestion,
                        deadlineAt = deadlineAt,
                    )
                )

                if (timeoutMinutes <= 0) return@forEach
                // 超时兜底：到点仍未回答 → 以「未回答」结掉，生成继续往下跑。
                // 用 appScope 而非 session job：session job 正是那条停下来等人的生成，
                // 挂在它上面会被 handleToolApproval 的 cancel 一起带走。
                askUserTimeoutJobs[tool.toolCallId]?.cancel()
                askUserTimeoutJobs[tool.toolCallId] = launchLocalJob {
                    delay(timeoutMinutes * 60_000L)
                    // 竞态：期间人已经回答过 → handleToolApproval 已把 job 摘掉，别重复兜底
                    if (askUserTimeoutJobs[tool.toolCallId] == null) return@launchLocalJob
                    ToolCallDebugLog.askUser(
                        "ChatService.timeoutFired",
                        "conv=$conversationId toolCallId=${tool.toolCallId} after=${timeoutMinutes}min",
                    )
                    Log.i(TAG, "ask_user timed out after ${timeoutMinutes}min: ${tool.toolCallId}")
                    // 走 Answered 而不是 Denied：让模型知道是「没人应答」，
                    // 而不是「用户拒绝了这次提问」，两者后续决策完全不同。
                    // 关弹窗 / 撤通知统一由 handleToolApproval → handleAskUserResolved 做，
                    // 这里不要自己再发一次 AskUserResolved（会重复）。
                    handleToolApproval(
                        conversationId = conversationId,
                        toolCallId = tool.toolCallId,
                        approved = true,
                        answer = buildJsonObject {
                            put("timeout", JsonPrimitive(true))
                            put(
                                "error",
                                JsonPrimitive(
                                    "No answer from the user within $timeoutMinutes minutes. " +
                                        "The user is probably away. Do not ask again; " +
                                        "proceed with your best judgement and state the assumption you made."
                                )
                            )
                        }.toString(),
                    )
                }
            }
    }

    /** 回答 / 超时 / 取消：撤超时任务 + 关弹窗 + 撤通知。 */
    private fun handleAskUserResolved(toolCallId: String) {
        val had = askUserTimeoutJobs.remove(toolCallId)?.also { it.cancel() } != null
        val wasNotified = notifiedAskUsers.remove(toolCallId)
        ToolCallDebugLog.askUserLazy("ChatService.resolved") {
            "toolCallId=$toolCallId hadTimeoutJob=$had wasNotified=$wasNotified " +
                "emitResolved=${had || wasNotified}"
        }
        if (had || wasNotified) {
            appEventBus.tryEmit(AppEvent.AskUserResolved(toolCallId))
        }
    }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        ToolCallDebugLog.askUserLazy("ChatService.approvalEnter") {
            val snapshot = session.state.value
            "conv=$conversationId toolCallId=$toolCallId approved=$approved " +
                "answerLen=${answer?.length ?: -1} answerHead=${answer?.take(120)?.replace("\n", "\\n")} " +
                "sessionIsGenerating=${session.isGenerating} sessionNodes=${snapshot.messageNodes.size} " +
                "sessionNewConv=${snapshot.newConversation}"
        }
        // 先登记完成信号，再取消旧生成；schedule-agent 的 wake 在任意线程到达时
        // 看到 deferred 后会等待恢复链，而不是取消它。
        val recoveryCompletion = CompletableDeferred<Unit>()
        askUserRecoveryCompletions[conversationId] = recoveryCompletion
        handleAskUserResolved(toolCallId)

        // The generation which produced the Pending tool can still be finishing its
        // cancellation/onCompletion cleanup when the user taps Submit.  Merely calling
        // cancel() lets that cleanup race this patch and write the old Pending/Denied
        // snapshot over the answer (the dialog closes, but the answer is empty/lost).
        // Cancel first, then join it inside the new job before touching the conversation.
        val previousGenerationJob = session.getJob()
        previousGenerationJob?.cancel()
        ToolCallDebugLog.askUserLazy("ChatService.approvalCancelPrev") {
            "toolCallId=$toolCallId prevJob=${previousGenerationJob != null} " +
                "prevActive=${previousGenerationJob?.isActive}"
        }

        // recoveryCompletion 已在取消旧 Job 前注册，避免 wake 插入到取消与登记之间。
        val job = launchLocalJob {
            try {
                runCatching { previousGenerationJob?.join() }
                ToolCallDebugLog.askUserLazy("ChatService.approvalPrevJoined") {
                    val snapshot = session.state.value
                    "toolCallId=$toolCallId isGenerating=${session.isGenerating} " +
                        "nodes=${snapshot.messageNodes.size} newConv=${snapshot.newConversation}"
                }

                // 幻影会话修复（2026-08-18）：全局弹窗/通知可以在**任何**页面回答，
                // 而被提问的那个对话（查岗 agent / 子 agent）早已因 5s 空闲被 removeSession 回收。
                // getOrCreateSession 这时会用「全局当前助手」造一个空的内存 Conversation，
                // 答案打在幻影上 → 没有任何 tool 被 patch → hasPendingTools=false →
                // handleMessageComplete 拿着空消息列表去生成 → handleMessageChunk 报
                // 「messages must not be empty」。表现就是「不在原对话里就提交失败」。
                // initializeConversation 在内存已有历史时直接 return（幂等），只在幻影态回库捞真身。
                runCatching { initializeConversation(conversationId, preserveCurrentAssistant = true) }
                    .onFailure { Log.w(TAG, "handleToolApproval: restore conversation failed for $conversationId", it) }
                ToolCallDebugLog.askUserLazy("ChatService.approvalAfterInit") {
                    val snapshot = session.state.value
                    // 幻影会话的特征：nodes=0（或不含目标 toolCallId）且 isGenerating=true 把
                    // initializeConversation 的回捉早退掉了。这行就是判别竞态的关键证据。
                    "toolCallId=$toolCallId isGenerating=${session.isGenerating} " +
                        "nodes=${snapshot.messageNodes.size} newConv=${snapshot.newConversation} " +
                        "assistantId=${snapshot.assistantId}"
                }

                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // 目标 toolCall 根本不在这条对话里（历史已被清理 / 传错 id）：
                // 绝不能继续往下 handleMessageComplete —— 那会拿空历史发起一次生成，
                // 既报错又白烧 token，还会让定时任务无限重试。
                val toolExists = conversation.messageNodes.any { node ->
                    node.messages.any { msg ->
                        msg.parts.any { it is UIMessagePart.Tool && it.toolCallId == toolCallId }
                    }
                }
                if (!toolExists) {
                    Log.w(TAG, "handleToolApproval: toolCallId $toolCallId not found in $conversationId, ignored")
                    ToolCallDebugLog.askUserLazy("ChatService.approvalToolMissing") {
                        val allIds = conversation.messageNodes.flatMap { node ->
                            node.messages.flatMap { msg ->
                                msg.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolCallId }
                            }
                        }
                        "ANSWER DROPPED toolCallId=$toolCallId conv=$conversationId " +
                            "nodes=${conversation.messageNodes.size} knownToolCallIds=$allIds"
                    }
                    return@launchLocalJob
                }
                ToolCallDebugLog.askUserLazy("ChatService.approvalToolFound") {
                    "toolCallId=$toolCallId newState=${newApprovalState::class.simpleName}"
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(approvalState = newApprovalState)
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)
                ToolCallDebugLog.askUserLazy("ChatService.approvalSaved") {
                    val persisted = session.state.value.messageNodes.flatMap { node ->
                        node.messages.flatMap { msg ->
                            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                                .filter { it.toolCallId == toolCallId }
                        }
                    }
                    "toolCallId=$toolCallId savedStates=" +
                        persisted.joinToString { "${it.approvalState::class.simpleName}/executed=${it.isExecuted}" }
                }

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    ToolCallDebugLog.askUser(
                        "ChatService.approvalResume",
                        "toolCallId=$toolCallId -> handleMessageComplete(conv=$conversationId)",
                    )
                    handleMessageComplete(conversationId)
                } else {
                    ToolCallDebugLog.askUser(
                        "ChatService.approvalStillPending",
                        "toolCallId=$toolCallId other pending tools remain, generation not resumed",
                    )
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                ToolCallDebugLog.askUser(
                    "ChatService.approvalError",
                    "toolCallId=$toolCallId ${e.javaClass.simpleName}: ${e.message}",
                )
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        job.invokeOnCompletion {
            recoveryCompletion.complete(Unit)
            askUserRecoveryCompletions.remove(conversationId, recoveryCompletion)
        }
        session.setJob(job)
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        ToolCallDebugLog.askUserLazy("ChatService.completeEnter") {
            val snapshot = getConversationFlow(conversationId).value
            "conv=$conversationId range=$messageRange nodes=${snapshot.messageNodes.size} " +
                "messages=${snapshot.currentMessages.size} lastTools=" +
                snapshot.currentMessages.lastOrNull()?.getTools()?.joinToString {
                    "${it.toolName}/${it.toolCallId}/${it.approvalState::class.simpleName}/executed=${it.isExecuted}"
                }.orEmpty()
        }
        // 专注监督兜底：所有生成路径（发送 / 重生成 / 工具批准续跑）都汇到这里，
        // 在这一层拦住才能保证没有第四个入口漏网。
        supervisionBlockReason(conversationId)?.let { reason ->
            Log.w(TAG, "handleMessageComplete blocked by supervision: $conversationId ($reason)")
            return
        }
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(
            initialConversation.modelId
                ?: assistant.chatModelId
                ?: settings.chatModelId
        ) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            val modelSupportsTools = model.toolCallingStrategy != ToolCallingStrategy.OFF

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            // 工具权限来源诊断：普通对话 = 对话级持久覆盖 ?? 助手默认；
            // subagent / schedule agent = 模板快照 ∪ 对话状态（MERGE，见下方注释）。
            val agentProfile = runCatching { agentBridge.profileOf(conversationId) }.getOrNull()
            // 每次生成前重读模板工具并入会话（2026-08-22）：ensureScheduleTools 只在触发时跑，
            // 触发间隙改模板（如加微信 MCP 白名单）实时对话吃不到 —— 这里生成前兜底补一次 merge。
            if (agentProfile != null) {
                runCatching { agentBridge.refreshScheduleTools(conversationId) }
                    .onFailure { Log.w(TAG, "refreshScheduleTools failed for $conversationId", it) }
            }
            // workspace 挂载：对话级覆盖 > 运行时/agent map > 助手默认（2026-08-22 下沉）。
            // 与 mcpServers / workspaceTools 同口径，禁止再直接读 assistant.workspaceId —— 那是
            // 「新对话默认值」，在对话里改挂载不能污染该助手的其他对话。
            // WORKSPACE_ID_UNBOUND 哨兵（明确不挂）在这里规整成 null，不继续回退助手默认。
            val conversationWorkspaceId = conversation.workspaceId?.let { cid ->
                if (cid == Conversation.WORKSPACE_ID_UNBOUND) null else cid.toString()
            }
            val effectiveWorkspaceId = agentProfile?.workspaceId
                ?: conversationWorkspaceId
                ?: workspaceIdByConversation[conversationId]
                ?: assistant.workspaceId?.toString()
            // agent 会话 = MERGE 语义（2026-08-21 用户明确要求）：
            //   模板声明的工具**无论对话状态如何都可用**，对话另开的工具并进来，去重。
            // 原实现是 `profile ?: conversation` 的**替换**语义，两个坑：
            //   ① 对话里为本轮临时开的工具在 agent 会话中被静默吞掉；
            //   ② 模板事后加的工具（如微信 MCP）在复用会话里永远不生效
            //      （见 bugs/2026-08-20_查岗agent微信MCP不注入.md，那次只在内存 map 里 merge，
            //       又被 conversation 的持久值整体压过，等于没 merge）。
            // 普通对话不受影响：agentProfile == null → 仍是「对话覆盖 ?? 助手默认」。
            val conversationWorkspaceTools = resolveWorkspaceTools(conversation)
            val effectiveWorkspaceTools: Set<String>? = if (agentProfile != null) {
                (agentProfile.workspaceTools.toSet() +
                    conversationWorkspaceTools.orEmpty() +
                    workspaceToolsByConversation[conversationId].orEmpty())
                    .takeIf { it.isNotEmpty() }
            } else {
                conversationWorkspaceTools
            }
            val conversationMcpTools = resolveMcpTools(conversation)
            val effectiveMcpTools: Set<String>? = if (agentProfile != null) {
                // 运行时 map 也必须并进来：ScheduleAgentRunner 的复用路径靠
                // mergeConversationTools 把模板新增工具塞进 map（2026-08-20 微信 MCP 不注入），
                // 而 profile 快照是建会话时固化的旧值，只读 profile 等于那次修复白做。
                (agentProfile.mcpTools.toSet() +
                    conversationMcpTools.orEmpty() +
                    mcpToolsByConversation[conversationId].orEmpty())
                    .takeIf { it.isNotEmpty() }
            } else {
                conversationMcpTools
            }
            // 挂载哪些 MCP server：对话覆盖 ?? 助手默认；agent 侧再并上模板/运行时 mcpTools 蕴含的 server
            // （key = "serverId/toolName"，模板只声明了工具却没挂 server 时工具照样进不来）。
            val conversationMcpServers = resolveMcpServers(conversation, assistant, settings.supervision)
            val effectiveMcpServers: Set<Uuid> = if (agentProfile != null) {
                conversationMcpServers + effectiveMcpTools.orEmpty().mapNotNull { key ->
                    runCatching { Uuid.parse(key.substringBefore('/')) }.getOrNull()
                }
            } else {
                conversationMcpServers
            }
            val assistantLocalTools = if (agentProfile != null) {
                (agentProfile.localTools.mapNotNull { parseLocalTool(it) } +
                    localToolsByConversation[conversationId].orEmpty() +
                    resolveLocalTools(conversation, assistant)).distinct()
            } else {
                resolveLocalTools(conversation, assistant)
            }
            val workspaceState = effectiveWorkspaceId?.let { workspaceRepository.getById(it) }
            ToolCallDebugLog.askUserLazy("ChatService.toolAssemblyContext") {
                "conv=$conversationId agent=${agentProfile != null} " +
                    "model=${model.id} strategy=${model.toolCallingStrategy} " +
                    "profileLocal=${agentProfile?.localTools.orEmpty()} " +
                    "profileWorkspace=${agentProfile?.workspaceTools.orEmpty()} " +
                    "profileMcp=${agentProfile?.mcpTools.orEmpty()} " +
                    "conversationLocal=${conversation.localTools?.map { it.toString() }.orEmpty()} " +
                    "conversationWorkspace=${conversation.workspaceTools.orEmpty()} " +
                    "conversationMcp=${conversation.mcpTools.orEmpty()} " +
                    "conversationMcpServers=${conversation.mcpServers?.map { it.toString() }.orEmpty()} " +
                    "effectiveLocal=${assistantLocalTools.map { it.toString() }} " +
                    "effectiveWorkspace=${effectiveWorkspaceTools.orEmpty()} " +
                    "effectiveMcp=${effectiveMcpTools.orEmpty()} " +
                    "effectiveMcpServers=${effectiveMcpServers.map { it.toString() }} " +
                    "workspaceId=$effectiveWorkspaceId " +
                    "workspaceExists=${workspaceState != null} " +
                    "workspaceStatus=${workspaceState?.shellStatus}"
            }

            // start generating
            val generationMessages = conversation.currentMessages.let {
                if (messageRange != null) {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                } else {
                    it
                }
            }
            // 空历史绝不发起生成（2026-08-18）：会话被空闲回收后拿到的幻影 Conversation、
            // 或历史被 checkInvalidMessages 清空的边界，都会走到这里。往下走的结局是
            // handleMessageChunk 撞空列表 + provider 收到空 messages 报 400，
            // 对定时任务而言就是「失败 → 重试 → 弹窗」的死循环。
            if (generationMessages.isEmpty()) {
                Log.w(TAG, "handleMessageComplete: empty messages, skip generation for $conversationId")
                ToolCallDebugLog.askUser(
                    "ChatService.completeEmpty",
                    "conv=$conversationId after checkInvalidMessages generationMessages=0 -> skip",
                )
                return
            }
            // 发送给模型前会把 asset:// 临时解析成 provider 可接受的 URL / file / data。
            // 注意 outgoingMessages 是传输层形态，绝不能写回会话；会话存储必须保持 asset://。
            // 记忆图注入固化（对齐日期模式的稳定注入位）：生成前把 <memory_graph> 块写进最新 user 消息并随会话落库，
            // 历史前缀逐轮字节级稳定 → 前缀缓存才能命中；重新生成/工具续跑/已有块不重算，保持历史字节不变。
            val effectiveMemoryOptions = resolveMemoryOptions(conversation, assistant)
            // 记忆图绑定解析（Resolver 是唯一运行时真源）：注入、tool 可写集合、抽屉全用它的输出。
            val resolvedGraphBindings = runCatching {
                memoryGraphBindingResolver.resolve(
                    assistant = assistant,
                    conversation = conversation,
                    options = conversation.memoryOptions
                        ?: memoryOptionsByConversation[conversationId]
                        ?: MemoryOptions(),
                    maxEnabledGraphs = settings.memorySearch.sanitized().maxEnabledGraphs,
                )
            }.getOrDefault(emptyList())
            val enabledGraphs = resolvedGraphBindings.filter { it.enabled }.map { it.meta }
            val memoryInjectedMessages = if (enabledGraphs.isNotEmpty()) {
                runCatching {
                    generationHandler.injectGraphMemoryIfNeeded(
                        settings = settings,
                        assistant = assistant,
                        messages = generationMessages,
                        memoryOptions = effectiveMemoryOptions,
                        graphs = enabledGraphs,
                    )
                }.getOrDefault(generationMessages)
            } else {
                generationMessages
            }
            if (memoryInjectedMessages != generationMessages) {
                val current = getConversationFlow(conversationId).value
                updateConversation(conversationId, current.updateCurrentMessages(memoryInjectedMessages))
            }
            val storageMessages = memoryInjectedMessages
            // 方案 2026-08-08 §3.6：发送给模型时折叠被总结覆盖的历史（只注入最新总结）。
            // foldedMessages 比 storageMessages 短，生成回来的消息必须按「折叠后前缀长度」对齐，
            // 否则 mergeTransportGenerationMessages 会把 assistant 回复当成历史前缀吞掉（丢消息）。
            val foldedMessages = foldSummarizedMessages(
                storageMessages,
                presetMessageCount = assistant.presetMessages.size,
            )
            val foldedPrefixSize = foldedMessages.size
            val outgoingMessages = mediaResolver.prepareOutgoingMessages(
                foldedMessages,
                model,
            )
            val session = getOrCreateSession(conversationId)
            // 新的一轮生成开始：清掉上一轮可能残留的优雅停轮标记
            // （子 agent 回报后置位，若随后又有新任务/唤醒，必须重新允许完整生成）。
            session.stopAfterCurrentStep.value = false
            val scopedMemories = ScopedMemories(
                assistant = if (effectiveMemoryOptions.referenceAssistantMemory) {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                } else emptyList(),
                global = if (effectiveMemoryOptions.referenceGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else emptyList(),
            )
            ToolCallDebugLog.askUserLazy("ChatService.generateStart") {
                "conv=$conversationId generationMessages=${generationMessages.size} " +
                    "outgoingMessages=${outgoingMessages.size} foldedPrefix=$foldedPrefixSize " +
                    "lastTools=" + outgoingMessages.lastOrNull()?.getTools()?.joinToString {
                        "${it.toolName}/${it.toolCallId}/${it.approvalState::class.simpleName}/executed=${it.isExecuted}"
                    }.orEmpty()
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = outgoingMessages,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversationId,
                // 思维链档位：对话级覆盖 ?? 助手默认（2026-08-18 重构）
                reasoningLevel = conversation.reasoningLevel,
                memoryOptions = effectiveMemoryOptions,
                graphBindings = resolvedGraphBindings,
                // 2026-08-12：管理开关从 assistant 设置改为「会话级覆盖 ?? 助手默认」（记忆卡片可单独开关）
                graphManageEnabled = effectiveMemoryOptions.allowManageMemoryGraphs ?: false,
                onGraphManage = { op -> onConversationGraphManage(assistant, conversationId, op) },
                stopAfterCurrentStep = session.stopAfterCurrentStep,
                memories = scopedMemories,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(WorkspaceReminderTransformer(workspaceRepository, effectiveWorkspaceTools, effectiveWorkspaceId))
                    add(CodeActionTransformer)
                    // 邮件内核 Step 4：未读提示逐步注入，生成中途新到的信下一步可见（读后自动消失）
                    add(UnreadHintTransformer(conversationId, agentInboxStore))
                    // 告知模型每张图片附件的 asset id, 让它能精确指认「第几张图」,
                    // 并在回复里用 asset:// 或裸 uuid 引用原图。无条件注入(有图才真正生效):
                    // 纯文本模型走 OCR 路径时由 OcrTransformer 补同一行, 这里兜底视觉模型路径。
                    add(AssetIdAnnotationTransformer)
                },
                outputTransformers = outputTransformers,
                tools = if (!modelSupportsTools) {
                    emptyList()
                } else {
                    // 记录 mcp__name -> "serverId/toolName" 映射，供监督过滤器用
                    val mcpToolKeys = mutableMapOf<String, String>()
                    val rawTools = buildList<Tool> {
                    // 监督管理工具：双重门（用户开闸 + 身份）。守门员=全量 action（含
                    // request_unlock —— 2026-08-18 起原 supervision_request_unlock 并入此处，
                    // 不再无开关常驻）；
                    // adminScheduleAgentIds 内的定时任务=只能加锁。开关本身在助手本地工具页，默认关。
                    if (assistantLocalTools.contains(LocalToolOption.SupervisionAdmin)) {
                        val scheduleTemplateId = runCatching {
                            agentSessionDao.getByChildId(conversationId.toString())?.templateId
                        }.getOrNull()
                        buildSupervisionAdminTool(
                            settingsStore = settingsStore,
                            settingsJsonExchange = settingsJsonExchange,
                            lockCoordinator = supervisionLockCoordinator,
                            conversationId = conversationId,
                            assistantId = assistant.id,
                            scheduleTemplateId = scheduleTemplateId,
                        )?.let { add(it) }
                    }
                    // 联网搜索：对话级覆盖 > 助手默认（2026-08-18 重构）
                    if (conversation.effectiveWebSearch(assistant)) {
                        addAll(createSearchTools(settings))
                    }
                    // agent 会话的工具权限使用上面解析出的 AgentProfile 快照，不能被普通对话的
                    // Conversation.localTools/workspaceTools/mcpTools 空集合覆盖。
                    val conversationImageReferences = buildConversationImageReferences(outgoingMessages)
                    // 让生图工具能直接拿工作区 / 挂载点里的图做参考图, 与 read_file 共用同一套读取逻辑。
                    val imageFileReader: ImageFileReader? = effectiveWorkspaceId
                        ?.let { wsId ->
                            ImageFileReader { path -> workspaceRepository.readToolFileBytes(wsId, path) }
                        }
                    val imageGenerationToolEnabled =
                        model.tools.contains(me.rerere.ai.provider.BuiltInTools.ImageGeneration) ||
                            assistantLocalTools.contains(LocalToolOption.ImageGeneration)
                    if (imageGenerationToolEnabled) {
                        // 构造失败（如未选生图模型 / provider 缺失）只应该少一个工具,
                        // 绝不能把整轮生成炸掉 —— 否则用户看到的是「对话直接报错」,
                        // 而真实原因只是生图配置不全。
                        runCatching {
                            createImageGenerationTool(
                                settings,
                                providerManager,
                                filesManager,
                                conversationImageReferences,
                                imageFileReader,
                            )
                        }.onSuccess { add(it) }
                            .onFailure { Log.w(TAG, "createImageGenerationTool failed, tool omitted", it) }
                    }
                    addAll(localTools.getTools(assistantLocalTools - LocalToolOption.ImageGeneration - LocalToolOption.Subagent))

                    // ---- tool_manage：让 AI 自己查/开关本对话工具（2026-08-21）----
                    // 它本身是个本地工具开关；开着才挂。开关结果写对话级覆盖并落库，下一轮生效。
                    if (assistantLocalTools.contains(LocalToolOption.ToolManage)) {
                        val tmWorkspaceState = effectiveWorkspaceId
                            ?.let { runCatching { workspaceRepository.getById(it) }.getOrNull() }
                        val tmWorkspaceOverrides =
                            tmWorkspaceState?.toolDefaultEnabledOverrides().orEmpty()
                        val tmWorkspaceDefault = WorkspaceToolNames
                            .filter { resolveWorkspaceToolDefaultEnabled(it, tmWorkspaceOverrides) }
                            .toSet()
                        // 实际生效（已挂载）的 workspace 工具：与 createWorkspaceTools 的筛选口径一致——
                        // 对话/agent 有显式集合就用它，否则退回 workspace 配置的「默认开启」集合。
                        // 直接报 effectiveWorkspaceTools.orEmpty() 会把普通对话（null=继承默认）误报成「全关」。
                        val tmEffectiveWorkspace = effectiveWorkspaceTools ?: tmWorkspaceDefault
                        val tmAllSkills = skillManager.listSkills().map { it.name to it.description }
                        val tmMcpServerTools = settings.mcpServers.map { server ->
                            Triple(
                                server.id,
                                server.commonOptions.name,
                                server.commonOptions.tools,
                            )
                        }
                        val tmAllServers = settings.mcpServers.map { server ->
                            Triple(server.id, server.commonOptions.name, server.commonOptions.enable)
                        }
                        val tmConversation = getConversationFlow(conversationId).value
                        // 实际生效的 MCP 工具 key 集合：与下方 MCP 装配块的 selected 口径一致——
                        // 对话/agent 有显式集合就用它，否则用挂载 server 下所有 enable 的工具。
                        val tmEffectiveMcpTools = effectiveMcpTools ?: settings.mcpServers
                            .filter { server -> server.id in effectiveMcpServers }
                            .flatMap { server ->
                                server.commonOptions.tools.filter { t -> t.enable }
                                    .map { "${server.id}/${it.name}" }
                            }
                            .toSet()
                        add(
                            buildToolManageTool(
                                contextProvider = {
                                    ToolManageContext(
                                        conversation = tmConversation,
                                        assistant = assistant,
                                        effectiveLocal = assistantLocalTools,
                                        effectiveWorkspace = tmEffectiveWorkspace,
                                        workspaceDefaultEnabled = tmWorkspaceDefault,
                                        workspaceAvailable = effectiveWorkspaceId != null &&
                                            tmWorkspaceState?.shellStatus == WorkspaceShellStatus.READY.name,
                                        mountedMcpServers = effectiveMcpServers,
                                        effectiveMcpTools = tmEffectiveMcpTools,
                                        allMcpServers = tmAllServers,
                                        effectiveSkills = conversation.effectiveSkills(assistant),
                                        allSkills = tmAllSkills,
                                        webSearchEnabled = conversation.effectiveWebSearch(assistant),
                                        mcpServerTools = tmMcpServerTools,
                                    )
                                },
                                onToggle = { op -> applyToolManageToggle(conversationId, op) },
                            )
                        )
                    }

                    // ---- 「对话即 Agent」工具接入（方案 2026-08-07 §9）----
                    // 本对话本身就是一个 agent 子会话 → 给它子侧工具（report / ask / send peer）；
                    // 否则开了 Subagent 开关时给主侧工具（spawn / status / read / review ...）。
                    if (agentProfile != null) {
                        addAll(
                            createSubAgentSideTools(
                                bridge = agentBridge,
                                conversationId = conversationId,
                                allowPeerMessaging = agentProfile.allowPeerMessaging,
                            )
                        )
                    }
                    if (assistantLocalTools.contains(LocalToolOption.Subagent)) {
                        addAll(
                            createAgentTools(
                                bridge = agentBridge,
                                templateManager = subagentTemplateManager,
                                conversationId = conversationId,
                                workspaceCwd = conversation.workspaceCwd,
                                fetchConversation = { target, mode, maxChars ->
                                    readAgentConversation(target, mode, maxChars)
                                },
                            )
                        )
                    }
                    // 旧黑盒 subagent（createSubagentTools）不再暴露给模型：交互式派活统一走 agent 工具。
                    // 它仍保留给 visibility=silent 的模板 / 记忆抽取 / 定时任务静默（那些路径直接调 SubagentRunner）。
                    // agent_mail 单工具（2026-08-11 合并 inbox / send / await；2026-08-20 开关合并为「信箱工具」）：
                    // - read / send：由同一个「信箱工具」开关（LocalToolOption.Inbox）统一控制；
                    //   子代理一旦开启就必须能查收并回报（任务/指令/回报全走信箱），故 Subagent 隐含信箱；
                    //   Send 是合并前的旧开关，仅作旧数据兼容（同开同关）。
                    // - await：阻塞等信 + 攒批合并返回，仅子代理开启时挂（唯一合法等待方式，I7 禁 sleep/轮询）。
                    val mailboxOn = assistantLocalTools.contains(LocalToolOption.Inbox) ||
                        assistantLocalTools.contains(LocalToolOption.Send) ||
                        assistantLocalTools.contains(LocalToolOption.Subagent)
                    val mailRead = mailboxOn
                    val mailSend = mailboxOn
                    val mailAwait = assistantLocalTools.contains(LocalToolOption.Subagent)
                    if (mailRead || mailSend || mailAwait) {
                        add(
                            createAgentMailTool(
                                inboxStore = agentInboxStore,
                                bridge = agentBridge,
                                conversationId = conversationId,
                                allowRead = mailRead,
                                allowSend = mailSend,
                                allowAwait = mailAwait,
                            )
                        )
                    }
                    if (effectiveMemoryOptions.referenceRecentChats == true) {
                        addAll(
                            createConversationTools(
                                conversationRepo = conversationRepo,
                                assistantId = assistant.id,
                                conversationId = conversationId,
                                assistantsProvider = {
                                    settingsStore.settingsFlow.value.assistants.map { it.id to it.name }
                                },
                            )
                        )
                    }
                    // 根据 toolCallingStrategy 判断是否过滤掉文件写/改/Patch 工具
                    val wsTools = createWorkspaceToolsIfReady(
                        effectiveWorkspaceId,
                        conversation.workspaceCwd,
                        effectiveWorkspaceTools,
                    )
                    addAll(wsTools)
                    // Skills：对话级覆盖 > 助手默认（2026-08-18 重构）
                    val effectiveSkills = conversation.effectiveSkills(assistant)
                    if (effectiveSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = effectiveSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools(effectiveMcpServers).also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        val key = "$serverId/${tool.name}"
                        val selected = effectiveMcpTools
                            ?: settings.mcpServers
                                .filter { server -> server.id in effectiveMcpServers }
                                .flatMap { server -> server.commonOptions.tools.filter { t -> t.enable }.map { t -> "${server.id}/${t.name}" } }
                                .toSet()
                        if (key !in selected) return@forEach
                        val mcpToolName = "mcp__${serverName}__${tool.name}"
                        mcpToolKeys[mcpToolName] = key
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                    }
                    // 专注监督：在最终工具集上按黑名单/白名单收口（见 PLAN_SUPERVISION_LOCK §4）。
                    // 必须在 buildList 之后做，才能一并覆盖 per-conversation 的临时工具开关。
                    val sup = settings.supervision
                    val finalTools = if (!sup.isActiveNow()) {
                        rawTools
                    } else {
                        rawTools.filter { tool ->
                            when {
                                // agent_mail / chat_history 刻意不参与监督过滤：
                                // 前者是 agent 收派活指令的唯一通道（砍掉 = agent 变聋子，任务静默失败），
                                // 后者只读自己的历史，不构成"分心入口"。
                                tool.name == "agent_mail" || tool.name == "chat_history" -> true
                                // 监督管理工具：它就是监工通道本身，挂载条件（双重门）已是唯一闸门。
                                // 再被 localToolFilter 筛一道 = 监督期把自己唯一的自救入口锁死（§6 洞①翻版）。
                                tool.name == SUPERVISION_ADMIN_TOOL_NAME -> true
                                tool.name in LocalToolOption.ALL_SERIAL_NAMES ||
                                    tool.name == IMAGE_GENERATION_TOOL_NAME ->
                                    sup.localToolFilter.allows(tool.name)
                                tool.name.startsWith("workspace_") ->
                                    sup.workspaceToolFilter.allows(tool.name)
                                tool.name.startsWith("mcp__") -> {
                                    val key = mcpToolKeys[tool.name]
                                    sup.mcpToolFilter.allows(key ?: tool.name)
                                }
                                else -> true
                            }
                        }
                    }
                    ToolCallDebugLog.askUserLazy("ChatService.toolAssemblyFinal") {
                        "conv=$conversationId agent=${agentProfile != null} " +
                            "model=${model.id} strategy=${model.toolCallingStrategy} " +
                            "rawCount=${rawTools.size} raw=${rawTools.joinToString { it.name }} " +
                            "finalCount=${finalTools.size} final=${finalTools.joinToString { it.name }} " +
                            "workspaceId=$effectiveWorkspaceId workspaceExists=${workspaceState != null} " +
                            "workspaceStatus=${workspaceState?.shellStatus} " +
                            "workspaceRequested=${effectiveWorkspaceTools.orEmpty()}"
                    }
                    finalTools
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新 + 落库。
                // 只更新内存不落库会让「已生成但被取消」的消息丢失：子 agent 回报时
                // finishPendingTools 会 cancel 生成 job，生成流走 onFailure 的
                // CancellationException 分支跳过兜底落库，导致子代理的回复从不写进
                // message_node，会话空闲回收后历史全丢（2026-08-08 辩论赛事故）。
                // onSuccess 的 saveConversation 保留（幂等），这里做取消路径的兜底。
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                // 用 NonCancellable：生成协程可能已被 finishPendingTools cancel
                // （子 agent 回报时序），取消态下 suspend 落库会直接抛
                // CancellationException 被吞掉，历史就丢了（2026-08-08 事故）。
                runCatching {
                    withContext(NonCancellable) { saveConversation(conversationId, updatedConversation) }
                }.onFailure { Log.w(TAG, "saveConversation on completion failed for $conversationId", it) }
                ToolCallDebugLog.askUserLazy("ChatService.genOnCompletionSave") {
                    // 这里是可能把 Answered 反写回 Pending 的兵家必争之地：
                    // 它拿的是自己那份快照，若跟 answer patch 抢序就会盖掉答案。
                    val tools = updatedConversation.messageNodes.lastOrNull()
                        ?.currentMessage?.getTools()
                        ?.filter { it.toolName == ASK_USER_TOOL_NAME }
                        .orEmpty()
                    if (tools.isEmpty()) "conv=$conversationId no ask_user in last node"
                    else "conv=$conversationId wrote " + tools.joinToString {
                        "${it.toolCallId}/${it.approvalState::class.simpleName}/executed=${it.isExecuted}"
                    }
                }

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val storageSafeMessages = mergeTransportGenerationMessages(
                            storageMessages = storageMessages,
                            transportMessages = chunk.messages,
                            transportPrefixSize = foldedPrefixSize,
                        )
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(storageSafeMessages)
                        updateConversation(conversationId, updatedConversation)
                        // The important ask_user/tool-resume case updates an existing message
                        // instead of appending a new one. Keep a compact before/after trace so
                        // a future regression cannot look like a provider/UI-only failure.
                        if (chunk.messages.size <= foldedPrefixSize) {
                            val mergedTools = storageSafeMessages
                                .flatMap { it.getTools() }
                                .joinToString { tool ->
                                    "${tool.toolName}/${tool.toolCallId}/" +
                                        "${tool.approvalState::class.simpleName}/executed=${tool.isExecuted}"
                                }
                            if (mergedTools.isNotEmpty()) {
                                ToolCallDebugLog.askUserLazy("ChatService.mergeExistingMessage") {
                                    "conv=$conversationId storage=${storageMessages.size} " +
                                        "transport=${chunk.messages.size} prefix=$foldedPrefixSize " +
                                        "tools=$mergedTools"
                                }
                            }
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        storageSafeMessages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                            // ask_user 进入待回答：弹全局弹窗 + 发通知 + 起超时兜底，
                            // 否则内联输入框没被看见时这条生成就永远停在 Pending 上。
                            notifyAskUserPending(conversationId, lastMessage)
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            ToolCallDebugLog.askUser(
                "ChatService.generateFailure",
                "conv=$conversationId cancellation=${it is CancellationException} " +
                    "${it.javaClass.simpleName}: ${it.message}",
            )

            if (it !is CancellationException) {
                // 异常中断（网络波动/模型异常等）必须与用户取消(❌)保持一致：
                // 把内存里已生成的半成品消息落库，并收尾未执行工具。
                // 否则整个生成过程只更新内存态（session.state），DB 一直是旧快照，
                // 会话一旦被回收重建（切走 5s 空闲、SSE 断线重连、进程重启）或
                // 下一次 checkInvalidMessages 清理未执行工具节点，整条 assistant 消息就丢了。
                runCatching {
                    finishInterruptedPendingTools(
                        conversationId,
                        interruptReason = "Generation interrupted: ${it.javaClass.simpleName}"
                    )
                }
                // finishInterruptedPendingTools 在无未执行工具时会提前 return 不落库，
                // 纯文本流式中途失败必须靠这里兜底保存已输出的部分。
                runCatching {
                    withContext(NonCancellable) {
                        saveConversation(conversationId, getConversationFlow(conversationId).value)
                    }
                }

                it.printStackTrace()
                addError(it, conversationId, title = context.getString(R.string.error_title_generation))
                // agent 子会话：API 报错/超时 → 状态 error + 系统消息告知父对话（2026-08-14 需求）。
                // 标 ERROR(TERMINAL) 后，随后的 generationDoneFlow emit 不会把半成品当完成自动回报。
                runCatching {
                    agentBridge.onGenerationError(
                        conversationId,
                        it.message ?: it.javaClass.simpleName,
                    )
                }.onFailure { Log.w(TAG, "onGenerationError failed for $conversationId", it) }
                Logging.log(TAG, "handleMessageComplete: $it")
                Logging.log(TAG, it.stackTraceToString())
            }
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)
            ToolCallDebugLog.askUserLazy("ChatService.generateSuccess") {
                "conv=$conversationId nodes=${finalConversation.messageNodes.size} " +
                    "messages=${finalConversation.currentMessages.size} lastTools=" +
                    finalConversation.currentMessages.lastOrNull()?.getTools()?.joinToString {
                        "${it.toolName}/${it.toolCallId}/${it.approvalState::class.simpleName}/executed=${it.isExecuted}"
                    }.orEmpty()
            }

            // agent 会话默认关标题/建议生成：子对话是真对话，每轮额外两次模型调用
            // 乘以 N 个 agent 直接烧钱；标题已由 bridge 写成「<模板名> · <task 摘要>」。
            val isAgentSession = runCatching {
                agentSessionDao.getByChildId(conversationId.toString()) != null
            }.getOrDefault(false)
            if (!isAgentSession) {
                launchWithConversationReference(conversationId) {
                    generateTitle(conversationId, finalConversation)
                }
                launchWithConversationReference(conversationId) {
                    generateSuggestion(conversationId, finalConversation)
                }
            }

            // 记忆图 P3：对话完成 → 入队自动提炼候选（助手开启时才入队；攒批 ≥5 条再抽取，默认关）
            // 门槛看 binding（新语义）或老字段（老配置），别只看老字段 —— 老字段被收敛置 false 后会哑掉
            val anyGraphBound = assistant.memoryGraphBindings.any { it.enabled || it.writable } ||
                assistant.enableMemoryGraph ||
                assistant.enableAssistantMemoryGraph ||
                assistant.enableGlobalMemoryGraph
            if (anyGraphBound && assistant.enableMemoryAutoExtract) {
                launchWithConversationReference(conversationId) {
                    runCatching {
                        candidateDAO.insert(
                            MemoryAutoSaveCandidateEntity(
                                assistantId = assistant.id.toString(),
                                chatId = conversationId.toString(),
                                triggerTimestamp = finalConversation.currentMessages.lastOrNull()?.createdAt
                                    ?.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault())
                                    ?.toEpochMilliseconds()
                                    ?: System.currentTimeMillis(),
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 主 agent 拉子对话细节（`agent action=read`）。
     *
     * 主上下文默认只吃摘要，需要具体过程时才按需拉，且必须截断。
     */
    private suspend fun readAgentConversation(target: Uuid, mode: String, maxChars: Int): String {
        val conversation = sessions[target]?.state?.value
            ?: conversationRepo.getConversationById(target)
            ?: return """{"type":"agent_read","error":"conversation not found: $target"}"""
        val messages = conversation.currentMessages
        val selected = if (mode == "tail") messages.takeLast(8) else messages
        var used = 0
        var truncated = false
        val body = buildString {
            selected.forEach { message ->
                if (used >= maxChars) {
                    truncated = true
                    return@forEach
                }
                val text = message.summaryAsText(maxLength = (maxChars - used).coerceAtMost(4000))
                used += text.length
                append("[").append(message.role.name.lowercase()).append("] ")
                append(text).append("\n\n")
            }
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("agent_read"))
            put("conversation_id", kotlinx.serialization.json.JsonPrimitive(target.toString()))
            put("title", kotlinx.serialization.json.JsonPrimitive(conversation.title))
            put("messages", kotlinx.serialization.json.JsonPrimitive(messages.size))
            put("truncated", kotlinx.serialization.json.JsonPrimitive(truncated))
            put("content", kotlinx.serialization.json.JsonPrimitive(body))
        }.toString()
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        enabledTools: Set<String>? = null,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) {
            Log.w(TAG, "toolAssembly.workspace: no workspace id, requested=${enabledTools.orEmpty()}")
            return emptyList()
        }
        val workspace = workspaceRepository.getById(workspaceId)
        if (workspace == null) {
            Log.w(
                TAG,
                "toolAssembly.workspace: workspace missing id=$workspaceId requested=${enabledTools.orEmpty()}"
            )
            return emptyList()
        }
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.w(
                TAG,
                "toolAssembly.workspace: workspace not ready id=$workspaceId status=${workspace.shellStatus} " +
                    "requested=${enabledTools.orEmpty()}"
            )
            return emptyList()
        }
        val tools = createWorkspaceTools(workspaceId, workspaceRepository, cwd, enabledTools)
        Log.i(
            TAG,
            "toolAssembly.workspace: workspace=$workspaceId status=${workspace.shellStatus} " +
                "requested=${enabledTools.orEmpty()} provided=${tools.map { it.name }}"
        )
        return tools
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // 已解决的工具（Denied 已拒绝/已取消、Answered 已回答）必须保留：
                // 取消路径 finishInterruptedPendingTools 会把未执行工具标成 Denied，
                // 若在这里整条删除，agent 内部取消（endChildTurn cancel）产生的回复
                // 会在下一轮被清掉，历史丢失（2026-08-08 辩论赛事故）。
                val allSettled = node.currentMessage.getTools().all {
                    it.approvalState is ToolApprovalState.Denied ||
                        it.approvalState is ToolApprovalState.Answered
                }
                if (allSettled) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                ToolCallDebugLog.askUserLazy("ChatService.checkInvalidDropNode") {
                    val ids = node.currentMessage.getTools()
                        .joinToString { "${it.toolName}#${it.toolCallId}/${it.approvalState::class.simpleName}" }
                    "conv=$conversationId dropping node with unresolved tools: $ids"
                }
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    /**
     * 收尾被打断的生成：把最后一条消息中未执行的 Tool 标记为已取消并落库。
     *
     * 用户取消（stopGeneration / 新发送前的 previousJob cancel）走这里；
     * 异常中断路径（onFailure）现在也会调用，避免残留未执行 Tool 的
     * assistant 节点被后续 checkInvalidMessages 整条删除。
     *
     * @param interruptReason 中断原因，写入 Tool 的 Denied 状态（默认保持"用户取消"语义）。
     */
    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        interruptReason: String = "Generation cancelled by user"
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val pendingTools = lastMessage.getTools().filter { !it.isExecuted }
        if (pendingTools.isNotEmpty()) {
            ToolCallDebugLog.askUserLazy("ChatService.interruptedTools") {
                "conv=$conversationId reason=$interruptReason tools=" +
                    pendingTools.joinToString {
                        "${it.toolName}/${it.toolCallId}/" +
                            it.approvalState::class.simpleName
                    }
            }
        }
        // 生成被打断 = 没人再会回答这些 ask_user：收掉弹窗/通知/超时任务，
        // 否则弹窗会挂在屏幕上问一个已经作废的问题。
        lastMessage.getTools()
            .filter { it.toolName == ASK_USER_TOOL_NAME && it.isPending }
            .forEach {
                ToolCallDebugLog.askUser(
                    "ChatService.interruptedPending",
                    "conv=$conversationId toolCallId=${it.toolCallId} -> Denied($interruptReason)",
                )
                handleAskUserResolved(it.toolCallId)
            }
        val updatedMessage = lastMessage.finishPendingTools { tool ->
            // Answered 表示真人答案已经写入，只是工具结果尚未由下一轮生成补上。
            // 不能把它当成普通 !isExecuted 工具再次改成 cancelled，否则
            // schedule-agent 的系统唤醒会覆盖刚提交的跨对话答案。
            if (tool.approvalState is ToolApprovalState.Answered) {
                tool
            } else {
                cancelToolByUser(tool).copy(approvalState = ToolApprovalState.Denied(interruptReason))
            }
        }
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        // NonCancellable：本函数可能在生成协程已取消的路径被调用（stopGeneration 等），
        // 取消态下 suspend 落库会直接抛 CancellationException 被吞掉，Denied 状态就丢了，
        // 未执行工具会残留到下一轮被误标（2026-08-13 子代理「没取消却显示 cancelled」）。
        runCatching {
            withContext(NonCancellable) { saveConversation(conversationId, updatedConversation) }
        }.onFailure { Log.w(TAG, "finishInterruptedPendingTools save failed for $conversationId", it) }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取（临时聊天取内存态）
            val latest = if (conversation.isTemporary) {
                sessions[conversationId]?.state?.value
            } else {
                conversationRepo.getConversationById(conversation.id)
            }
            latest?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * 以 [boundaryMessageId] 为分界点生成对话总结（方案 2026-08-08 重构）。
     *
     * - 增量总结：输入 = 上一条总结（若有，title+正文） + (上一条总结分界点, 本次分界点] 的原始消息，
     *   上一条总结之前的上下文不再重复喂给模型；
     * - 总结作为独立消息节点插入分界点之后；同一分界点重新总结 → 该节点新版本（复用多版本机制）；
     * - 原始消息永不删除，删除总结消息即恢复上下文；
     * - 返回生成的 [SummaryMeta]。
     */
    suspend fun summarizeConversation(
        conversationId: Uuid,
        conversation: Conversation,
        boundaryMessageId: Uuid,
        template: CompressTemplate,
        additionalPrompt: String = "",
        targetTokens: Int = 2000,
    ): Result<SummaryMeta> {
        val session = getOrCreateSession(conversationId)
        session.summaryStatus.value = context.getString(R.string.chat_page_compressing)
        return try {
            runCatching {
        val settings = settingsStore.settingsFlow.first()
        val nodes = conversation.messageNodes
        val boundaryNodeIndex = nodes.indexOfFirst { node -> node.messages.any { it.id == boundaryMessageId } }
        if (boundaryNodeIndex == -1) throw IllegalStateException("Boundary message not found")

        // 增量起点：**分界点之前**最近的一条总结（其分界点之前的内容不再重复喂）。
        // 必须严格取 boundaryNodeIndex 之前的总结：
        // - 「重新生成」时同分界点的旧总结就在 boundaryNodeIndex+1，取它会算出
        //   coveredStart > boundaryNodeIndex 直接抛「消息不足」→ 重新生成 100% 失败；
        // - 在更早的消息处插入总结时，取全局最后一条总结同样会误判为消息不足。
        val prevSummaryNodeIndex = (0 until boundaryNodeIndex)
            .lastOrNull { nodes[it].currentMessage.summaryMeta != null } ?: -1
        val prevSummary: UIMessage? = prevSummaryNodeIndex.takeIf { it >= 0 }
            ?.let { nodes[it].currentMessage }
        val prevBoundaryNodeIndex = prevSummary?.summaryMeta?.boundaryMessageId?.let { prevId ->
            nodes.indexOfFirst { node -> node.messages.any { m -> m.id == prevId } }
        } ?: -1

        // 覆盖区间：(上一条总结分界点, 本次分界点]（无上一条总结时从对话开头算）
        val coveredStart = (prevBoundaryNodeIndex + 1).coerceAtLeast(0)
        if (coveredStart > boundaryNodeIndex) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        // 覆盖区里要排掉总结消息本身：上一条总结节点就落在这个区间内
        // （它在 prevBoundary 之后），若不排掉就会既进 {previous_summary} 又进 {content}，
        // 上一条总结被喂两遍，正是「第二次总结重复喂前文」要避免的事。
        val messagesToCompress = nodes.subList(coveredStart, boundaryNodeIndex + 1)
            .map { it.currentMessage }
            .filter { it.summaryMeta == null }
        if (messagesToCompress.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        // 压缩模型：模板模型 > 对话模型 > 全局聊天模型 > 全局压缩模型
        val model = template.modelId?.let { settings.findModelById(it) }
            ?: conversation.modelId?.let { settings.findModelById(it) }
            ?: settings.getCurrentChatModel()
            ?: settings.findModelById(settings.compressModelId)
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val previousSummaryText = prevSummary?.let { s ->
            val title = s.summaryMeta?.title?.takeIf { it.isNotBlank() }?.let { "[$it]\n" } ?: ""
            title + s.toText()
        } ?: ""
        val reasoningLevel = template.reasoningEffort?.let(::reasoningLevelFromEffort) ?: ReasoningLevel.AUTO

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(
            contentToCompress: String,
            previousSummary: String,
        ): String {
            val prompt = template.prompt.applyPlaceholders(
                "content" to contentToCompress,
                "previous_summary" to previousSummary,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val params = backgroundTextGenerationParams(model, reasoningLevel)

            // 单次尝试：streaming=true 走流式（一路有 chunk 心跳，网关不会因为
            // 「几十秒没数据」判死连接 524，也绕开部分只认 stream=true 的渠道），
            // streaming=false 走非流式。返回压缩正文，空/空白视为失败。
            suspend fun compressOnce(
                streaming: Boolean,
                messages: List<UIMessage>,
            ): String {
                if (streaming) {
                    var out = listOf<UIMessage>()
                    providerHandler.streamText(
                        providerSetting = provider,
                        messages = messages,
                        params = params,
                    ).collect { chunk ->
                        out = out.handleMessageChunk(chunk, model)
                    }
                    // 只取正文：reasoning 不进 toText()，所以思考模型的思维链不会污染总结。
                    return out.lastOrNull { it.role == MessageRole.ASSISTANT }?.toText()?.trim().orEmpty()
                }
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = params,
                )
                return result.choices[0].message?.toText()?.trim().orEmpty()
            }

            // 质量兜底（KeyRoulette 的 HTTP 层重试管不到的两类失败 + 长度校验）：
            // 1. 流式吐过 chunk 后中断：executeWithRetryFlow 保证「已发射就不重试」
            //    （防重复输出），但那是**同一次调用内**的限制；压缩是后台任务、输出
            //    不展示，业务层重发一次全新流式调用没有重复风险，且流式有 chunk 心跳
            //    防网关 524、兼容只认 stream=true 的渠道——重试仍走流式，不降级非流式。
            // 2. 「成功但返回空 / 不足 500 字」：发生在 HTTP 成功之后，重试框架看不见。
            // 3. 结果 ≥500 字但可能被 max_tokens 截断：追加一条「继续」消息补全，
            //    明确要求不要重复上文。
            // 方案：最多 2 次完整尝试（失败/太短时隔 1s 重试）；
            // 拿到 ≥500 字的结果后再发「继续」消息收尾，补全失败则保留原结果。
            val minLength = 500
            val initialMessages = listOf(UIMessage.user(prompt))
            var lastError: Throwable? = null
            var text = ""
            var attempt = 0
            while (attempt < 2 && (text.isBlank() || text.length < minLength)) {
                attempt++
                try {
                    text = compressOnce(streaming = template.streaming, messages = initialMessages)
                    if (text.isNotBlank() && text.length >= minLength) break
                    lastError = IllegalStateException(
                        "Compressed summary too short (${text.length} chars, need >= $minLength)"
                    )
                } catch (e: CancellationException) {
                    throw e // 用户取消必须透传，不能吞进重试循环
                } catch (e: Exception) {
                    lastError = e
                }
                if (attempt < 2) delay(1_000) // 两次尝试之间喘口气，避开同一瞬断
            }
            if (text.isBlank() || text.length < minLength) {
                throw lastError ?: IllegalStateException("Failed to generate compressed summary")
            }

            // 够长但可能被截断：追加「继续」消息，模型基于完整上下文续写剩余部分。
            val continued = runCatching {
                compressOnce(
                    streaming = template.streaming,
                    messages = initialMessages + UIMessage.user(
                        "以上总结可能因长度限制被截断。请直接继续输出剩余内容，" +
                            "不要重复任何已经写过的内容，不要重新输出标题，接着上文末尾继续。"
                    ),
                )
            }.getOrNull()
            return if (continued.isNullOrBlank()) text else text.trimEnd() + "\n\n" + continued.trim()
        }

        val coveredText = messagesToCompress.joinToString("\n\n") { it.summaryAsText(maxLength = 4000) }
        val promptSnapshot = template.prompt.applyPlaceholders(
            "content" to coveredText,
            "previous_summary" to previousSummaryText,
            "target_tokens" to targetTokens.toString(),
            "additional_context" to if (additionalPrompt.isNotBlank()) {
                "Additional instructions from user: $additionalPrompt"
            } else "",
            "locale" to Locale.getDefault().displayName
        )

        // 分片压缩：超长覆盖区先分片并发压，再把各片结果合并成一条终稿。
        // 关键点（原实现有两个 bug）：
        // 1. previous_summary 只能给「第一片」（或终稿），每片都塞会让上一条总结被重复计入 N 次；
        // 2. 多片结果不能直接 join —— 每片首行都是标题，join 后标题只取到第一片的，
        //    其余片的标题混进正文。分片时必须再走一次合并压缩产出唯一的「标题+正文」。
        val chunks = splitMessages(messagesToCompress)
        val summaryText = if (chunks.size <= 1) {
            compressMessages(
                contentToCompress = chunks.firstOrNull()
                    ?.joinToString("\n\n") { it.summaryAsText(maxLength = 4000) }
                    ?: coveredText,
                previousSummary = previousSummaryText,
            ).trim()
        } else {
            val partials = coroutineScope {
                chunks.mapIndexed { index, chunk ->
                    async {
                        compressMessages(
                            contentToCompress = chunk.joinToString("\n\n") {
                                it.summaryAsText(maxLength = 4000)
                            },
                            // 只有第一片承接上一条总结，避免重复喂
                            previousSummary = if (index == 0) previousSummaryText else "",
                        )
                    }
                }.awaitAll()
            }
            // 终稿：把各片阶段性总结再压一次，保证输出仍是「首行标题 + 正文」
            compressMessages(
                contentToCompress = partials.mapIndexed { i, s ->
                    "[Part ${i + 1}/${partials.size}]\n$s"
                }.joinToString("\n\n"),
                previousSummary = previousSummaryText,
            ).trim()
        }
        if (summaryText.isBlank()) throw IllegalStateException("Failed to generate compressed summary")

        // 第一行 = 标题（≤40 字符，去 markdown 井号 / 列表符），其余 = 正文
        val lines = summaryText.lines()
        val rawTitle = lines.firstOrNull().orEmpty()
            .trim().trimStart('#', '*', '-', ' ').trim()
        val summaryTitle = rawTitle.take(40).ifBlank {
            context.getString(R.string.summary_default_title)
        }
        val summaryContent = lines.drop(1).joinToString("\n").trim()
            .ifEmpty { summaryText }

        val summaryMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(summaryContent)),
            summaryMeta = SummaryMeta(
                title = summaryTitle,
                boundaryMessageId = boundaryMessageId,
                summarizedCount = messagesToCompress.size,
                summarizedTokens = estimateCompressTokens(
                    messages = messagesToCompress,
                    // usage 基线：覆盖区之前的消息，用它们最后一次 promptTokens 做减法
                    prior = nodes.subList(0, coveredStart).map { it.currentMessage },
                ),
                modelId = model.id,
                templateId = template.id,
                reasoningEffort = template.reasoningEffort,
                prompt = promptSnapshot,
            ),
        )

        // 插入分界点之后；同分界点已有总结 → 作为该节点新版本（最新生成为生效版本）。
        // 注意：压缩要跑一次模型调用（可能几十秒），期间会话可能已经有新消息/新总结，
        // 必须基于**最新**会话落库并重新定位分界点，否则写回旧快照会吞掉这期间的消息。
        val latest = getConversationFlow(conversationId).value
        val latestNodes = latest.messageNodes
        val latestBoundaryIndex = latestNodes.indexOfFirst { node ->
            node.messages.any { it.id == boundaryMessageId }
        }
        if (latestBoundaryIndex == -1) throw IllegalStateException("Boundary message not found")
        val updatedNodes = latestNodes.toMutableList()
        val nextNode = latestNodes.getOrNull(latestBoundaryIndex + 1)
        if (nextNode?.currentMessage?.summaryMeta?.boundaryMessageId == boundaryMessageId) {
            updatedNodes[latestBoundaryIndex + 1] = nextNode.copy(
                messages = nextNode.messages + summaryMessage,
                selectIndex = nextNode.messages.size,
            )
        } else {
            updatedNodes.add(latestBoundaryIndex + 1, summaryMessage.toMessageNode())
        }
        saveConversation(
            conversationId,
            latest.copy(messageNodes = updatedNodes, chatSuggestions = emptyList()),
        )

            summaryMessage.summaryMeta!!
            }
        } finally {
            // 无论成功、API 异常还是协程取消，状态都不能卡在「总结中」。
            session.summaryStatus.value = null
        }
    }

    /**
     * 编辑总结消息（方案 2026-08-08 §6.3）：标题与正文均可改，总结元数据其余部分不变。
     * 仅允许编辑 summaryMeta != null 的消息。
     */
    suspend fun updateSummaryMessage(
        conversationId: Uuid,
        messageId: Uuid,
        newTitle: String,
        newContent: String,
    ) {
        if (newTitle.isBlank() && newContent.isBlank()) return
        val current = getConversationFlow(conversationId).value
        val updatedNodes = current.messageNodes.map { node ->
            if (node.messages.none { it.id == messageId }) {
                return@map node
            }
            node.copy(
                messages = node.messages.map { m ->
                    if (m.id == messageId && m.summaryMeta != null) {
                        m.copy(
                            parts = listOf(UIMessagePart.Text(newContent)),
                            summaryMeta = m.summaryMeta!!.copy(title = newTitle.trim()),
                        )
                    } else {
                        m
                    }
                }
            )
        }
        saveConversation(conversationId, current.copy(messageNodes = updatedNodes))
    }

    /**
     * 折叠被总结覆盖的消息（方案 2026-08-08 §3.6）：
     * 只把最新一条总结作为 user 消息注入，其分界点之前（含分界点）的原始消息全部跳过。
     *
     * 只作用于发送给模型的传输层列表，绝不写回会话存储。
     * 复用原总结消息 id 与内容 → 历史前缀逐轮字节级稳定，前缀缓存照旧命中。
     *
     * 注意：SYSTEM 消息与助手预设消息（presetMessages）永不折叠 ——
     * 它们是人设/few-shot 骨架，被压缩吃掉会直接改变对话风格。
     */
    private fun foldSummarizedMessages(
        messages: List<UIMessage>,
        presetMessageCount: Int = 0,
    ): List<UIMessage> {
        val lastSummaryIdx = messages.indexOfLast { it.summaryMeta != null }
        if (lastSummaryIdx < 0) return messages
        val meta = messages[lastSummaryIdx].summaryMeta ?: return messages
        val boundaryIdx = messages.indexOfFirst { it.id == meta.boundaryMessageId }
        if (boundaryIdx < 0 || boundaryIdx >= lastSummaryIdx) return messages
        val summary = messages[lastSummaryIdx]
        val summaryAsUser = summary.copy(
            parts = listOf(UIMessagePart.Text("[对话总结：${meta.title}]\n${summary.toText()}")),
            summaryMeta = null,
        )
        // 折叠区 = [preserveEnd, lastSummaryIdx)，其中 preserveEnd 之前是必须保留的骨架
        val preserveEnd = presetMessageCount.coerceIn(0, lastSummaryIdx)
        return buildList {
            // 1) 助手预设消息（人设/few-shot）原样保留
            addAll(messages.subList(0, preserveEnd))
            // 2) 折叠区里的 SYSTEM 消息仍要保留（它们是指令，不是对话内容）
            messages.subList(preserveEnd, lastSummaryIdx)
                .filter { it.role == MessageRole.SYSTEM }
                .let(::addAll)
            // 3) 总结本体 + 总结之后的原始消息
            add(summaryAsUser)
            addAll(messages.subList(lastSummaryIdx + 1, messages.size))
        }
    }

    private fun reasoningLevelFromEffort(effort: String): ReasoningLevel = when (effort.lowercase()) {
        "off" -> ReasoningLevel.OFF
        "on" -> ReasoningLevel.ON
        "auto" -> ReasoningLevel.AUTO
        "low" -> ReasoningLevel.LOW
        "medium" -> ReasoningLevel.MEDIUM
        "high" -> ReasoningLevel.HIGH
        "xhigh" -> ReasoningLevel.XHIGH
        "max" -> ReasoningLevel.MAX
        else -> ReasoningLevel.AUTO
    }

    /**
     * 被覆盖内容的 token 估算（分界线「共 y tokens」展示）。
     *
     * 旧实现是 `summaryAsText(4000).length / 4`，三重低估：
     * 1. summaryAsText 只取 Text part —— 工具调用的入参/返回体（常是最大头）、思考、附件全漏；
     * 2. 每条截断 4000 字符 —— 长消息被砍掉；
     * 3. `/4` 对中文严重偏低（CJK 约 0.7 token/字）。
     * 现在统一走 [estimateMessagesTokens]：真实 usage 优先（promptTokens 增量 + 输出），
     * 拿不到才退回按 part 全量遍历的字符估算。
     */
    private fun estimateCompressTokens(
        messages: List<UIMessage>,
        prior: List<UIMessage> = emptyList(),
    ): Long = estimateMessagesTokens(messages, prior)

    /**
     * 解析压缩模板：显式 templateId > 助手 defaultCompressTemplateId > 全局默认模板 > 内置通用模板。
     */
    fun resolveCompressTemplate(
        settings: Settings,
        assistant: Assistant?,
        templateId: Uuid?,
    ): CompressTemplate {
        val templates = settings.compressTemplates
        templateId?.let { id -> templates.firstOrNull { it.id == id }?.let { return it } }
        assistant?.defaultCompressTemplateId?.let { id -> templates.firstOrNull { it.id == id }?.let { return it } }
        settings.defaultCompressTemplateId?.let { id -> templates.firstOrNull { it.id == id }?.let { return it } }
        return templates.firstOrNull { it.builtin } ?: DEFAULT_COMPRESS_TEMPLATES.first()
    }

    /**
     * 自动压缩触发（方案 2026-08-08 §5.2）：
     * - 对话覆盖 > 助手默认（开关、模板、阈值、保留量**逐项**可覆盖，见 [mergeOverride]）；
     * - token 限制与条数限制 OR 触发，保留量取交集（保守）；
     * - 命中则以保留区之前的最后一条消息为分界点执行压缩（与手动压缩同一流水线）。
     */
    private suspend fun maybeAutoCompress(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant() ?: return
            // 生效配置 = 助手默认 + 本对话覆盖（聊天面板「自动压缩」里改的就是 override）
            val base = assistant.autoCompress.mergeOverride(conversation.autoCompressOverride)
            if (!base.enabled) return
            val countLimitOn = base.countLimitEnabled
            val tokenLimitOn = base.tokenLimitEnabled
            if (!countLimitOn && !tokenLimitOn) return

            val nodes = conversation.messageNodes
            // 生效区 = 最新总结节点及其之后。被折叠的历史原始消息永不删除，
            // 若按全量 messageNodes.size 判阈值，压缩后条数依然超标 → 每轮都再压一次（死循环刷 token）。
            val lastSummaryIdx = nodes.indexOfLast { it.currentMessage.summaryMeta != null }
            val effectiveStart = if (lastSummaryIdx >= 0) lastSummaryIdx else 0
            val effectiveCount = nodes.size - effectiveStart
            // 口径：优先真实 usage.promptTokens（最后一条 assistant 的上下文大小），
            // usage 缺失（中断/本地生成，约 4% 的对话）时由 estimateMessagesTokens 内部
            // 退回按 part 全量遍历的字符估算 —— 否则 token 线永远读到 0，
            // 只能靠 count 线兜底，用户设的 token 阈值形同虚设。
            val lastAssistantTokens = estimateMessagesTokens(
                nodes.subList(effectiveStart, nodes.size).map { it.currentMessage }
            )

            val countTrigger = countLimitOn && effectiveCount >= base.countThreshold
            val tokenTrigger = tokenLimitOn && lastAssistantTokens >= base.tokenThreshold.toLong()
            if (!countTrigger && !tokenTrigger) return

            val keepCount = if (countLimitOn) base.countKeep else Int.MAX_VALUE
            val keepTokens = if (tokenLimitOn) base.tokenKeep.toLong() else Long.MAX_VALUE
            val boundaryIndex = findSummaryBoundaryIndex(nodes, effectiveStart, keepCount, keepTokens)
            // 分界点必须严格落在生效区内部：等于 effectiveStart 说明只剩上一条总结自己，
            // 没有新内容可总结，再压一次就是拿同样的输入反复烧钱。
            if (boundaryIndex <= effectiveStart) return
            val boundaryMessageId = nodes[boundaryIndex].currentMessage.id

            val template = resolveCompressTemplate(settings, assistant, base.templateId)
            // 可观测性（2026-08-21）：这套东西曾经「静默失效」半个月没人发现，
            // 触发原因/水位必须留痕，否则下次又只能靠体感猜。
            Log.i(
                TAG,
                "autoCompress trigger conv=$conversationId reason=" +
                    (if (countTrigger) "count(${effectiveCount}/${base.countThreshold})" else "") +
                    (if (tokenTrigger) "token(${lastAssistantTokens}/${base.tokenThreshold})" else "") +
                    " boundary=$boundaryIndex template=${template.name} streaming=${template.streaming}"
            )
            summarizeConversation(conversationId, conversation, boundaryMessageId, template)
                .onFailure { e ->
                    addError(e, conversationId, title = context.getString(R.string.error_title_compress_conversation))
                }
        }.onFailure { e ->
            e.printStackTrace()
        }
    }

    /**
     * 从后往前找到保留区之前的最后一个节点 index（方案 2026-08-08 §5.2「保留量取交集」）：
     * 保留区节点数 ≤ [keepCount] 且累计 token ≤ [keepTokens]，两者同时满足。
     *
     * token 口径用**单条消息文本估算**，不能用 `usage.promptTokens`：
     * 后者是那一次请求的整个上下文大小（累计值），逐条相加会把上下文重复计算 N 遍，
     * 结果保留区被严重高估、分界点乱跳。
     *
     * 分界点会回退到最近一个「完整回合结尾」（assistant 消息且无未执行工具），
     * 避免把一次工具调用/一对问答劈成两半。返回 < [minIndex] 表示无可压缩内容。
     */
    private fun findSummaryBoundaryIndex(
        nodes: List<me.rerere.rikkahub.data.model.MessageNode>,
        minIndex: Int,
        keepCount: Int,
        keepTokens: Long,
    ): Int {
        var tokens = 0L
        var kept = 0
        var idx = nodes.lastIndex
        while (idx >= minIndex) {
            val message = nodes[idx].currentMessage
            // 这里必须用**单条纯字符估算**，不能走 estimateCompressTokens：
            // 后者 usage 优先，而单条 assistant 的 promptTokens 含全部历史，
            // prior 为空时 baseline=0 → 单条被算成整段上下文 → 保留量判定直接爆表，
            // 第一条就超 keepTokens，等于把保留区缩到 1 条。
            val t = message.estimateTokens()
            // 至少保留 1 条，否则 keepTokens 很小时会把全部消息压掉
            if (kept >= keepCount || (kept >= 1 && tokens + t > keepTokens)) break
            tokens += t
            kept++
            idx--
        }
        // 回退到完整回合结尾：分界点落在 user 消息或带未执行工具的消息上会切坏上下文
        while (idx > minIndex) {
            val message = nodes[idx].currentMessage
            val settled = message.role == MessageRole.ASSISTANT &&
                message.getTools().all { it.isExecuted }
            if (settled) break
            idx--
        }
        return idx
    }

    // ---- 对话状态更新 ----

    /**
     * GenerationHandler receives transport messages (asset resolved to file/url/data for providers),
     * but the conversation database must keep the original storage messages with asset:// refs.
     * Only assistant/tool messages created after the prompt history are allowed to come back from
     * the transport run.
     */
    private fun mergeTransportGenerationMessages(
        storageMessages: List<UIMessage>,
        transportMessages: List<UIMessage>,
        transportPrefixSize: Int = storageMessages.size,
    ): List<UIMessage> = buildList {
        // 传输层历史可能比存储短（对话压缩折叠了被总结的历史，方案 2026-08-08 §3.6）：
        // 前缀按传输层长度切，新生成的消息 = transportMessages 里超出该前缀的部分，
        // 再接到完整的 storageMessages 后面。用 storageMessages.size 切会把 assistant
        // 回复当成历史前缀丢掉（压缩后回复消失）。
        val prefix = transportPrefixSize.coerceIn(0, transportMessages.size)
        // A resumed tool round updates a Tool part inside an existing assistant message.
        // Keeping storageMessages verbatim here drops that update because transportMessages
        // usually has the same size as the prefix. Merge those existing-message changes back
        // while preserving storage-only asset:// and memory-injection fields.
        val storageById = storageMessages.associateBy { it.id }
        val mergedPrefix = transportMessages.take(prefix).mapIndexed { index, transportMessage ->
            val storageMessage = storageById[transportMessage.id]
                ?: storageMessages.getOrNull(index)
            if (storageMessage == null) {
                transportMessage
            } else {
                mergeTransportMessageIntoStorage(storageMessage, transportMessage)
            }
        }
        addAll(storageMessages.map { storageMessage ->
            mergedPrefix.firstOrNull { it.id == storageMessage.id } ?: storageMessage
        })
        if (transportMessages.size > prefix) {
            addAll(transportMessages.drop(prefix))
        }
    }

    /**
     * Merge an updated transport message without leaking transport-only fields.
     *
     * The transport message is authoritative for assistant text/reasoning and tool state:
     * after a resumed ask_user, the model's final reply is appended to the same assistant
     * message rather than creating a new message. Keeping only storageMessage.parts here
     * would therefore make tools look fixed while silently dropping the actual reply.
     * Only media parts are restored from storage, because those may have been rewritten to
     * provider URLs/data by the transport preparation step.
     */
    private fun mergeTransportMessageIntoStorage(
        storageMessage: UIMessage,
        transportMessage: UIMessage,
    ): UIMessage {
        // User/system history must remain byte-for-byte storage-safe; only assistant output
        // is updated by GenerationHandler during a generation round.
        if (storageMessage.role != MessageRole.ASSISTANT) return storageMessage

        val mergedParts = mergeTransportPartsPreservingStorageMedia(
            storageParts = storageMessage.parts,
            transportParts = transportMessage.parts,
        )

        return storageMessage.copy(
            parts = mergedParts,
            finishedAt = transportMessage.finishedAt ?: storageMessage.finishedAt,
            usage = transportMessage.usage ?: storageMessage.usage,
        )
    }

    private fun mergeTransportPartsPreservingStorageMedia(
        storageParts: List<UIMessagePart>,
        transportParts: List<UIMessagePart>,
    ): List<UIMessagePart> = transportParts.mapIndexed { index, transportPart ->
        val storagePart = storageParts.getOrNull(index)
        when (transportPart) {
            is UIMessagePart.Tool -> {
                val storageTool = storageParts
                    .filterIsInstance<UIMessagePart.Tool>()
                    .firstOrNull { it.toolCallId == transportPart.toolCallId }
                transportPart.copy(
                    output = mergeTransportPartsPreservingStorageMedia(
                        storageParts = storageTool?.output.orEmpty(),
                        transportParts = transportPart.output,
                    )
                )
            }

            is UIMessagePart.Image,
            is UIMessagePart.Video,
            is UIMessagePart.Audio,
            is UIMessagePart.Document ->
                storagePart?.takeIf { it::class == transportPart::class } ?: transportPart

            else -> transportPart
        }
    }

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    /**
     * AI 挂载/建图写回（阶段二 §2.6）：把绑定落到**当前对话**的 memoryGraphBindings，
     * 首次以助手当前值做种子物化（review2 §二.B），永不改助手配置。
     *
     * 需要 `allowConversationPromptInjection`：Resolver 只在该开关打开时才读会话绑定，
     * 否则写进去等于没写（下轮解析仍走助手绑定），直接报错让模型知道。
     * 语义为下一轮生效，不重注入当前轮。
     */
    private suspend fun onConversationGraphManage(
        assistant: Assistant,
        conversationId: Uuid,
        op: MemoryGraphManageOp,
    ): String? {
        if (!assistant.allowConversationPromptInjection) {
            return "conversation-level memory graph management requires enabling 'conversation prompt injection' for this assistant"
        }
        val session = getOrCreateSession(conversationId)
        val current = session.state.value
        val seed = assistant.memoryGraphBindings
        val base = current.memoryGraphBindings ?: seed
        val next = when (op) {
            is MemoryGraphManageOp.Attach ->
                (base.filter { it.graphId != op.graphId } +
                    MemoryGraphBinding(op.graphId, enabled = true, writable = op.writable))

            is MemoryGraphManageOp.Detach -> base.filter { it.graphId != op.graphId }
        }
        updateConversation(conversationId, current.copy(memoryGraphBindings = next))
        return null
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 把模型绑定到具体对话（而非助手/全局默认）。
     * 先改内存态让 UI 立刻响应，再异步落库；临时对话由 saveConversation 内部判断跳过写库。
     */
    fun setConversationModel(conversationId: Uuid, modelId: Uuid?) {
        updateConversationState(conversationId) { it.copy(modelId = modelId) }
        appScope.launch(Dispatchers.IO) {
            runCatching {
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            }
        }
    }

    /**
     * 对话级能力覆盖的统一写入口（2026-08-18 重构）。
     *
     * 与 [setConversationModel] 同构：先改内存态让 UI 立刻响应，再异步落库
     * （落库后由 ConversationRepository 的 outbox 钩子自动进云同步队列，
     * 所以这些开关天然跨端同步，无需额外处理）。
     *
     * 调用方只传自己要改的那一项，其余保持不变；传 `null` 语义是「恢复继承助手默认」，
     * 因此不能用 nullable 参数区分「不改」与「改成 null」—— 统一用 lambda 形式。
     */
    fun updateConversationOverrides(conversationId: Uuid, update: (Conversation) -> Conversation) {
        updateConversationState(conversationId, update)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            }.onFailure { Log.w(TAG, "updateConversationOverrides save failed for $conversationId", it) }
        }
    }

    /**
     * 执行 tool_manage 的开关意图：把变更写入**对话级覆盖**（与 UI 里的开关同一条路），
     * 内存态即时更新 + 异步落库 + 进同步 outbox。下一轮生成按新的覆盖值解析工具，
     * 所以 tool_manage 返回里告诉模型「下一轮回复起生效」。
     *
     * 种子物化与 [me.rerere.rikkahub.ui.pages.chat.ChatVM.toggleLocalTool] 等保持同一套口径：
     * 首次改某一类工具时以「当前生效值」（对话覆盖 ?? 助手/配置默认）为基底增删，
     * 而不是从空集合起步，避免一开就把助手默认开着的其他工具关掉。
     */
    private suspend fun applyToolManageToggle(conversationId: Uuid, op: ToolManageOp) {
        if (op !is ToolManageOp.SetEnabled) return
        val current = getConversationFlow(conversationId).value
        val assistant = settingsStore.settingsFlow.value.assistants
            .firstOrNull { it.id == current.assistantId }
            ?: error("Assistant ${current.assistantId} not found for conversation $conversationId")

        // 监督期：对话级覆盖可能被 Gate 收紧（resolveMcpServers 等已收口），
        // 但 tool_manage 本身是个本地工具，监督期若还挂着就等于允许改工具。
        // 这里不再额外加锁——是否挂载 tool_manage 已由监督过滤器决定。

        val updated: Conversation = when (op.source) {
            ToolManageSource.LOCAL -> {
                val option = parseLocalTool(op.id)
                    ?: error("Unknown local tool option: ${op.id}")
                if (option == LocalToolOption.ToolManage) {
                    error("tool_manage cannot toggle itself.")
                }
                val base = current.effectiveLocalTools(assistant)
                // 与 ChatVM.toggleLocalTool 一致：开子代理隐含信箱；信箱合并 Inbox+Send。
                val addOptions = when {
                    op.enabled && option == LocalToolOption.Subagent ->
                        listOf(option, LocalToolOption.Inbox, LocalToolOption.Send)
                    option == LocalToolOption.Inbox ->
                        listOf(LocalToolOption.Inbox, LocalToolOption.Send)
                    else -> listOf(option)
                }
                val next = if (op.enabled) {
                    (base + addOptions).distinct()
                } else {
                    val remove = if (option == LocalToolOption.Inbox) {
                        listOf(LocalToolOption.Inbox, LocalToolOption.Send)
                    } else listOf(option)
                    base - remove.toSet()
                }
                current.copy(localTools = next)
            }

            ToolManageSource.WORKSPACE -> {
                // workspace 工具默认开启表挂在「本对话生效的 workspace」上；
                // 挂载本身也是对话级覆盖（2026-08-22 下沉），读取顺序必须与 ChatService 一致：
                // 对话覆盖 > 运行时/agent map > 助手默认。WORKSPACE_ID_UNBOUND 哨兵规整为 null。
                val conversationWorkspaceId = current.workspaceId?.let { cid ->
                    if (cid == Conversation.WORKSPACE_ID_UNBOUND) null else cid.toString()
                }
                val workspaceId = conversationWorkspaceId
                    ?: workspaceIdByConversation[conversationId]
                    ?: assistant.workspaceId?.toString()
                val overrides = workspaceId
                    ?.let { runCatching { workspaceRepository.getById(it) }.getOrNull() }
                    ?.toolDefaultEnabledOverrides().orEmpty()
                val defaultEnabled = WorkspaceToolNames
                    .filter { resolveWorkspaceToolDefaultEnabled(it, overrides) }
                    .toSet()
                val base = current.workspaceTools ?: defaultEnabled
                val next = if (op.enabled) base + op.id else base - op.id
                current.copy(workspaceTools = next)
            }

            ToolManageSource.MCP -> {
                // key 格式 "serverId/toolName"。开工具时除了把 key 加进 mcpTools，
                // 还要确保所属 server 被挂载（mcpServers 并集）；关工具不动挂载。
                val separator = op.id.indexOf('/')
                val serverId = if (separator > 0) {
                    runCatching { Uuid.parse(op.id.substring(0, separator)) }.getOrNull()
                } else null
                val toolKey = op.id
                val baseMcpTools = current.mcpTools ?: settingsStore.settingsFlow.value.mcpServers
                    .filter { server -> server.id in current.effectiveMcpServers(assistant) }
                    .flatMap { server ->
                        server.commonOptions.tools.filter { t -> t.enable }
                            .map { "${server.id}/${it.name}" }
                    }
                    .toSet()
                val nextMcpTools = if (op.enabled) baseMcpTools + toolKey else baseMcpTools - toolKey
                val nextMcpServers = if (op.enabled && serverId != null) {
                    current.effectiveMcpServers(assistant) + serverId
                } else {
                    current.mcpServers
                }
                current.copy(mcpTools = nextMcpTools, mcpServers = nextMcpServers)
            }

            ToolManageSource.SKILL -> {
                val base = current.enabledSkills ?: assistant.enabledSkills
                val next = if (op.enabled) base + op.id else base - op.id
                current.copy(enabledSkills = next)
            }

            ToolManageSource.WEB -> {
                // 三态布尔：false/true 都要能显式表达，不能用 ?: 退化成「未设置」
                // （否则 AI 关闭联网后下一轮又继承助手默认值被悄悄打开）。
                current.copy(enableWebSearch = op.enabled)
            }
        }

        updateConversationState(conversationId) { updated }
        appScope.launch(Dispatchers.IO) {
            runCatching {
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            }.onFailure { Log.w(TAG, "tool_manage save failed for $conversationId", it) }
        }
    }
    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        // 受保护的定时任务不许移出它的文件夹（移走 = 从「监督」组里藏起来）
        scheduleProtectionGuard.blockReason(conversationId, ScheduleAction.MOVE)?.let { reason ->
            throw IllegalStateException(reason)
        }
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        // Asset refactor: conversation history is no longer the owner of local files.
        // Diffing file:// URIs here can mistake a storage asset:// -> transport file:// rewrite as
        // a user deletion and remove managed_files rows still referenced by messages. Keep file
        // lifetime under explicit attachment removal / file management instead.
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        // 临时聊天只更新内存状态，永不落库
        if (conversation.isTemporary) {
            updateConversation(conversationId, conversation.copy())
            return
        }
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy(updateAt = Instant.now())
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        // 受保护的定时任务：不许改历史（改消息 = 篡改查岗结论 / 伪造派活）
        if (rejectIfProtected(conversationId, ScheduleAction.EDIT_MESSAGE)) return
        launchLocalJob(
            errorHandler = { e -> addError(e, conversationId, title = context.getString(R.string.error_title_operation)) },
        ) {
            editMessageLocked(conversationId, messageId, parts)
        }.join()
    }

    private suspend fun editMessageLocked(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        // 受保护的定时任务：不许分叉（分支出去就能在副本里随便改，等于绕过全部保护）
        scheduleProtectionGuard.blockReason(conversationId, ScheduleAction.FORK)?.let { reason ->
            throw IllegalStateException(reason)
        }
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkTitle = currentConversation.title
            .takeIf { it.isNotBlank() }
            ?.let { "$it · 分支" } ?: ""
        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            title = forkTitle,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
            // 分支对话继承父对话的上下文归属：合集 folder、工作区 cwd、显式模型，
            // 避免新分支落到默认「聊天」合集
            folderId = currentConversation.folderId,
            workspaceCwd = currentConversation.workspaceCwd,
            modelId = currentConversation.modelId,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        // 受保护的定时任务：单条记录也不许删（查岗证据链完整性）
        scheduleProtectionGuard.blockReason(conversationId, ScheduleAction.DELETE_MESSAGE)?.let { reason ->
            throw IllegalStateException(reason)
        }
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        // 受保护的定时任务（监督查岗）不许用户手动掐断：被监督者不该拿着监工的电源开关。
        // 内部停止一律走 stopGenerationInternal（bridge 的 stop/archive/CALL 抢占用它）。
        if (rejectIfProtected(conversationId, ScheduleAction.CANCEL)) return
        stopGenerationInternal(conversationId)
    }

    /** 内部停止：绕过保护（会话轮换 / 归档 / CALL 抢占等系统动作）。 */
    suspend fun stopGenerationInternal(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val job = session.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
        val current = session.state.value
        val stopped = current.copy(
            messageNodes = current.messageNodes.mapIndexed { index, node ->
                if (index != current.messageNodes.lastIndex) {
                    node
                } else {
                    node.copy(
                        messages = node.messages.map { message ->
                            if (message.role == MessageRole.ASSISTANT) {
                                message.finishReasoning().copy(
                                    finishedAt = message.finishedAt ?: kotlin.time.Clock.System.now()
                                        .toLocalDateTime(TimeZone.currentSystemDefault())
                                )
                            } else {
                                message
                            }
                        }
                    )
                }
            },
            updateAt = Instant.now(),
        )
        saveConversation(conversationId, stopped)
    }
}
