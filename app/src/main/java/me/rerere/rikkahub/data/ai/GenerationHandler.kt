package me.rerere.rikkahub.data.ai

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
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024
private val memoryToolNames = setOf("memory_tool", "assistant_memory_tool", "global_memory_tool")

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
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        memoryOptions: MemoryOptions = MemoryOptions(),
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
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
                    if (effectiveMemoryOptions.allowEditAssistantMemory) {
                        buildMemoryTools(
                            json = json,
                            toolName = "assistant_memory_tool",
                            memoryScope = "assistant-specific",
                            onCreation = { content ->
                                memoryRepo.addMemory(assistant.id.toString(), content)
                            },
                            onUpdate = { id, content ->
                                memoryRepo.updateContentInScope(assistant.id.toString(), id, content)
                            },
                            onDelete = { id ->
                                memoryRepo.deleteMemoryInScope(assistant.id.toString(), id)
                            }
                        ).let(this::addAll)
                    }
                    if (effectiveMemoryOptions.allowEditGlobalMemory) {
                        buildMemoryTools(
                            json = json,
                            toolName = "global_memory_tool",
                            memoryScope = "global shared",
                            onCreation = { content ->
                                memoryRepo.addMemory(MemoryRepository.GLOBAL_MEMORY_ID, content)
                            },
                            onUpdate = { id, content ->
                                memoryRepo.updateContentInScope(MemoryRepository.GLOBAL_MEMORY_ID, id, content)
                            },
                            onDelete = { id ->
                                memoryRepo.deleteMemoryInScope(MemoryRepository.GLOBAL_MEMORY_ID, id)
                            }
                        ).let(this::addAll)
                    }
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
                    memories = memories ?: emptyList(),
                    memoryOptions = memoryOptions.effective(assistant),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
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
                                                        append("\n${it.stackTraceToString()}")
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
        memories: List<AssistantMemory>,
        memoryOptions: MemoryOptions,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        ephemeralToolUserMessages: List<UIMessage> = emptyList(),
    ) {
        val resolvedEphemeralToolUserMessages = ephemeralToolUserMessages.resolveToolUserMessagesForModel(model)
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

                // 记忆（仅在聊天框允许“参考记忆”时注入）
                if (memoryOptions.referencesAny() && memories.isNotEmpty()) {
                    appendLine()
                    append(buildMemoryPrompt(memories = memories))
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
                messages = internalMessages,
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
                messages = internalMessages,
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

    private fun List<UIMessagePart.Tool>.toEphemeralUserMessages(model: Model): List<UIMessage> = mapNotNull { tool ->
        if (tool.toolName == "image_generation" && Modality.IMAGE !in model.inputModalities) return@mapNotNull null
        if (tool.toolName == "workspace_read_file" && Modality.IMAGE !in model.inputModalities) return@mapNotNull null
        val mediaParts = tool.output.flatMap { it.collectMediaParts(tool.toolName) }
        if (mediaParts.isEmpty()) return@mapNotNull null
        val intro = if (tool.toolName == "workspace_read_file") {
            "[读取文件见下]"
        } else {
            "The tool `${tool.toolName}` returned the following attachment(s). Use them as user-provided context for the next answer."
        }
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(intro)) + mediaParts,
        )
    }

    private fun UIMessagePart.collectMediaParts(toolName: String): List<UIMessagePart> = when (this) {
        is UIMessagePart.Image -> listOf(this)
        is UIMessagePart.Document -> listOf(this)
        is UIMessagePart.Video -> listOf(this)
        is UIMessagePart.Audio -> listOf(this)
        is UIMessagePart.Text -> collectMediaPartsFromJsonText(text, toolName)
        is UIMessagePart.Tool -> output.flatMap { it.collectMediaParts(toolName) }
        else -> emptyList()
    }

    private fun collectMediaPartsFromJsonText(text: String, toolName: String): List<UIMessagePart> {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val uri = obj["asset_uri"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { AssetUri.isAsset(it) }
            ?: return emptyList()
        return listOf(UIMessagePart.Image(uri))
    }

    private fun List<UIMessage>.sanitizeToolMediaForModel(): List<UIMessage> = map { message ->
        message.copy(parts = message.parts.map { it.sanitizeToolMediaForModel() })
    }

    private fun UIMessagePart.sanitizeToolMediaForModel(): UIMessagePart = when (this) {
        is UIMessagePart.Tool -> copy(output = output.filterNot { it.isMediaPart() }.map { it.sanitizeToolMediaForModel() })
        else -> this
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
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
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
