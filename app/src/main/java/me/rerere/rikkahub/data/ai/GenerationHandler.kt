package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ToolCallingStrategy
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.AssetResolver
import me.rerere.rikkahub.data.files.AssetUri
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.tools.MEMORY_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.MemoryGraphManageOp
import me.rerere.rikkahub.data.ai.tools.MemoryToolGraph
import me.rerere.rikkahub.data.ai.tools.MemoryToolGraphInfo
import me.rerere.rikkahub.data.ai.tools.MemoryToolScope
import me.rerere.rikkahub.data.ai.tools.buildMemoryTool
import me.rerere.rikkahub.data.ai.memory.MemorySemanticSearch
import me.rerere.rikkahub.data.ai.memory.MemoryGraphSelector
import me.rerere.rikkahub.data.ai.memory.MemoryGraphBindingResolver
import me.rerere.rikkahub.data.ai.prompts.parseMemoryInjectionNodeIds
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryGraphCreator
import me.rerere.rikkahub.data.model.MemoryGraphData
import me.rerere.rikkahub.data.model.MemoryGraphLink
import me.rerere.rikkahub.data.model.MemoryGraphMatchEligibility
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.model.MemoryGraphSearchHit
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.model.ResolvedGraphBinding
import me.rerere.rikkahub.data.model.ScopedMemories
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.common.android.MemoryGraphDebugLog
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
// 含历史工具名: 旧会话里的 assistant_memory_tool / global_memory_tool 仍需被识别为记忆工具
private val memoryToolNames = setOf(MEMORY_TOOL_NAME, "assistant_memory_tool", "global_memory_tool")

/** 注入块子图解析已收拢到 parseMemoryInjectionNodeIds（新纯文本行式 + 旧 JSON 双格式兼容）。 */

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val graphRepo: MemoryGraphRepository,
    private val assetResolver: AssetResolver,
    private val semanticSearch: MemorySemanticSearch,
    private val selector: MemoryGraphSelector,
    private val registry: MemoryGraphRegistry,
    private val bindingResolver: MemoryGraphBindingResolver,
) {    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: ScopedMemories = ScopedMemories.Empty,
        memoryOptions: MemoryOptions = MemoryOptions(),
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
        /** 本轮记忆图绑定（由 ChatService 经 MemoryGraphBindingResolver 解析后下传）；null 时本方法自行解析 */
        graphBindings: List<ResolvedGraphBinding>? = null,
        /** 允许 AI 自管理记忆图（list_graphs/create_graph/attach_graph 的暴露开关，对应 `allowManageMemoryGraphs`） */
        graphManageEnabled: Boolean = false,
        /**
         * AI 挂载/建图后写回当前会话绑定的通道（ChatService 注入）。
         * 返回 null = 成功；非 null = 失败原因（如未开对话级注入开关）。null = 无写回通道（如 SubagentRunner）。
         */
        onGraphManage: (suspend (MemoryGraphManageOp) -> String?)? = null,
        /**
         * 优雅停轮信号（ChatService 注入的会话级标记）：置 true 时在当前 step 的工具执行
         * 结束（结果已合并/emit）后退出循环，不再发起下一轮模型调用，流程正常 onSuccess 收尾。
         *
         * 用于子 agent 回报/反问收尾——替代在生成协程内部 job.cancel()：从内部取消会把
         * 正在执行工具的结果合并一起掐掉，工具卡在「未执行」，随后被 sendMessage 的兜底
         * 误标成 "Generation cancelled by user"（2026-08-13 用户反馈）。null = 不启用（如 SubagentRunner）。
         */
        stopAfterCurrentStep: StateFlow<Boolean>? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        // 本轮生效的记忆图绑定（Resolver 是唯一真源）。
        // writableToolGraphs 必须是**可变内存集合**：tool 在 step 循环体内逐 step 重建，但
        // assistant/conversation 是捕获的入参，AI 挂载新图后重建 tool 仍读到陈旧对象 →
        // 新图进不了可写集合 → 建完图立刻被拒（review2 §一.2）。阶段二的 attach_graph
        // 会同时写库 + 更新这个集合，execute 的鉴权只读它。
        val resolvedGraphBindings = runCatching {
            graphBindings ?: bindingResolver.resolve(
                assistant = assistant,
                conversation = null,
                options = memoryOptions,
                maxEnabledGraphs = settings.memorySearch.sanitized().maxEnabledGraphs,
            )
        }.getOrDefault(emptyList())
        val writableToolGraphs = resolvedGraphBindings
            .filter { it.writable }
            .map { MemoryToolGraph(id = it.meta.id, slug = it.meta.wireId, name = it.meta.name) }
            .toMutableList()
        // 本轮已挂载（enabled）的图 id 集合：attach/create 后同步更新，供 list_graphs 与鉴权实时读（review2 §一.2）
        val attachedGraphIds = resolvedGraphBindings.filter { it.enabled }.map { it.meta.id }.toMutableSet()
        // 本轮生效的记忆选项（hoist：与 step 无关，避免每步重复 effective；memoryOptions 是捕获入参不可变）
        val effectiveMemoryOptions = memoryOptions.effective(assistant)
        // graph 侧按需裁剪（2026-08-12 用户需求）：编辑总闸 allowEditMemoryGraph 关掉时 graph 面全不暴露；
        // list_graphs 只在「允许管理」或「编辑总闸开且有绑定图」时给（总闸开但暂无 writable 图时 AI 也能先查看）
        val graphListEnabled = graphManageEnabled ||
            (effectiveMemoryOptions.allowEditMemoryGraph && resolvedGraphBindings.isNotEmpty())

        for (stepIndex in 0 until maxSteps) {
            // 上一轮工具执行已请求停轮（如 agent_report 回报完成）→ 优雅退出，不再发起模型调用。
            // 此时工具结果已合并并 emit 过，流程走正常 onSuccess 落库，不会残留「未执行」工具。
            if (stopAfterCurrentStep?.value == true) break
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (model.toolCallingStrategy != ToolCallingStrategy.OFF) {
                    val editableScopes = buildList<MemoryToolScope> {
                        if (effectiveMemoryOptions.allowEditAssistantMemory) add(MemoryToolScope.ASSISTANT)
                        if (effectiveMemoryOptions.allowEditGlobalMemory) add(MemoryToolScope.GLOBAL)
                    }
                    fun scopeId(scope: MemoryToolScope) = when (scope) {
                        MemoryToolScope.ASSISTANT -> assistant.id.toString()
                        MemoryToolScope.GLOBAL -> MemoryRepository.GLOBAL_MEMORY_ID
                    }
                    buildMemoryTool(
                        scopes = editableScopes,
                        onCreation = { scope, content ->
                            memoryRepo.addMemory(scopeId(scope), content)
                        },
                        onUpdate = { scope, id, content ->
                            memoryRepo.updateContentInScope(scopeId(scope), id, content)
                        },
                        onDelete = { scope, id ->
                            memoryRepo.deleteMemoryInScope(scopeId(scope), id)
                        },
                        onLink = { scope, sourceId, targetId, type, weight, description ->
                            memoryRepo.linkMemories(scopeId(scope), sourceId, targetId, type, weight, description)
                        },
                        onQueryLinks = { scope, memoryId ->
                            memoryRepo.queryMemoryLinks(scopeId(scope), memoryId)
                        },
                        onUnlink = { scope, linkId ->
                            memoryRepo.unlink(scopeId(scope), linkId)
                        },
                        // 实时读取可写图集合：AI 中途挂载的新图必须立刻可写（review2 §一.2）
                        graphsProvider = { writableToolGraphs.toList() },
                        graphResolve = { ref ->
                            runCatching { registry.resolve(ref, assistant.id.toString()) }
                                .getOrNull()
                                ?.let { MemoryToolGraph(id = it.id, slug = it.wireId, name = it.name) }
                        },
                        graphOnCreate = { graphId, title, content, matchEligibility ->
                            graphRepo.createNode(
                                graphId,
                                title,
                                content,
                                matchEligibility = matchEligibility ?: MemoryGraphMatchEligibility.ALWAYS,
                            )
                        },
                        graphOnUpdate = { graphId, id, title, content, matchEligibility ->
                            graphRepo.updateNode(
                                graphId,
                                id,
                                title = title.ifBlank { null },
                                content = content.ifBlank { null },
                                matchEligibility = matchEligibility,
                            )
                        },
                        graphOnDelete = { graphId, id ->
                            graphRepo.deleteNode(graphId, id)
                        },
                        graphOnLink = { graphId, sourceId, targetId, type, weight, description ->
                            graphRepo.linkNodes(graphId, sourceId, targetId, type, weight, description)
                        },
                        graphOnQueryLinks = { graphId, nodeId ->
                            if (nodeId == null) graphRepo.getLinks(graphId)
                            else graphRepo.getLinksOfNode(graphId, nodeId)
                        },
                        graphOnUnlink = { graphId, linkId ->
                            graphRepo.deleteLink(graphId, linkId)
                        },
                        graphOnUpdateLink = { graphId, linkId, type, weight, description ->
                            graphRepo.updateLink(
                                scope = graphId,
                                id = linkId,
                                type = type,
                                weight = weight,
                                description = description,
                            )
                        },
                        graphOnQueryNodes = { graphId, query, limit ->
                            // 有 query 走门控关键词打分检索，无 query 列出该图全部节点（均受 limit 约束）。
                            if (query.isNullOrBlank()) {
                                graphRepo.getNodes(graphId).take(limit)
                            } else {
                                // 门控池：第一轮只扫常驻池，命中集解锁 gated（邻居激活制 + 强命中直通），
                                // 再对解锁集跑一轮轻量匹配，两段合并去重。阈值用检索档（与注入档独立可调）。
                                val searchSettings = settings.memorySearch.sanitized()
                                val alwaysIds = runCatching { graphRepo.getAlwaysEligibleNodeIds(graphId) }
                                    .getOrDefault(emptySet())
                                val baseHits = runCatching {
                                    graphRepo.searchNodes(query, graphId, limit, eligibleNodeIds = alwaysIds)
                                }.getOrDefault(emptyList())
                                val unlockedIds = runCatching {
                                    graphRepo.getUnlockedGatedNodeIds(
                                        graphId,
                                        baseHits.map { it.node.id },
                                        searchSettings.gatedUnlockSearchThreshold,
                                    ) + graphRepo.getStrongMatchGatedNodeIds(graphId, query)
                                }.getOrDefault(emptySet())
                                val unlockedHits = if (unlockedIds.isEmpty()) {
                                    emptyList()
                                } else {
                                    runCatching {
                                        graphRepo.scoreNodesByQuery(query, graphId, unlockedIds, topK = limit)
                                    }.getOrDefault(emptyList())
                                }
                                val baseById = baseHits.associateBy { it.node.id }
                                val unlockedById = unlockedHits.associateBy { it.node.id }
                                (baseHits.map { it.node.id } + unlockedHits.map { it.node.id })
                                    .distinct()
                                    .mapNotNull { id -> baseById[id]?.node ?: unlockedById[id]?.node }
                                    .take(limit)
                            }
                        },
                        // ---- 阶段二：AI 自管理（list_graphs / create_graph / attach_graph）----
                        graphListEnabled = graphListEnabled,
                        graphEditEnabled = effectiveMemoryOptions.allowEditMemoryGraph,
                        graphManageEnabled = graphManageEnabled,
                        graphOnListGraphs = {
                            val metas = runCatching { registry.list() }.getOrDefault(emptyList())
                            val counts = runCatching { registry.nodeCounts() }.getOrDefault(emptyMap())
                            val all = metas.map { meta ->
                                MemoryToolGraphInfo(
                                    id = meta.id,
                                    slug = meta.wireId,
                                    name = meta.name,
                                    description = meta.description,
                                    nodeCount = counts[meta.id] ?: 0,
                                    attached = meta.id in attachedGraphIds,
                                    writable = writableToolGraphs.any { it.id == meta.id },
                                )
                            }
                            // 未开 allowManageMemoryGraphs 只列已挂载图（review2 §二.E）
                            if (graphManageEnabled) all else all.filter { it.attached }
                        },
                        graphOnCreateGraph = { name, description, emoji ->
                            runCatching {
                                val meta = registry.create(
                                    name = name,
                                    description = description,
                                    emoji = emoji,
                                    createdBy = MemoryGraphCreator.AI,
                                )
                                attachedGraphIds += meta.id
                                writableToolGraphs += MemoryToolGraph(id = meta.id, slug = meta.wireId, name = meta.name)
                                val attachError = onGraphManage?.invoke(MemoryGraphManageOp.Attach(meta.id, writable = true))
                                if (attachError != null) {
                                    MemoryGraphDebugLog.w(
                                        TAG,
                                        "create_graph: graph ${meta.slug} created but attach failed: $attachError"
                                    )
                                }
                                MemoryToolGraph(id = meta.id, slug = meta.wireId, name = meta.name)
                            }.getOrNull()
                        },
                        graphOnAttachGraph = { graphId, writable, detach ->
                            if (detach) {
                                attachedGraphIds -= graphId
                                writableToolGraphs.removeAll { it.id == graphId }
                            } else {
                                attachedGraphIds += graphId
                                if (writable && writableToolGraphs.none { it.id == graphId }) {
                                    val meta = runCatching { registry.get(graphId) }.getOrNull()
                                    if (meta != null) {
                                        writableToolGraphs += MemoryToolGraph(id = meta.id, slug = meta.wireId, name = meta.name)
                                    }
                                }
                            }
                            onGraphManage?.invoke(if (detach) MemoryGraphManageOp.Detach(graphId) else MemoryGraphManageOp.Attach(graphId, writable))
                        },
                    ).let(this::addAll)
                    addAll(tools)
                }
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories,
                    memoryOptions = memoryOptions.effective(assistant),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    conversationId = conversationId,
                    graphBindings = resolvedGraphBindings,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: if (tool.toolName in memoryToolNames) {
                                    executedTools += tool.copy(
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(
                                                    buildJsonObject {
                                                        put(
                                                            "error",
                                                            JsonPrimitive("Memory editing is disabled by the user. Do not edit memory.")
                                                        )
                                                    }
                                                )
                                            )
                                        )
                                    )
                                    return@runCatching
                                } else {
                                    executedTools += tool.copy(
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(
                                                    buildJsonObject {
                                                        put(
                                                            "error",
                                                            JsonPrimitive("The tool is unavailable; it is currently disabled by the user.")
                                                        )
                                                    }
                                                )
                                            )
                                        )
                                    )
                                    return@runCatching
                                }
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool.toolCallId, result, hasShellAccess)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(buildString {
                                                        append("[${it.javaClass.name}] ${it.message}")
                                                        summarizeCause(it)?.let { cause ->
                                                            append("\ncaused by $cause")
                                                        }
                                                        summarizeStackTrace(it)?.let { frames ->
                                                            append("\nat $frames")
                                                        }
                                                    })
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            val finalizedTools = executedTools.withReadFileOcrIfNeeded(model)

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    finalizedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: ScopedMemories,
        memoryOptions: MemoryOptions,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        conversationId: Uuid? = null,
        graphBindings: List<ResolvedGraphBinding>? = null,
    ) {
        // 工具产出的媒体一律留在它自己的 tool output 原位, 不再抽出来拼到上下文末尾:
        // 只有位置逐轮不变, 历史前缀才能字节级一致, 前缀缓存才可能命中。
        // 传输层各 provider 会把 tool 媒体降级成「紧跟该组 tool 结果之后的一条 user 消息」,
        // 位置仍然钉在原位, 且对纯文本模型自动退化为占位文本。
        val modelHistoryMessages = messages.sanitizeToolMediaForModel(model)
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                if (assistant.enableConversationIdInjection && conversationId != null) {
                    appendLine()
                    appendLine()
                    appendLine("**Conversation Info**")
                    append("Current Conversation ID: $conversationId")
                }

                // 传统记忆（legacy）：全量注入 system prompt，独立于记忆图开关，保持旧版行为。
                // 记忆图开启与否都不影响这里的 legacy 注入（双轨并行，方案 2026-08-05）。
                if (memoryOptions.referencesLegacyAny() && !memories.isEmpty()) {
                    appendLine()
                    append(
                        buildMemoryPrompt(
                            assistantMemories = if (memoryOptions.referenceAssistantMemory) memories.assistant else emptyList(),
                            globalMemories = if (memoryOptions.referenceGlobalMemory) memories.global else emptyList(),
                            groupByScope = memoryOptions.referenceAssistantMemory && memoryOptions.referenceGlobalMemory,
                            wrapInMemoryBlock = false,
                        )
                    )
                }

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(modelHistoryMessages.limitContext(assistant.contextMessageSize))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        // ---- 记忆注入 ----
        // 传统记忆已在上面 system 中全量注入（legacy 链路，不受记忆图开关影响）。
        // 记忆图（独立链路）：开启时从独立图谱仓库检索，存进最后一条真实 USER 消息的
        // memoryInjection 字段（不写 Text part：气泡/编辑/复制天然看不到），发请求前才展开。
        // ChatService 生成前已固化（latest user 已带 memoryInjection）时不重算，
        // 保证历史前缀逐轮字节级一致 → 前缀缓存命中；直接调用 generateText（无预注入）时在此兜底注入请求。
        // 检索失败 / 图谱为空 → 不注入，绞不回退到传统记忆（双轨互不干扰，方案 2026-08-05）。
        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
        val lastUserHasMemoryInjection = lastUserMessage?.memoryInjection?.isNotBlank() == true
        // 该 user 消息上一轮已尝试过检索（哪怕结果为空）：重 roll / 续跑不重跑检索——
        // 消息没变，检索结果不该变，避免每次重 roll 都白调一次注入选择器。
        val lastUserRetrievalAttempted = lastUserMessage?.memoryInjectionAttempted == true
        // 有 Resolver 输出时，它才是多图链路的闸门；不能再用 legacy 的两个布尔字段判断，
        // 否则自定义图已绑定但旧字段为 false 时，ChatService 虽传了图列表仍会被这里静默跳过。
        val effectiveGraphOptions = memoryOptions.effective(assistant)
        val graphReferenceEnabled = graphBindings?.any { it.enabled }
            ?: effectiveGraphOptions.referencesGraphAny()
        val graphMemoryInjection = if (graphReferenceEnabled && !lastUserHasMemoryInjection && !lastUserRetrievalAttempted) {
            val effOpts = effectiveGraphOptions
            val sanitizedSearch = settings.memorySearch.sanitized()
            val query = graphQuery(settings, messages)
            MemoryGraphDebugLog.i(
                TAG,
                "graph inject gate: graph-reference-enabled assistantId=${assistant.id} query=\"${query.take(120)}\" " +
                    "refAssistantGraph=${effOpts.referenceAssistantGraph} refGlobalGraph=${effOpts.referenceGlobalGraph} " +
                    "boundGraphs=${graphBindings?.count { it.enabled } ?: 0} " +
                    "semanticSearch=${effOpts.semanticSearch} graphExpansion=${effOpts.graphExpansion} " +
                    "recentTurns=${sanitizedSearch.queryRecentTurns}"
            )
            runCatching {
                retrieveGraphMemories(
                    query = query,
                    assistantId = assistant.id.toString(),
                    memoryOptions = effOpts,
                    settings = settings,
                    excludedNodeIds = injectedGraphNodeIds(messages),
                    includeHeader = !hasEarlierGraphInjection(messages),
                    graphs = graphBindings?.filter { it.enabled }?.map { it.meta },
                )
            }.onFailure {
                MemoryGraphDebugLog.e(TAG, "retrieveGraphMemories failed", it)
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.also {
                    MemoryGraphDebugLog.i(TAG, "graph inject block chars=${it.length}")
                }
        } else null
        if (graphReferenceEnabled && graphMemoryInjection == null && !lastUserHasMemoryInjection && !lastUserRetrievalAttempted) {
            MemoryGraphDebugLog.w(TAG, "graph inject EMPTY: assistantId=${assistant.id}")
        }
        // 注入块只在传输层展开：先存字段，再用 withMemoryInjection() 展成 Text part。
        // 历史消息里已固化的字段同样需要展开，否则模型看不到旧轮记忆。
        val internalMessagesWithField = if (graphMemoryInjection != null) {
            val lastUserIndex = internalMessages.indexOfLast { it.role == MessageRole.USER }
            if (lastUserIndex >= 0) {
                internalMessages.toMutableList().apply {
                    set(lastUserIndex, this[lastUserIndex].copy(memoryInjection = graphMemoryInjection))
                }
            } else {
                internalMessages
            }
        } else {
            internalMessages
        }
        val internalMessagesFinal = internalMessagesWithField.map { it.withMemoryInjection() }

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = if (model.toolCallingStrategy == ToolCallingStrategy.NATIVE) tools else emptyList(),
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            try {
                providerImpl.streamText(
                    providerSetting = provider,
                    messages = internalMessagesFinal,
                    params = params
                ).collect {
                    messages = messages.handleMessageChunk(chunk = it, model = model)
                    it.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(usage = message.usage.merge(usage))
                            } else {
                                message
                            }
                        }
                    }
                    onUpdateMessages(messages)
                }
            } finally {
                // 重试耗尽后命中 closeOnCodes（默认 401/403/422）的 Token 已关闭，同步为禁用状态。
                syncClosedProviderKeys(provider)
            }
        } else {
            try {
                val chunk = providerImpl.generateText(
                    providerSetting = provider,
                    messages = internalMessagesFinal,
                    params = params,
                )
                messages = messages.handleMessageChunk(chunk = chunk, model = model)
                chunk.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(
                                usage = message.usage.merge(usage)
                            )
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            } finally {
                syncClosedProviderKeys(provider)
            }
        }
    }

    /**
     * 记忆图检索用的对话上下文：
     * - 注入选择器开启时用 [MemoryInjectSettings] 的轮数/字数（它要读的是"对话"，通常要比检索 query 长）；
     * - 未开启时沿用 [MemorySearchSettings] 的旧参数，行为与之前逐字节一致。
     */
    private fun graphQuery(settings: Settings, messages: List<UIMessage>): String {
        val inject = settings.memoryInject.sanitized()
        return if (inject.enabled) {
            latestUserQuery(
                messages = messages,
                maxChars = inject.contextMaxChars,
                recentTurns = inject.recentTurns,
            )
        } else {
            val search = settings.memorySearch.sanitized()
            latestUserQuery(
                messages = messages,
                maxChars = search.queryMaxChars,
                recentTurns = search.queryRecentTurns,
            )
        }
    }

    /**
     * 提取最近 [recentTurns] 轮用户/助手消息的纯文本作为记忆检索 query；
     * 工具结果/媒体忽略，总长度由设置控制。
     *
     * - recentTurns = 1 且最后一条是用户消息 → 返回纯用户文本（兼容旧行为）；
     * - 最后一条是助手回复时，该回复一并带上（"user: 问 / assistant: 答"），
     *   让语义/关键词召回拿到更充分的上下文；
     * - recentTurns > 1 → 取最近 N 轮按时间正序拼接。
     *
     * 预算分配：最后一条 user 消息优先拿满（保证最新提问一定进 query），
     * 其余段按「最新优先」顺序吃剩余预算；旧段超预算直接丢弃。
     * 旧实现是时间正序拼接后整体 take(maxChars)，长 assistant 回复会把预算吃光、
     * 最新那句提问反而被截掉（多轮开关开得越大检索越差）。
     */
    private fun latestUserQuery(messages: List<UIMessage>, maxChars: Int, recentTurns: Int): String {
        val turns = recentTurns.coerceAtLeast(1)
        // 从后往前收集（newestFirst[0] 是最新一条），仅用户/助手文本消息，注入块剥离
        val newestFirst = mutableListOf<Pair<String, String>>() // role, text
        var collectedUsers = 0
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role != MessageRole.USER && msg.role != MessageRole.ASSISTANT) continue
            val text = msg.parts
                .mapNotNull { part -> (part as? UIMessagePart.Text)?.text }
                .joinToString("\n")
                // 注入块已不在 parts 里（进了 memoryInjection 字段），无需再剥离
                .trim()
            if (text.isBlank()) continue
            newestFirst.add(if (msg.role == MessageRole.USER) "user" to text else "assistant" to text)
            if (msg.role == MessageRole.USER) {
                collectedUsers++
                if (collectedUsers >= turns) break
            }
        }
        if (newestFirst.isEmpty()) return ""
        val budget = maxChars.coerceAtLeast(20)

        // 预算分配：最后一条 user 优先拿满，其余段最新优先，旧段超预算丢弃
        val lastUser = newestFirst.firstOrNull { it.first == "user" }
        val allocations = LinkedHashMap<Pair<String, String>, Int>()
        var remaining = budget
        if (lastUser != null) {
            val n = minOf(lastUser.second.length, remaining)
            allocations[lastUser] = n
            remaining -= n
        }
        for (seg in newestFirst) {
            if (remaining <= 0) break
            if (seg in allocations) continue
            val n = minOf(seg.second.length, remaining)
            allocations[seg] = n
            remaining -= n
        }

        // 输出按时间正序（最旧在前），语义上下文连贯
        val ordered = newestFirst.reversed().mapNotNull { seg ->
            val n = allocations[seg] ?: return@mapNotNull null
            val kept = seg.second.take(n)
            if (kept.isBlank()) null else seg.first to kept
        }
        return if (ordered.size == 1 && ordered.first().first == "user") {
            ordered.first().second // 单条用户消息：不带角色前缀，与旧行为一致
        } else {
            ordered.joinToString("\n") { (role, text) -> "$role: $text" }
        }
    }

    /**
     * 记忆图注入固化（对齐日期模式的稳定注入位）：供 ChatService 在生成前调用。
     *
     * 把记忆图注入块写入最新一条 user 消息的 memoryInjection 字段并随会话落库，
     * 使历史前缀逐轮字节稳定（前缀缓存才能命中），同时不污染用户正文。规则：
     * - 只处理「最新消息是 user」的正常发送；重新生成/工具续跑/审批等待不重注入，保持历史字节不变；
     * - 最新 user 消息已带注入字段（重试/续跑）时不重算，原样返回；
     * - 检索失败 / 图谱为空 → 不注入。
     */
    suspend fun injectGraphMemoryIfNeeded(
        settings: Settings,
        assistant: Assistant,
        messages: List<UIMessage>,
        memoryOptions: MemoryOptions,
        /** 本轮启用的记忆图（ChatService 经 Resolver 解析后传入）；null 时退化按老字段推导 */
        graphs: List<MemoryGraphMeta>? = null,
    ): List<UIMessage> {
        // 多图体系：Resolver 给了图列表就以它为准（空列表 = 本轮不注入）；
        // 没给则退化到老字段判断，对老配置行为等价。
        val graphGateOpen = if (graphs != null) {
            graphs.isNotEmpty() && !memoryOptions.graphMuted
        } else {
            !memoryOptions.graphMuted && memoryOptions.effective(assistant).referencesGraphAny()
        }
        if (!graphGateOpen || messages.isEmpty()) return messages
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0 || lastUserIndex != messages.lastIndex) return messages
        val lastUser = messages[lastUserIndex]
        // 已注入过、或已尝试过检索（消息未变的重 roll）→ 不重跑，见 UIMessage.memoryInjectionAttempted
        if (!lastUser.memoryInjection.isNullOrBlank() || lastUser.memoryInjectionAttempted) return messages
        val excludedNodeIds = injectedGraphNodeIds(messages)
        // 说明头一次会话只注入一次：历史里已有注入块时本轮只带数据行（省 ~66 token/轮）。
        val includeHeader = !hasEarlierGraphInjection(messages)
        MemoryGraphDebugLog.i(
            TAG,
            "injectGraphMemoryIfNeeded: excludedAlreadyInjected=${excludedNodeIds.size} " +
                "ids=${excludedNodeIds.take(30)} includeHeader=$includeHeader"
        )
        val block = runCatching {
            retrieveGraphMemories(
                query = graphQuery(settings, messages),
                assistantId = assistant.id.toString(),
                memoryOptions = memoryOptions.effective(assistant),
                settings = settings,
                excludedNodeIds = excludedNodeIds,
                includeHeader = includeHeader,
                graphs = graphs,
            )
        }.getOrDefault("")
        if (block.isBlank()) {
            MemoryGraphDebugLog.w(TAG, "injectGraphMemoryIfNeeded: EMPTY block, mark attempted and skip assistantId=${assistant.id}")
            // 检索为空也要标记「已尝试」：重 roll 时消息没变，不该再跑一遍检索（选择器 LLM 不被反复白调）
            return messages.toMutableList().apply {
                set(lastUserIndex, lastUser.copy(memoryInjectionAttempted = true))
            }
        }
        MemoryGraphDebugLog.i(TAG, "injectGraphMemoryIfNeeded: inject block chars=${block.length} into user msg idx=$lastUserIndex")
        return messages.toMutableList().apply {
            set(lastUserIndex, lastUser.copy(memoryInjection = block, memoryInjectionAttempted = true))
        }
    }

    /**
     * 收集会话历史里已注入过的图谱节点 id（跨轮去重：同一记忆只插入一次）。
     * 复用 [parseMemoryInjectionNodeIds]（新纯文本行式 + 旧 JSON 双格式兼容），
     * 避免注入格式改动时两处解析逻辑分叉。
     */
    private fun injectedGraphNodeIds(messages: List<UIMessage>): Set<Long> {
        val ids = mutableSetOf<Long>()
        messages.forEach { message ->
            val block = message.memoryInjection?.takeIf { it.isNotBlank() } ?: return@forEach
            parseMemoryInjectionNodeIds(block).values.forEach { ids.addAll(it) }
        }
        return ids
    }

    /**
     * 历史里是否已经出现过记忆图注入块（说明头已在上文）。
     * 用于让说明头一次会话只注入一次，后续轮次只带数据行（省 ~66 token/轮）。
     * 判定按"存在非空注入字段"而非"解析出节点"，避免旧格式解析失败时反复重复说明头。
     */
    private fun hasEarlierGraphInjection(messages: List<UIMessage>): Boolean =
        messages.any { it.memoryInjection?.contains("<memory_graph>") == true }

    /**
     * 记忆图检索注入（独立链路）：从独立图谱仓库按 query 检索 assistant/global 两个 scope，
     * 取命中节点 + N 跳邻居子图，输出 <memory_graph> 块。与 legacy 全量注入完全隔离（方案 2026-08-05）。
     * 召回条数/权重/跳数/注入上限全部来自 [MemorySearchSettings]，不再硬编码。
     */
    private suspend fun retrieveGraphMemories(
        query: String,
        assistantId: String,
        memoryOptions: MemoryOptions,
        settings: Settings,
        excludedNodeIds: Set<Long> = emptySet(),
        includeHeader: Boolean = true,
        /** 本轮参与检索的图（已解析 + 排序 + 截断）；为 null 时按老字段推导两张内置图 */
        graphs: List<MemoryGraphMeta>? = null,
    ): String {
        if (query.isBlank()) return ""
        val searchSettings = settings.memorySearch.sanitized()
        val topK = searchSettings.topK
        // 参与检索的图：多图体系下由 Resolver 给出；没给就退化成老的两张内置图（行为等价）
        val targetGraphs = graphs ?: runCatching {
            buildList {
                if (memoryOptions.referenceAssistantGraph) add(registry.ensureAssistantGraph(assistantId))
                if (memoryOptions.referenceGlobalGraph) add(registry.ensureGlobalGraph())
            }
        }.getOrDefault(emptyList())
        if (targetGraphs.isEmpty()) {
            MemoryGraphDebugLog.w(TAG, "retrieveGraphMemories: no graphs to search")
            return ""
        }
        // 注入选择器（方案 2026-08-06）：开启后用轻量 LLM 直接从整份目录挑 id，取代关键词/语义打分。
        // null = 未启用或调用/解析失败；非 null = 选择结果（可能为空数组，表示模型认为本轮无需注入）。
        val injectSettings = settings.memoryInject.sanitized()
        val selection = if (injectSettings.enabled) {
            runCatching {
                selector.select(
                    settings = settings,
                    graphs = targetGraphs,
                    conversation = query,
                    excludedNodeIds = excludedNodeIds,
                )
            }.onFailure { MemoryGraphDebugLog.e(TAG, "selector call failed", it) }.getOrNull()
        } else {
            null
        }
        if (injectSettings.enabled && selection == null) {
            if (!injectSettings.fallbackToKeywordOnFailure) {
                MemoryGraphDebugLog.w(TAG, "selector unavailable and fallback disabled → skip graph injection")
                return ""
            }
            MemoryGraphDebugLog.w(TAG, "selector unavailable → fallback to keyword/semantic recall")
        }
        suspend fun scopeGraph(scope: String): Pair<List<MemoryGraphNode>, List<MemoryGraphLink>> {
            // 选择器给了结果就直接当命中集（保持模型给出的顺序），否则走旧的关键词 + 语义打分。
            val selectedIds = selection?.idsFor(scope)
            if (selectedIds != null && selectedIds.isEmpty()) {
                MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope selector picked NOTHING, skip")
                return emptyList<MemoryGraphNode>() to emptyList()
            }
            // 门控池解锁（邻居激活制，取代早期 unlocks 边）：常驻池命中集作为激活集，
            // 解锁「单锚点被激活 / 激活邻居权重和达标」的 gated 节点，并对解锁集跑一轮轻量关键词匹配。
            // 只放行「解锁且与 query 相关」的节点 —— 解锁只是进入候选池，是否注入仍过匹配关。
            suspend fun unlockGate(baseIds: List<Long>): List<MemoryGraphSearchHit> {
                if (baseIds.isEmpty()) return emptyList()
                return runCatching {
                    val unlockedIds = graphRepo.getUnlockedGatedNodeIds(
                        scope,
                        baseIds,
                        searchSettings.gatedUnlockInjectThreshold,
                    ) + graphRepo.getStrongMatchGatedNodeIds(scope, query)
                    if (unlockedIds.isEmpty()) {
                        emptyList()
                    } else {
                        graphRepo.scoreNodesByQuery(query, scope, unlockedIds, topK = maxOf(3, topK / 2))
                    }
                }.getOrDefault(emptyList())
            }
            val hits: List<MemoryGraphSearchHit>
            val unlockedHits: List<MemoryGraphSearchHit>
            if (selectedIds != null) {
                val nodesById = runCatching { graphRepo.getNodesByIds(selectedIds) }.getOrDefault(emptyMap())
                hits = selectedIds.mapIndexedNotNull { index, id ->
                    nodesById[id]?.let { node ->
                        MemoryGraphSearchHit(node = node, score = (selectedIds.size - index).toFloat())
                    }
                }.also { picked ->
                    MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope selectorHits=${picked.size} " +
                        "titles=${picked.joinToString(",") { it.node.title.take(20) }}")
                }
                unlockedHits = unlockGate(hits.map { it.node.id })
            } else {
                // 匹配资格门第一段：只扫常驻池（gated 未解锁节点物理上不参与匹配）
                val alwaysIds = runCatching { graphRepo.getAlwaysEligibleNodeIds(scope) }
                    .getOrDefault(emptySet())
                val keywordHits = if (searchSettings.keywordSearch) {
                    runCatching { graphRepo.searchNodes(query, scope, topK, eligibleNodeIds = alwaysIds) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope keywordHits=${keywordHits.size} " +
                    "titles=${keywordHits.joinToString(",") { it.node.title.take(20) }}")
                val semanticHits = if (memoryOptions.semanticSearch || searchSettings.semanticSearch) {
                    runCatching { semanticSearch.search(settings, query, scope, topK, eligibleNodeIds = alwaysIds) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope semanticHits=${semanticHits.size} " +
                    "titles=${semanticHits.joinToString(",") { it.node.title.take(20) }}")
                unlockedHits = unlockGate(keywordHits.map { it.node.id } + semanticHits.map { it.node.id })
                if (unlockedHits.isNotEmpty()) {
                    MemoryGraphDebugLog.i(
                        TAG,
                        "scopeGraph: scope=$scope unlock gate opened ${unlockedHits.size} gated nodes " +
                            "titles=${unlockedHits.joinToString(",") { it.node.title.take(20) }}"
                    )
                }
                val keywordById = keywordHits.associateBy { it.node.id }
                val semanticById = semanticHits.associateBy { it.node.id }
                hits = (keywordHits.map { it.node.id } + semanticHits.map { it.node.id })
                    .distinct()
                    .mapNotNull { id ->
                        val node = semanticById[id]?.node ?: keywordById[id]?.node
                        if (node == null) {
                            null
                        } else {
                            val keywordScore = keywordById[id]?.score ?: 0f
                            val semanticScore = semanticById[id]?.score ?: 0f
                            MemoryGraphSearchHit(
                                node = node,
                                score = keywordScore * searchSettings.keywordWeight +
                                    semanticScore * searchSettings.semanticWeight,
                            )
                        }
                    }
                    .filter { it.score >= searchSettings.minScore }
                    .sortedByDescending { it.score }
                    .take(topK)
            }
            // 解锁节点并入命中集：只保留「解锁 + query 相关」的，权重低于直接命中
            val mergedHits = if (unlockedHits.isEmpty()) {
                hits
            } else {
                val unlockedById = unlockedHits.associateBy { it.node.id }
                (hits.map { it.node.id } + unlockedHits.map { it.node.id })
                    .distinct()
                    .mapNotNull { id ->
                        val hit = hits.firstOrNull { it.node.id == id }
                        val unlocked = unlockedById[id]
                        when {
                            hit != null -> hit
                            unlocked != null -> MemoryGraphSearchHit(node = unlocked.node, score = unlocked.score * 0.5f)
                            else -> null
                        }
                    }
                    .sortedByDescending { it.score }
                    .take(topK)
            }
            val finalHits = mergedHits
            if (finalHits.isEmpty()) {
                MemoryGraphDebugLog.w(TAG, "scopeGraph: scope=$scope merged hits EMPTY, " +
                    "fallbackToAllWhenEmpty=${searchSettings.fallbackToAllWhenEmpty}")
                if (!searchSettings.fallbackToAllWhenEmpty) return emptyList<MemoryGraphNode>() to emptyList()
                // 兜底全量也只给常驻池：锁池节点在无语境命中时不应泄漏进上下文
                val all = graphRepo.getGraph(scope)
                val selected = all.nodes.filter {
                    it.matchEligibility == MemoryGraphMatchEligibility.ALWAYS && it.id !in excludedNodeIds
                }.take(topK)
                val fallbackIds = selected.map { it.id }.toSet()
                MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope fallback selected=${selected.size} " +
                    "(excluded=${excludedNodeIds.size})")
                return selected to all.links.filter { link ->
                    link.sourceId in fallbackIds && link.targetId in fallbackIds
                }
            }
            MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope merged hits=${finalHits.size} " +
                "topTitles=${finalHits.take(10).joinToString(",") { it.node.title.take(20) + ":" + String.format(Locale.US, "%.2f", it.score) }}")
            if (!memoryOptions.graphExpansion && !searchSettings.graphExpansion) {
                val nodes = finalHits.map { it.node }.filter { it.id !in excludedNodeIds }
                if (nodes.isEmpty()) {
                    MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope no expansion, all hits already injected, skip")
                    return emptyList<MemoryGraphNode>() to emptyList()
                }
                val ids = nodes.map { it.id }.toSet()
                MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope no expansion, return nodes=${nodes.size} " +
                    "(excluded=${excludedNodeIds.size})")
                return nodes to graphRepo.getLinks(scope).filter { it.sourceId in ids && it.targetId in ids }
            }
            val graph = runCatching {
                graphRepo.getGraphForNodes(
                    scope = scope,
                    seedIds = finalHits.map { it.node.id },
                    maxHops = searchSettings.expansionHops,
                )
            }
                .getOrDefault(MemoryGraphData())
            val filteredNodes = graph.nodes.filter { it.id !in excludedNodeIds }
            val filteredIds = filteredNodes.map { it.id }.toSet()
            MemoryGraphDebugLog.i(TAG, "scopeGraph: scope=$scope with expansion hops=${searchSettings.expansionHops} " +
                "nodes=${filteredNodes.size} links=${graph.links.size} (excluded=${excludedNodeIds.size})")
            return filteredNodes to graph.links.filter { it.sourceId in filteredIds && it.targetId in filteredIds }
        }
        // 注入上限：所有图共享 maxInjectNodes 额度，按 sortOrder 顺序依次吃；
        // 单图另受 perGraphMaxNodes 约束，防止一张大图把其他图全挤掉。
        var budget = searchSettings.maxInjectNodes
        val blocks = mutableListOf<GraphInjectionBlock>()
        for (graph in targetGraphs) {
            if (budget <= 0) break
            val result = scopeGraph(graph.id)
            if (result.first.isEmpty()) continue
            val capped = result.capNodes(minOf(budget, searchSettings.perGraphMaxNodes))
            if (capped.first.isEmpty()) continue
            budget -= capped.first.size
            blocks += GraphInjectionBlock(
                wireId = graph.wireId,
                name = graph.name,
                nodes = capped.first,
                links = capped.second,
            )
        }
        MemoryGraphDebugLog.i(
            TAG,
            "retrieve done: graphs=${blocks.size} " +
                blocks.joinToString(" ") { "${it.wireId}(nodes=${it.nodes.size},links=${it.links.size})" } +
                " maxInjectNodes=${searchSettings.maxInjectNodes} perGraphMaxNodes=${searchSettings.perGraphMaxNodes}"
        )
        return buildGraphMemoryPrompt(
            graphs = blocks,
            contentMaxChars = searchSettings.nodeContentMaxChars,
            includeHeader = includeHeader,
        )
    }

    /** 按上限裁剪节点，并丢掉两端不再存在的边。 */
    private fun Pair<List<MemoryGraphNode>, List<MemoryGraphLink>>.capNodes(
        limit: Int,
    ): Pair<List<MemoryGraphNode>, List<MemoryGraphLink>> {
        if (limit <= 0) return emptyList<MemoryGraphNode>() to emptyList()
        if (first.size <= limit) return this
        val nodes = first.take(limit)
        val ids = nodes.map { it.id }.toSet()
        return nodes to second.filter { it.sourceId in ids && it.targetId in ids }
    }

    private suspend fun List<UIMessagePart.Tool>.withReadFileOcrIfNeeded(model: Model): List<UIMessagePart.Tool> {
        if (Modality.IMAGE in model.inputModalities) return this
        val localOnlyModel = model.copy(inputModalities = model.inputModalities - Modality.URL)
        return map { tool ->
            if (tool.toolName != "workspace_read_file") return@map tool
            val textIndex = tool.output.indexOfFirst { it is UIMessagePart.Text }
            val text = (tool.output.getOrNull(textIndex) as? UIMessagePart.Text)?.text ?: return@map tool
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@map tool
            if (obj["ocr"] != null) return@map tool
            val assetUri = obj["asset_uri"]?.jsonPrimitive?.contentOrNull?.takeIf { AssetUri.isAsset(it) } ?: return@map tool
            val image = assetResolver.resolvePartForModel(UIMessagePart.Image(assetUri), localOnlyModel) as? UIMessagePart.Image
                ?: return@map tool
            val ocr = OcrTransformer.performOcr(image)
            val updatedText = UIMessagePart.Text(
                json.encodeToString(
                    buildJsonObject {
                        obj.forEach { (key, value) -> put(key, value) }
                        put("ocr", ocr)
                    }
                )
            )
            tool.copy(output = tool.output.mapIndexed { index, part -> if (index == textIndex) updatedText else part })
        }
    }

    /**
     * 工具输出的矘身: 只缩水文本(去掉冗余元信息, 保留 asset id 供后续引用),
     * **不再剔除媒体 part** —— 媒体留在原位, 由传输层按模型能力自行降级:
     * - 支持图片的模型 -> 紧跟该组 tool 结果之后的 synthetic user 消息;
     * - 纯文本模型 -> 占位文本 / OCR。
     * 中途换模型也能自动适配, 且图的位置永不漂移。
     */
    private fun List<UIMessage>.sanitizeToolMediaForModel(model: Model): List<UIMessage> = map { message ->
        message.copy(parts = message.parts.map { it.sanitizeToolMediaForModel(model) })
    }

    private fun UIMessagePart.sanitizeToolMediaForModel(model: Model): UIMessagePart = when (this) {
        is UIMessagePart.Tool -> copy(
            output = output.map { part ->
                if (part is UIMessagePart.Text) {
                    part.distillToolTextForModel(toolName, model)
                } else {
                    part
                }
            }
        )
        else -> this
    }

    private fun UIMessagePart.Text.distillToolTextForModel(toolName: String, model: Model): UIMessagePart.Text {
        if (toolName != "workspace_read_file" && toolName != "image_generation") return this
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return this
        if (obj["asset_uri"] == null && obj["preview_asset_uri"] == null && obj["asset_ids"] == null) return this
        val ocr = obj["ocr"]?.jsonPrimitive?.contentOrNull
        val distilled = buildJsonObject {
            put("status", obj["status"] ?: JsonPrimitive("ok"))
            // 必须保留 asset id(s): 否则模型在后续轮彻底失去这张图的地址, 无法再引用或二次编辑。
            obj["asset_ids"]?.let { put("asset_ids", it) }
            val assetId = obj["asset_id"]?.jsonPrimitive?.contentOrNull
                ?: AssetUri.parse(obj["asset_uri"]?.jsonPrimitive?.contentOrNull)
            if (assetId != null) put("asset_id", assetId)
            // legacy: 老会话里存的 round tag 继续透传, 不破坏已有上下文。
            obj["tag"]?.let { put("tag", it) }
            if (toolName == "workspace_read_file") {
                // 按模型能力与实际情况说实话: 这句话曾被无条件写成「不支持图片, 已 OCR」,
                // 而视觉模型根本不会跑 OCR(withReadFileOcrIfNeeded 首行就 return),
                // 结果是主动误导模型「我看到的是图但工具说这是 OCR 文本」。
                val description = when {
                    Modality.IMAGE in model.inputModalities -> "Image attached in the following message"
                    !ocr.isNullOrBlank() -> "This model doesn't support images — OCR applied"
                    else -> "This model doesn't support images — image content unavailable"
                }
                put("description", description)
            }
            if (!ocr.isNullOrBlank()) {
                put("ocr", ocr)
            }
        }
        return UIMessagePart.Text(json.encodeToString(distilled))
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(AppPaths.filesDir(context), FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

private const val TOOL_ERROR_FRAME_LIMIT = 3
private const val TOOL_ERROR_FIELD_LIMIT = 300

/**
 * Root cause summary for a failed tool call, or null when there is no distinct cause.
 *
 * Only class name and message are kept: the model can act on "AccessDeniedException on /mnt/x",
 * but never on an R8-obfuscated frame list.
 */
private fun summarizeCause(throwable: Throwable): String? {
    val cause = generateSequence(throwable.cause) { it.cause }.lastOrNull() ?: return null
    if (cause === throwable) return null
    return "[${cause.javaClass.name}] ${cause.message}".take(TOOL_ERROR_FIELD_LIMIT)
}

/**
 * Compact frame hint: at most [TOOL_ERROR_FRAME_LIMIT] frames from this project's own packages,
 * so a tool failure costs a few tokens instead of a few hundred.
 * The full stack trace still goes to logcat via printStackTrace().
 */
private fun summarizeStackTrace(throwable: Throwable): String? =
    throwable.stackTrace
        .asSequence()
        .filter { it.className.startsWith("me.rerere.") }
        .take(TOOL_ERROR_FRAME_LIMIT)
        .map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" <- ")
