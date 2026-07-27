package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val TAG = "SubagentRunner"

/** 子 agent 任务说明: 主对话的 spawn_agent 工具调用会被翻译成一个 Spec */
class SubagentSpec(
    val task: String,
    val tools: List<Tool>,
    val settings: Settings,
    val model: Model,
    val assistant: Assistant,
    val context: String? = null,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val timeout: Duration = DEFAULT_TIMEOUT,
    val workspaceCwd: String? = null,
    val systemPrompt: String? = null,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val onProgress: (steps: Int, toolCalls: Int) -> Unit = { _, _ -> },
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 50
        const val MAX_STEPS_LIMIT = 100
        val DEFAULT_TIMEOUT = 15.minutes
    }
}

class SubagentResult(
    val summary: String,
    val steps: Int,
    val toolCalls: Int,
    val durationMs: Long,
)

/**
 * 无 UI 跑一轮 agent: 直接消费 GenerationHandler 的 Flow 至完成。
 * 不创建 Conversation 实体, 消息只存在内存里, 结束后返回最后一条 assistant 消息作为摘要。
 *
 * 防失控:
 * - 并发上限 [MAX_CONCURRENT], 超出直接抛错
 * - 单次运行超时 (withTimeout, 由调用方捕获 TimeoutCancellationException)
 * - 不注册 spawn_agent 到子工具集 => 递归深度硬限制为 1 (由调用方保证)
 */
class SubagentRunner(
    private val generationHandler: GenerationHandler,
) {
    private val running = AtomicInteger(0)

    /**
     * 同步阻塞跑完一个子任务。取消传播依赖结构化并发:
     * 主对话生成被停止时, 本函数所在协程被取消, 子 agent 随之终止。
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException 超时 (调用方必须捕获, 不能让它冒泡成主对话取消)
     * @throws IllegalStateException 并发超限
     */
    suspend fun run(spec: SubagentSpec): SubagentResult {
        check(running.get() < MAX_CONCURRENT) {
            "Too many subagents running (max $MAX_CONCURRENT). Wait for the current one to finish."
        }
        running.incrementAndGet()
        val startAt = System.currentTimeMillis()
        try {
            val initialMessages = listOf(UIMessage.user(buildString {
                append(spec.task)
                if (!spec.context.isNullOrBlank()) {
                    append("\n\n<context>\n")
                    append(spec.context)
                    append("\n</context>")
                }
            }))
            var latest: List<UIMessage> = initialMessages

            withTimeout(spec.timeout) {
                generationHandler.generateText(
                    settings = spec.settings,
                    model = spec.model,
                    messages = initialMessages,
                    assistant = spec.assistant.copy(
                        systemPrompt = spec.systemPrompt ?: SUBAGENT_SYSTEM_PROMPT,
                        enableMemory = false,
                    ),
                    memories = emptyList(),
                    memoryOptions = MemoryOptions(
                        referenceAssistantMemory = false,
                        allowEditAssistantMemory = false,
                        referenceGlobalMemory = false,
                        allowEditGlobalMemory = false,
                    ),
                    tools = spec.tools,
                    maxSteps = spec.maxSteps.coerceIn(1, SubagentSpec.MAX_STEPS_LIMIT),
                    processingStatus = spec.processingStatus,
                    workspaceCwd = spec.workspaceCwd,
                ).collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Messages -> {
                            latest = chunk.messages
                            val steps = latest.count { it.role == MessageRole.ASSISTANT }
                            val toolCalls = latest.sumOf { msg -> msg.getTools().count { it.isExecuted } }
                            spec.onProgress(steps, toolCalls)
                        }
                    }
                }
            }

            val steps = latest.count { it.role == MessageRole.ASSISTANT }
            val toolCalls = latest.sumOf { msg -> msg.getTools().count { it.isExecuted } }
            val summary = latest.lastOrNull { it.role == MessageRole.ASSISTANT && it.toText().isNotBlank() }
                ?.toText()
                ?: "(Subagent finished without a text summary; it may have exhausted max_steps mid-task.)"
            Log.i(TAG, "run: finished, steps=$steps, toolCalls=$toolCalls")
            return SubagentResult(
                summary = summary,
                steps = steps,
                toolCalls = toolCalls,
                durationMs = System.currentTimeMillis() - startAt,
            )
        } finally {
            running.decrementAndGet()
        }
    }

    companion object {
        const val MAX_CONCURRENT = 4
    }
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
