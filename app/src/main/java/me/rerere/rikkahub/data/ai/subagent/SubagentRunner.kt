package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.model.ScopedMemories
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val TAG = "SubagentRunner"

/** 子 agent 任务说明: 主对话的 subagent 工具调用会被翻译成一个 Spec */
data class SubagentSpec(
    val task: String,
    val tools: List<Tool>,
    val settings: Settings,
    val model: Model,
    val assistant: Assistant,
    val context: String? = null,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxTotalTokens: Int = DEFAULT_MAX_TOTAL_TOKENS,
    val contextMessageSize: Int = DEFAULT_CONTEXT_MESSAGE_SIZE,
    val timeout: Duration = DEFAULT_TIMEOUT,
    val workspaceCwd: String? = null,
    val systemPrompt: String? = null,
    /**
     * 子 agent 拿到的是同一批 workspace 工具, 但不共享主对话的 System 消息。
     * 不透传 transformer 的话, 挂载点 / 路径规则 / 工具白名单全部丢失, 只能靠瞎猜。
     */
    val inputTransformers: List<InputMessageTransformer> = emptyList(),
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val onProgress: (trace: SubagentTraceState) -> Unit = {},
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 50
        const val MAX_STEPS_LIMIT = 100
        const val DEFAULT_MAX_TOTAL_TOKENS = 64_000
        const val DEFAULT_CONTEXT_MESSAGE_SIZE = 20
        val DEFAULT_TIMEOUT = 15.minutes
    }
}

data class SubagentResult(
    val summary: String,
    val steps: Int,
    val toolCalls: Int,
    val durationMs: Long,
    val tokenUsage: TokenUsage = TokenUsage(),
)

/**
 * 无 UI 跑一轮 agent: 直接消费 GenerationHandler 的 Flow 至完成。
 * 不创建 Conversation 实体, 消息只存在内存里, 结束后返回最后一条 assistant 消息作为摘要。
 */
class SubagentRunner(
    private val generationHandler: GenerationHandler,
) {
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** 同步阻塞跑完一个子任务。取消传播依赖结构化并发。 */
    suspend fun run(spec: SubagentSpec): SubagentResult = semaphore.withPermit {
        val startAt = System.currentTimeMillis()
        val initialMessages = listOf(UIMessage.user(buildString {
            append(spec.task)
            if (!spec.context.isNullOrBlank()) {
                append("\n\n<context>\n")
                append(spec.context)
                append("\n</context>")
            }
        }))
        var latest: List<UIMessage> = initialMessages
        var latestTrace = SubagentTraceState(
            jobId = "",
            taskBrief = spec.task.take(160),
            status = SubagentJobStatus.RUNNING,
            maxSteps = spec.maxSteps,
            maxTotalTokens = spec.maxTotalTokens,
            contextMessageSize = spec.contextMessageSize,
            startedAt = startAt,
        )

        withTimeout(spec.timeout) {
            generationHandler.generateText(
                settings = spec.settings,
                model = spec.model,
                messages = initialMessages,
                assistant = spec.assistant.copy(
                    systemPrompt = spec.systemPrompt ?: SUBAGENT_SYSTEM_PROMPT,
                    enableMemory = false,
                    contextMessageSize = spec.contextMessageSize,
                ),
                memories = ScopedMemories.Empty,
                memoryOptions = MemoryOptions(
                    referenceAssistantMemory = false,
                    allowEditAssistantMemory = false,
                    referenceGlobalMemory = false,
                    allowEditGlobalMemory = false,
                    referenceRecentChats = false,
                ),
                tools = spec.tools,
                maxSteps = spec.maxSteps.coerceIn(1, SubagentSpec.MAX_STEPS_LIMIT),
                processingStatus = spec.processingStatus,
                workspaceCwd = spec.workspaceCwd,
                inputTransformers = spec.inputTransformers,
            ).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        latest = chunk.messages
                        latestTrace = buildTrace(spec, latest, startAt, SubagentJobStatus.RUNNING)
                        spec.onProgress(latestTrace)
                        val budget = spec.maxTotalTokens
                        if (budget > 0 && latestTrace.tokenUsage.totalTokens >= budget) {
                            error("Subagent token budget exceeded (${latestTrace.tokenUsage.totalTokens}/$budget)")
                        }
                    }
                }
            }
        }

        val finalTrace = buildTrace(spec, latest, startAt, SubagentJobStatus.COMPLETED)
        val summary = latest.lastOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
            ?.toText()
            ?: "(Subagent finished without a text summary; it may have exhausted max_steps mid-task.)"
        Log.i(TAG, "run: finished, steps=${finalTrace.steps}, toolCalls=${finalTrace.toolCalls.size}")
        return@withPermit SubagentResult(
            summary = summary,
            steps = finalTrace.steps,
            toolCalls = finalTrace.toolCalls.size,
            durationMs = System.currentTimeMillis() - startAt,
            tokenUsage = finalTrace.tokenUsage,
        )
    }

    private fun buildTrace(
        spec: SubagentSpec,
        messages: List<UIMessage>,
        startedAt: Long,
        status: SubagentJobStatus,
    ): SubagentTraceState {
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        val toolTraces = messages.flatMapIndexed { msgIndex, msg ->
            msg.getTools().map { tool ->
                SubagentToolTrace(
                    step = msgIndex + 1,
                    toolName = tool.toolName,
                    argsPreview = tool.input.take(500),
                    resultPreview = tool.output.joinToString("\n") { it.toTextForTrace() }.take(800).ifBlank { null },
                    status = if (tool.isExecuted) "finished" else "started",
                )
            }
        }
        val usage = messages.mapNotNull { it.usage }.fold(TokenUsage()) { acc, u ->
            TokenUsage(
                promptTokens = acc.promptTokens + u.promptTokens,
                completionTokens = acc.completionTokens + u.completionTokens,
                cachedTokens = acc.cachedTokens + u.cachedTokens,
                totalTokens = acc.totalTokens + u.totalTokens,
            )
        }
        return SubagentTraceState(
            jobId = "",
            taskBrief = spec.task.take(160),
            status = status,
            steps = assistantMessages.size,
            maxSteps = spec.maxSteps,
            toolCalls = toolTraces,
            currentTool = toolTraces.lastOrNull { it.status != "finished" }?.toolName,
            tokenUsage = usage,
            maxTotalTokens = spec.maxTotalTokens,
            contextMessageSize = spec.contextMessageSize,
            startedAt = startedAt,
        )
    }

    companion object {
        const val MAX_CONCURRENT = 4
    }
}

private fun UIMessagePart.toTextForTrace(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Image -> "[image]"
    is UIMessagePart.Document -> "[document $fileName]"
    is UIMessagePart.Audio -> "[audio]"
    is UIMessagePart.Video -> "[video]"
    else -> ""
}

private val SUBAGENT_SYSTEM_PROMPT = """
You are a subagent: an autonomous worker spawned by a main assistant to complete one specific task.

Rules:
- Work autonomously. There is NO human watching; you cannot ask questions or request approval.
- Use the provided tools to complete the task. If a tool call is rejected as requiring approval, find another way or report the limitation.
- Be efficient: avoid redundant tool calls, read only what you need.
- When the task is done (or you determine it cannot be done), end with a final message that summarizes:
  1. What was accomplished (or why it failed)
  2. Key results/findings the main assistant needs
  3. Any files created or modified (absolute paths)
This final message is the ONLY thing returned to the main assistant, so make it complete but concise.
""".trimIndent()
