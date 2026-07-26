package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

private const val TAG = "SubagentTools"

/**
 * 无人值守包装: 子 agent 内的工具不走 UI 审批弹窗。
 * 需要审批的调用直接拒绝并返回错误文本, 让子 agent 自行调整策略。
 */
fun Tool.unattended(): Tool {
    val originalNeedsApproval = needsApproval
    val originalExecute = execute
    return copy(
        needsApproval = { false },
        execute = { args ->
            if (originalNeedsApproval(args)) {
                listOf(
                    UIMessagePart.Text(
                        "{\"error\":\"This tool call requires user approval, which is unavailable in unattended subagent mode. " +
                            "Try a different approach or report this limitation in your summary.\"}"
                    )
                )
            } else {
                originalExecute(args)
            }
        }
    )
}

/**
 * spawn_agent 工具: 主对话的模型派生一个隔离上下文的子 agent 执行子任务, 只拿回摘要。
 *
 * @param buildTools 按选择组装子 agent 工具集 ("workspace" | "search" | "all"),
 *        调用方须保证结果里不含 spawn_agent 本身 (递归深度=1)
 */
fun createSpawnAgentTool(
    json: Json,
    runner: SubagentRunner,
    settings: Settings,
    model: Model,
    assistant: Assistant,
    workspaceCwd: String?,
    processingStatus: MutableStateFlow<String?>,
    buildTools: suspend (selection: String) -> List<Tool>,
) = Tool(
    name = "spawn_agent",
    description = """
        Spawn an isolated subagent to autonomously complete a sub-task, keeping its long work process out of this conversation's context.
        The subagent shares the same workspace but has its own fresh context; only its final summary is returned to you.
        Use it for long, self-contained tasks (e.g. process many files one by one, run and fix builds iteratively, batch research).
        Do NOT use it for trivial tasks that need only a few tool calls, or tasks needing user interaction (it runs unattended: no approval dialogs, tools requiring approval will be rejected).
        This call blocks until the subagent finishes (up to 15 minutes).
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Task instruction for the subagent. Be specific and self-contained: it cannot see this conversation. Include acceptance criteria and what to report back."
                    )
                })
                put("context", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional background info to pass along (relevant paths, prior findings, constraints)."
                    )
                })
                put("tools", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("workspace"))
                        add(JsonPrimitive("search"))
                        add(JsonPrimitive("all"))
                    })
                    put(
                        "description",
                        "Tool set for the subagent: workspace (files/shell, default), search (web), or all."
                    )
                })
                put("max_steps", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Max agent-loop steps. Defaults to ${SubagentSpec.DEFAULT_MAX_STEPS}, hard max ${SubagentSpec.MAX_STEPS_LIMIT}."
                    )
                })
            },
            required = listOf("task"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val task = obj["task"]?.jsonPrimitive?.content
            ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"task is required\"}"))
        val context = obj["context"]?.jsonPrimitive?.content
        val selection = obj["tools"]?.jsonPrimitive?.content ?: "workspace"
        val maxSteps = obj["max_steps"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: SubagentSpec.DEFAULT_MAX_STEPS

        val childTools = buildTools(selection).map { it.unattended() }
        Log.i(TAG, "spawn_agent: task=${task.take(80)}, tools=$selection(${childTools.size}), maxSteps=$maxSteps")

        try {
            val result = runner.run(
                SubagentSpec(
                    task = task,
                    context = context,
                    tools = childTools,
                    maxSteps = maxSteps,
                    settings = settings,
                    model = model,
                    assistant = assistant,
                    workspaceCwd = workspaceCwd,
                    processingStatus = processingStatus,
                    onProgress = { steps, toolCalls ->
                        processingStatus.value = "Subagent running: step $steps, $toolCalls tool calls"
                    },
                )
            )
            processingStatus.value = null
            listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        buildJsonObject {
                            put("summary", result.summary)
                            put("steps", result.steps)
                            put("tool_calls", result.toolCalls)
                            put("duration_seconds", result.durationMs / 1000)
                        }
                    )
                )
            )
        } catch (e: TimeoutCancellationException) {
            processingStatus.value = null
            listOf(
                UIMessagePart.Text(
                    "{\"error\":\"Subagent timed out after 15 minutes and was cancelled. Partial work in the workspace may remain. Consider splitting the task.\"}"
                )
            )
        } catch (e: IllegalStateException) {
            processingStatus.value = null
            listOf(UIMessagePart.Text("{\"error\":\"${e.message}\"}"))
        }
    },
)
