package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
import me.rerere.rikkahub.data.ai.tools.MemoryToolScope
import me.rerere.rikkahub.data.ai.tools.buildMemoryTool
import me.rerere.rikkahub.data.ai.memory.MemorySemanticSearch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.model.ScopedMemories
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
// 含历史工具名: 旧会话里的 assistant_memory_tool / global_memory_tool 仍需被识别为记忆工具
private val memoryToolNames = setOf(MEMORY_TOOL_NAME, "assistant_memory_tool", "global_memory_tool")

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
    private val assetResolver: AssetResolver,
    private val semanticSearch: MemorySemanticSearch,
) {
    /**
     * 会话级记忆去重（对齐 Operit shared.js buildMemorySnapshotId 快照机制）：
     * key = conversationId，记录本会话已注入的记忆 id；后续轮次检索时排除，
     * 避免多轮/工具循环中模型重复引用同一批记忆（Operit snapshot 语义：同 chat 不重复命中）。
     */
    private val injectedMemoryIdsByConversation = ConcurrentHashMap<String, MutableSet<Int>>()

    /** 防止去重集合无限膨胀：单会话记忆去重集合上限，超出后整体清空重建（个人记忆量级足够） */
    private fun injectedSet(conversationId: Uuid?): MutableSet<Int>? {
        val key = conversationId?.toString() ?: return null
        val set = injectedMemoryIdsByConversation.getOrPut(key) { ConcurrentHashMap.newKeySet() }
        if (set.size > 512) set.clear()
        return set
    }
    fun generateText(
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
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages
        val ephemeralToolUserMessages = mutableListOf<UIMessage>()

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (model.toolCallingStrategy != ToolCallingStrategy.OFF) {
                    val effectiveMemoryOptions = memoryOptions.effective(assistant)
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
                        }
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
                    ephemeralToolUserMessages = ephemeralToolUserMessages,
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
            finalizedTools.toEphemeralUserMessages(model).let { newUserMessages ->
                if (newUserMessages.isNotEmpty()) {
                    ephemeralToolUserMessages += newUserMessages
                }
            }

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
        ephemeralToolUserMessages: List<UIMessage> = emptyList(),
    ) {
        val historyEphemeralToolUserMessages = messages.extractHistoryEphemeralToolUserMessages(model)
        val combinedEphemeralToolUserMessages = (historyEphemeralToolUserMessages + ephemeralToolUserMessages).distinctBy { it.parts }
        val resolvedEphemeralToolUserMessages = combinedEphemeralToolUserMessages.resolveToolUserMessagesForModel(model)
        val modelHistoryMessages = messages.sanitizeToolMediaForModel()
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

                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) add(UIMessage.system(prompt = system))
            addAll(modelHistoryMessages.limitContext(assistant.contextMessageSize))
            addAll(resolvedEphemeralToolUserMessages)
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

        // ---- 记忆注入（§6.3 动态记忆改造，对齐 Operit appendExtraInfoToMessage）----
        // 记忆不再进 system：system + 历史保持字节级稳定，Claude/Gemini 前缀缓存才能命中。
        // 检索结果作为显式 <memory> 块追加到最后一条真实 USER 消息末尾（每轮现查现注、不落库）。
        val memoryInjection = if (memoryOptions.referencesAny() && !memories.isEmpty()) {
            runCatching {
                retrieveMemories(
                    query = latestUserQuery(messages),
                    assistantId = assistant.id.toString(),
                    memories = memories,
                    memoryOptions = memoryOptions,
                    settings = settings,
                    conversationId = conversationId,
                )
            }.getOrDefault(ScopedMemories.Empty).let { retrieved ->
                // 空结果回退全量仅当 fallbackToAllWhenEmpty 开启（默认关，Operit shared.js:789-802 空结果输出占位不装全量）
                val inject = if (retrieved.isEmpty() && settings.memorySearch.fallbackToAllWhenEmpty) memories else retrieved
                buildMemoryPrompt(
                    assistantMemories = if (memoryOptions.referenceAssistantMemory) inject.assistant else emptyList(),
                    globalMemories = if (memoryOptions.referenceGlobalMemory) inject.global else emptyList(),
                    groupByScope = memoryOptions.referenceAssistantMemory && memoryOptions.referenceGlobalMemory,
                ).takeIf { it.isNotBlank() }
            }
        } else null
        val internalMessagesFinal = memoryInjection?.let { block ->
            val lastUserIndex = internalMessages.indexOfLast { it.role == MessageRole.USER }
            if (lastUserIndex >= 0) {
                val lastUser = internalMessages[lastUserIndex]
                internalMessages.toMutableList().apply {
                    set(lastUserIndex, lastUser.copy(parts = lastUser.parts + UIMessagePart.Text("\n\n$block")))
                }
            } else {
                internalMessages
            }
        } ?: internalMessages

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
        } else {
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
        }
    }

    /** 提取最近一条用户消息的纯文本（作为记忆检索 query）；工具结果/媒体忽略，截断防止超长拖慢 embedding */
    private fun latestUserQuery(messages: List<UIMessage>): String {
        val text = messages.lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.mapNotNull { part -> (part as? UIMessagePart.Text)?.text }
            ?.joinToString("\n")
            .orEmpty()
            .trim()
        return text.take(200)
    }

    /**
     * P2 相关性召回注入：多路融合替换全量注入。
     * 路 1：FTS5 BM25 关键词（恒开，jieba 分词，见 MemoryFtsManager）；
     * 路 2：图传播（关键词 top 种子多跳 BFS ≤2 跳，衰减 0.5/跳，graphExpansion 开关）；
     * 路 3：语义（HNSW 向量，memorySearch.semanticSearch 开关且渠道已配置）。
     * 融合取 topK；返回 Empty 时仅当 fallbackToAllWhenEmpty（默认关）回退全量注入，
     * 空结果时输出占位不装全量（对齐 Operit shared.js:789-802）。
     * 会话级去重：conversationId 快照排除已注入 id（Operit snapshot_id 语义）。
     */
    private suspend fun retrieveMemories(
        query: String,
        assistantId: String,
        memories: ScopedMemories,
        memoryOptions: MemoryOptions,
        settings: Settings,
        conversationId: Uuid? = null,
    ): ScopedMemories {
        if (query.isBlank() || !memoryOptions.referencesAny()) return ScopedMemories.Empty
        val cfg = settings.memorySearch
        val excluded = injectedSet(conversationId)
        // 全局设置 OR per-assistant 开关（MemoryOptions 占位开关，默认关）
        val semanticOn = cfg.semanticSearch || memoryOptions.semanticSearch
        val graphOn = cfg.graphExpansion || memoryOptions.graphExpansion
        val topK = 10

        suspend fun retrieveScope(scope: String, all: List<AssistantMemory>): List<AssistantMemory> {
            if (all.isEmpty()) return emptyList()
            val scored = LinkedHashMap<Int, Float>()

            // 路 1：关键词（FTS5 BM25，jieba）
            runCatching { memoryRepo.searchMemories(query, scope, topK) }
                .getOrDefault(emptyList())
                .forEach { scored[it.memory.id] = it.score }

            // 路 2：图传播（关键词 top 种子的一跳/两跳邻居，衰减 0.5/跳）
            if (graphOn) {
                val seedId = scored.entries.maxByOrNull { it.value }?.key
                if (seedId != null) {
                    runCatching {
                        val hop1 = memoryRepo.getNeighbors(scope, seedId, maxHops = 1)
                        val hop2 = memoryRepo.getNeighbors(scope, seedId, maxHops = 2).filter { it !in hop1 }
                        val seedScore = scored[seedId] ?: 0f
                        hop1.forEach { scored.merge(it, seedScore * 0.5f) { a, b -> a + b } }
                        hop2.forEach { scored.merge(it, seedScore * 0.25f) { a, b -> a + b } }
                    }
                }
            }

            // 路 3：语义（HNSW）
            if (semanticOn) {
                runCatching { semanticSearch.search(settings, query, scope, topK) }
                    .getOrDefault(emptyList())
                    .forEach { scored.merge(it.id, 1f) { a, b -> a + b } }
            }

            if (scored.isEmpty()) return emptyList()
            val contentById = all.associateBy { it.id }
            val results = scored.entries
                .sortedByDescending { it.value }
                .take(topK)
                .mapNotNull { contentById[it.key] }
            // 会话级去重：排除本会话已注入的记忆（Operit snapshot_id 语义），并登记本轮新增注入
            val fresh = if (excluded != null) results.filter { it.id !in excluded } else results
            excluded?.addAll(fresh.map { it.id })
            return fresh
        }

        return ScopedMemories(
            assistant = if (memoryOptions.referenceAssistantMemory) {
                retrieveScope(assistantId, memories.assistant)
            } else emptyList(),
            global = if (memoryOptions.referenceGlobalMemory) {
                retrieveScope(MemoryRepository.GLOBAL_MEMORY_ID, memories.global)
            } else emptyList(),
        )
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

    private fun List<UIMessage>.extractHistoryEphemeralToolUserMessages(model: Model): List<UIMessage> {
        val currentRound = count { it.role == MessageRole.USER }
        return flatMapIndexed { msgIndex, msg ->
            val toolRound = take(msgIndex + 1).count { it.role == MessageRole.USER }
            val roundDistance = (currentRound - toolRound).coerceAtLeast(0)
            val tools = msg.parts.filterIsInstance<UIMessagePart.Tool>()
            tools.toEphemeralUserMessages(model, roundDistance)
        }
    }

    private fun List<UIMessagePart.Tool>.toEphemeralUserMessages(model: Model, roundDistance: Int = 0): List<UIMessage> = mapNotNull { tool ->
        if (tool.toolName == "image_generation" && Modality.IMAGE !in model.inputModalities) return@mapNotNull null
        if (tool.toolName == "workspace_read_file" && Modality.IMAGE !in model.inputModalities) return@mapNotNull null
        val mediaParts = tool.output.flatMap { it.collectMediaParts(tool.toolName, roundDistance) }
        if (mediaParts.isEmpty()) return@mapNotNull null
        // 带上 asset id(s): 模型后续想引用/二次编辑这些图时, 靠它定位。
        val assetIds = tool.output.flatMap { it.primaryAssetIds() }.distinct()
        val idSuffix = if (assetIds.isEmpty()) {
            ""
        } else {
            " asset_id=" + assetIds.joinToString(", ") + " (use this id to reference the image)"
        }
        val intro = if (tool.toolName == "workspace_read_file") {
            "[读取文件见下]$idSuffix"
        } else {
            "The tool `${tool.toolName}` returned the following attachment(s). " +
                "Use them as user-provided context for the next answer.$idSuffix"
        }
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(intro)) + mediaParts,
        )
    }

    /** 从工具输出的 JSON 文本里取全部 asset id（多图取数组，单图取单字段）；其它 part 无 id。 */
    private fun UIMessagePart.primaryAssetIds(): List<String> = when (this) {
        is UIMessagePart.Text -> runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            obj["asset_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOfNotNull(
                    obj["asset_id"]?.jsonPrimitive?.contentOrNull
                        ?: AssetUri.parse(obj["asset_uri"]?.jsonPrimitive?.contentOrNull)
                        ?: AssetUri.parse(obj["preview_asset_uri"]?.jsonPrimitive?.contentOrNull)
                )
        }.getOrDefault(emptyList())
        else -> emptyList()
    }

    private fun UIMessagePart.collectMediaParts(toolName: String, roundDistance: Int): List<UIMessagePart> = when (this) {
        is UIMessagePart.Image -> listOf(this)
        is UIMessagePart.Document -> listOf(this)
        is UIMessagePart.Video -> listOf(this)
        is UIMessagePart.Audio -> listOf(this)
        is UIMessagePart.Text -> {
            // 新格式生图输出把图片以 Image part 形式直接给出，Text 只含元信息
            // （asset_ids 数组），不再从 JSON 里解析图片，避免第一张重复出现两次。
            if (toolName == "image_generation" && text.contains("asset_ids")) {
                emptyList()
            } else {
                collectMediaPartsFromJsonText(text, toolName, roundDistance)
            }
        }
        is UIMessagePart.Tool -> output.flatMap { it.collectMediaParts(toolName, roundDistance) }
        else -> emptyList()
    }

    private fun collectMediaPartsFromJsonText(text: String, toolName: String, roundDistance: Int): List<UIMessagePart> {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val isUncompressed = obj["uncompressed"]?.jsonPrimitive?.booleanOrNull == true
        val originalUri = obj["asset_uri"]?.jsonPrimitive?.contentOrNull?.takeIf { AssetUri.isAsset(it) }
        val previewUri = obj["preview_asset_uri"]?.jsonPrimitive?.contentOrNull?.takeIf { AssetUri.isAsset(it) }

        val selectedUri = if (isUncompressed && roundDistance <= 2) {
            originalUri ?: previewUri
        } else {
            previewUri ?: originalUri
        } ?: return emptyList()

        return listOf(UIMessagePart.Image(selectedUri))
    }

    private fun List<UIMessage>.sanitizeToolMediaForModel(): List<UIMessage> = map { message ->
        message.copy(parts = message.parts.map { it.sanitizeToolMediaForModel() })
    }

    private fun UIMessagePart.sanitizeToolMediaForModel(): UIMessagePart = when (this) {
        is UIMessagePart.Tool -> copy(
            output = output.filterNot { it.isMediaPart() }.map { part ->
                if (part is UIMessagePart.Text) {
                    part.distillToolTextForModel(toolName)
                } else {
                    part.sanitizeToolMediaForModel()
                }
            }
        )
        else -> this
    }

    private fun UIMessagePart.Text.distillToolTextForModel(toolName: String): UIMessagePart.Text {
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
                put("description", "图片已读取并生成预览")
            }
            if (!ocr.isNullOrBlank()) {
                put("ocr", ocr)
            }
        }
        return UIMessagePart.Text(json.encodeToString(distilled))
    }

    private fun UIMessagePart.isMediaPart(): Boolean =
        this is UIMessagePart.Image || this is UIMessagePart.Document || this is UIMessagePart.Video || this is UIMessagePart.Audio

    private suspend fun List<UIMessage>.resolveToolUserMessagesForModel(model: Model): List<UIMessage> = buildList {
        this@resolveToolUserMessagesForModel.forEach { message ->
            val resolvedParts = buildList {
                message.parts.forEach { part ->
                    part.resolveToolUserPartForModel(model)?.let { add(it) }
                }
            }
            if (resolvedParts.size > 1 || resolvedParts.any { it !is UIMessagePart.Text }) {
                add(message.copy(parts = resolvedParts))
            }
        }
    }

    private suspend fun UIMessagePart.resolveToolUserPartForModel(model: Model): UIMessagePart? {
        val effectiveModel = if (this is UIMessagePart.Image && Modality.IMAGE !in model.inputModalities) {
            model.copy(inputModalities = model.inputModalities - Modality.URL)
        } else {
            model
        }
        return when (this) {
            is UIMessagePart.Image,
            is UIMessagePart.Document,
            is UIMessagePart.Video,
            is UIMessagePart.Audio -> assetResolver.resolvePartForModel(this, effectiveModel)
            else -> this
        }
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
