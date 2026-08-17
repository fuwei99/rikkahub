package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentRetryPolicy
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
    /**
     * 备用模型（最多 [AgentRetryPolicy.MAX_FALLBACK_MODELS] 个，按顺序尝试）。
     *
     * 主模型三次都打不通（或直接判定为「该模型没救」）时依次切换。
     * 原来这里只有单一 model，一挂就整任务失败、毫无补救。
     */
    val fallbackModels: List<Model> = emptyList(),
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

    /**
     * 同步阻塞跑完一个子任务，带重试 + 备用模型（PLAN_AGENT_RETRY_FALLBACK）。
     *
     * 节奏：每个模型最多 [AgentRetryPolicy.MAX_ATTEMPTS_PER_MODEL] 次，
     * 退避 2s/8s/20s 带抖动；打完仍失败则切下一个备用模型（退避重置）。
     *
     * 三条硬约束：
     * 1. [CancellationException] 必须原样抛出，绝不能进重试逻辑
     *    （否则用户取消不掉、任务变僵尸还继续烧 token）；
     * 2. 重试总耗时受 [totalDeadline] 约束，不能让退避把预算吃穿导致永不失败；
     * 3. semaphore permit 在整个重试期间被占着，所以退避上限压在 20s。
     */
    suspend fun run(spec: SubagentSpec): SubagentResult = semaphore.withPermit {
        val modelChain = buildList {
            add(spec.model)
            addAll(spec.fallbackModels.take(AgentRetryPolicy.MAX_FALLBACK_MODELS))
        }.distinctBy { it.id }.take(AgentRetryPolicy.MAX_MODELS)

        // 总预算：单次超时 × 模型数，再给退避留一点余量；到点一律彻底失败
        val totalDeadline = System.currentTimeMillis() +
            spec.timeout.inWholeMilliseconds * modelChain.size + RETRY_BUDGET_SLACK_MS

        var lastError: Throwable? = null
        modelChain.forEachIndexed { modelIndex, model ->
            // 用带标签的 for：SWITCH_MODEL 必须跳出**整个**本模型的重试循环，
            // 用 repeat + return@repeat 只会跳过本次迭代、继续拿同一个坏模型再打两次。
            attempts@ for (attempt in 1..AgentRetryPolicy.MAX_ATTEMPTS_PER_MODEL) {
                if (System.currentTimeMillis() >= totalDeadline) {
                    Log.w(TAG, "run: total retry budget exhausted")
                    throw lastError ?: IllegalStateException("Subagent retry budget exhausted")
                }
                try {
                    if (modelIndex > 0 || attempt > 1) {
                        spec.processingStatus.value =
                            "Subagent retry: model ${modelIndex + 1}/${modelChain.size}, attempt $attempt"
                        Log.i(TAG, "run: retrying with model=${model.displayName} attempt=$attempt")
                    }
                    return@withPermit runOnce(spec, model)
                } catch (e: CancellationException) {
                    // 取消绝不重试：结构化并发的取消必须原样穿透
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    val decision = AgentRetryPolicy.classify(e)
                    Log.w(TAG, "run: attempt $attempt on ${model.displayName} failed -> $decision", e)
                    when (decision) {
                        AgentRetryPolicy.Decision.FATAL -> throw e
                        AgentRetryPolicy.Decision.SWITCH_MODEL -> break@attempts
                        AgentRetryPolicy.Decision.RETRY_SAME_MODEL -> {
                            if (attempt >= AgentRetryPolicy.MAX_ATTEMPTS_PER_MODEL) break@attempts
                            val wait = AgentRetryPolicy.backoffMillis(attempt)
                                .coerceAtMost((totalDeadline - System.currentTimeMillis()).coerceAtLeast(0))
                            if (wait > 0) delay(wait)
                        }
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("Subagent failed without a recorded error")
    }

    private suspend fun runOnce(spec: SubagentSpec, model: Model): SubagentResult {
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
            messages = initialMessages,
        )

        withTimeout(spec.timeout) {
            generationHandler.generateText(
                settings = spec.settings,
                model = model,
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
                    // 子 agent 不继承记忆图：referenceAssistantGraph / referenceGlobalGraph 默认是 true，
                    // 多图体系下若不显式关掉，每个子 agent 都会拖一整套图进 prompt（review2 §二.H）。
                    referenceAssistantGraph = false,
                    referenceGlobalGraph = false,
                    allowEditAssistantGraph = false,
                    allowEditGlobalGraph = false,
                ),
                // 显式空绑定，绕过 GenerationHandler 内部的老字段推导
                graphBindings = emptyList(),
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
        return SubagentResult(
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
            messages = messages,
        )
    }

    companion object {
        const val MAX_CONCURRENT = 4

        /** 重试退避给总预算留的余量（3 模型 × 3 次退避约 90s，给 2 分钟）。 */
        const val RETRY_BUDGET_SLACK_MS = 120_000L
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
