package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.time.Duration.Companion.minutes

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

fun createSubagentTools(
    json: Json,
    runner: SubagentRunner,
    jobManager: SubagentJobManager,
    templateManager: SubagentTemplateManager,
    settings: Settings,
    model: Model,
    assistant: Assistant,
    workspaceCwd: String?,
    processingStatus: MutableStateFlow<String?>,
    buildTools: suspend (selection: String) -> List<Tool>,
    /**
     * 与主对话同源的输入 transformer, 使子 agent 也能拿到 workspace 系统提示。
     * 入参为子 agent 实际获得的工具选择, 避免提示里列出它拿不到的工具。
     */
    inputTransformers: suspend (selection: List<String>) -> List<InputMessageTransformer> = { emptyList() },
): List<Tool> {
    val workspaceRoot = workspaceCwd?.let { File(it) }
    val templates = templateManager.listTemplates(workspaceRoot)
    val templateListDesc = if (templates.isNotEmpty()) {
        "Available subagent templates: " + templates.joinToString(", ") { "${it.id} (${it.description})" }
    } else "No subagent templates are currently enabled."

    fun resolveModel(modelOverrideObj: JsonObject?, template: SubagentTemplate?): Model {
        val overrideProviderIdStr = modelOverrideObj?.get("provider_id")?.jsonPrimitive?.content
            ?: template?.recommendedModel?.providerId
        val overrideProviderName = modelOverrideObj?.get("provider_name")?.jsonPrimitive?.content
            ?: template?.recommendedModel?.providerName
        val overrideModelId = modelOverrideObj?.get("model_id")?.jsonPrimitive?.content
            ?: template?.recommendedModel?.modelId

        val targetProvider = if (!overrideProviderIdStr.isNullOrBlank()) {
            val pId = runCatching { kotlin.uuid.Uuid.parse(overrideProviderIdStr) }.getOrNull()
            settings.providers.firstOrNull { it.id == pId }
        } else if (!overrideProviderName.isNullOrBlank()) {
            settings.providers.firstOrNull { it.name.equals(overrideProviderName, ignoreCase = true) }
        } else {
            null
        }

        return if (targetProvider != null && !overrideModelId.isNullOrBlank()) {
            targetProvider.models.firstOrNull { it.modelId == overrideModelId }
                ?: Model(modelId = overrideModelId, providerOverwrite = targetProvider)
        } else if (!overrideModelId.isNullOrBlank()) {
            model.copy(modelId = overrideModelId)
        } else {
            model
        }
    }

    fun resolveAssistant(modelOverrideObj: JsonObject?, template: SubagentTemplate?): Assistant {
        val overrideReasoningEffort = modelOverrideObj?.get("reasoning_effort")?.jsonPrimitive?.content
            ?: template?.recommendedModel?.reasoningEffort

        if (!overrideReasoningEffort.isNullOrBlank()) {
            val level = when (overrideReasoningEffort.lowercase()) {
                "low" -> me.rerere.ai.core.ReasoningLevel.LOW
                "medium" -> me.rerere.ai.core.ReasoningLevel.MEDIUM
                "high" -> me.rerere.ai.core.ReasoningLevel.HIGH
                "off" -> me.rerere.ai.core.ReasoningLevel.OFF
                "on" -> me.rerere.ai.core.ReasoningLevel.ON
                "auto" -> me.rerere.ai.core.ReasoningLevel.AUTO
                else -> assistant.reasoningLevel
            }
            return assistant.copy(reasoningLevel = level)
        }
        return assistant
    }

    suspend fun buildSpec(obj: JsonObject): Pair<SubagentSpec?, String?> {
        val task = obj["task"]?.jsonPrimitive?.content
            ?: return null to "task is required"
        val context = obj["context"]?.jsonPrimitive?.content
        val templateId = obj["template"]?.jsonPrimitive?.content ?: obj["template_id"]?.jsonPrimitive?.content
        val template = templateId?.let { templateManager.getTemplate(it, workspaceRoot) }
        val selectionTools = parseTools(obj["tools"]) ?: template?.defaultTools ?: listOf("workspace")
        val maxSteps = obj["max_steps"]?.jsonPrimitive?.intOrNull
            ?: template?.maxSteps ?: SubagentSpec.DEFAULT_MAX_STEPS
        val maxTotalTokens = obj["max_total_tokens"]?.jsonPrimitive?.intOrNull
            ?: SubagentSpec.DEFAULT_MAX_TOTAL_TOKENS
        val contextMessageSize = obj["context_message_size"]?.jsonPrimitive?.intOrNull
            ?: SubagentSpec.DEFAULT_CONTEXT_MESSAGE_SIZE
        val timeoutMinutes = obj["timeout_minutes"]?.jsonPrimitive?.intOrNull
            ?: template?.timeoutMinutes ?: 15
        val effectiveModel = resolveModel(obj["model_override"]?.jsonObject, template)
        val effectiveAssistant = resolveAssistant(obj["model_override"]?.jsonObject, template)

        val allTools = buildTools("all")
        val childTools = if (selectionTools.contains("all") || selectionTools.contains("workspace")) {
            allTools.map { it.unattended() }
        } else {
            allTools.filter { it.name in selectionTools }.map { it.unattended() }
        }

        return SubagentSpec(
            task = task,
            context = context,
            tools = childTools,
            maxSteps = maxSteps,
            maxTotalTokens = maxTotalTokens,
            contextMessageSize = contextMessageSize,
            timeout = timeoutMinutes.toLong().minutes,
            settings = settings,
            model = effectiveModel,
            assistant = effectiveAssistant,
            workspaceCwd = workspaceCwd,
            systemPrompt = template?.systemPrompt,
            inputTransformers = inputTransformers(selectionTools),
            processingStatus = processingStatus,
            onProgress = { trace ->
                processingStatus.value = "Subagent running: step ${trace.steps}/${trace.maxSteps}, ${trace.toolCalls.size} tool calls, ${trace.tokenUsage.totalTokens}/${trace.maxTotalTokens} tokens"
            },
        ) to templateId
    }

    fun SubagentJob.toJson() = buildJsonObject {
        put("type", "subagent_trace")
        put("job_id", id)
        put("status", status.name.lowercase())
        put("steps", steps)
        put("tool_calls", toolCalls)
        result?.let { result ->
            put("summary", result.summary)
            put("duration_seconds", result.durationMs / 1000)
            put("total_tokens", result.tokenUsage.totalTokens)
        }
        error?.let { put("error", it) }
    }

    fun SubagentTraceState.toJson() = buildJsonObject {
        put("type", "subagent_trace")
        put("job_id", jobId)
        put("template_id", templateId ?: "")
        put("task", taskBrief)
        put("status", status.name.lowercase())
        put("steps", steps)
        put("max_steps", maxSteps)
        put("tool_calls", toolCalls.size)
        put("current_tool", currentTool ?: "")
        put("prompt_tokens", tokenUsage.promptTokens)
        put("completion_tokens", tokenUsage.completionTokens)
        put("total_tokens", tokenUsage.totalTokens)
        put("max_total_tokens", maxTotalTokens)
        put("context_message_size", contextMessageSize)
        summary?.let { put("summary", it) }
        error?.let { put("error", it) }
        put("tools", buildJsonArray {
            toolCalls.takeLast(20).forEach { trace ->
                add(buildJsonObject {
                    put("step", trace.step)
                    put("tool_name", trace.toolName)
                    put("args", trace.argsPreview)
                    trace.resultPreview?.let { put("result", it) }
                    put("status", trace.status)
                })
            }
        })
    }

    val subagentTool = Tool(
        name = "subagent",
        description = """
            Spawn, check, wait for, or cancel autonomous subagents. Prefer action='spawn_async' for background work unless the next answer strictly depends on the result. $templateListDesc
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("spawn")
                            add("spawn_async")
                            add("check")
                            add("wait")
                            add("cancel")
                        })
                        put("description", "spawn blocks until done; spawn_async returns job_id immediately; check/wait/cancel manage existing jobs.")
                    })
                    put("task", buildJsonObject { put("type", "string") })
                    put("context", buildJsonObject { put("type", "string") })
                    put("template", buildJsonObject { put("type", "string") })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Tool names for the subagent, e.g. ['workspace_grep','workspace_read_file']; use ['workspace'] for all enabled workspace tools.")
                    })
                    put("max_steps", buildJsonObject { put("type", "integer") })
                    put("max_total_tokens", buildJsonObject { put("type", "integer") })
                    put("context_message_size", buildJsonObject { put("type", "integer") })
                    put("timeout_minutes", buildJsonObject { put("type", "integer") })
                    put("model_override", buildJsonObject { put("type", "object") })
                    put("job_id", buildJsonObject { put("type", "string") })
                    put("job_ids", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("timeout_seconds", buildJsonObject { put("type", "integer") })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            when (val action = obj["action"]?.jsonPrimitive?.content ?: "spawn_async") {
                "spawn" -> {
                    val (spec, error) = buildSpec(obj)
                    if (spec == null) return@Tool listOf(UIMessagePart.Text("{\"error\":\"$error\"}"))
                    try {
                        val job = SubagentJob(spec = spec)
                        val result = runner.run(spec.copy(onProgress = { trace ->
                            job.steps = trace.steps
                            job.toolCalls = trace.toolCalls.size
                            processingStatus.value = "Subagent running: step ${trace.steps}/${trace.maxSteps}, ${trace.toolCalls.size} tool calls, ${trace.tokenUsage.totalTokens}/${trace.maxTotalTokens} tokens"
                        }))
                        processingStatus.value = null
                        job.status = SubagentJobStatus.COMPLETED
                        job.result = result
                        job.steps = result.steps
                        job.toolCalls = result.toolCalls
                        listOf(UIMessagePart.Text(json.encodeToString(job.toJson())))
                    } catch (e: TimeoutCancellationException) {
                        processingStatus.value = null
                        listOf(UIMessagePart.Text("{\"type\":\"subagent_trace\",\"status\":\"timed_out\",\"error\":\"Subagent timed out and was cancelled.\"}"))
                    } catch (e: IllegalStateException) {
                        processingStatus.value = null
                        listOf(UIMessagePart.Text("{\"type\":\"subagent_trace\",\"status\":\"failed\",\"error\":\"${e.message}\"}"))
                    }
                }

                "spawn_async" -> {
                    val (spec, error) = buildSpec(obj)
                    if (spec == null) return@Tool listOf(UIMessagePart.Text("{\"error\":\"$error\"}"))
                    val templateId = obj["template"]?.jsonPrimitive?.content ?: obj["template_id"]?.jsonPrimitive?.content
                    val job = jobManager.submitJob(spec, templateId)
                    listOf(UIMessagePart.Text(json.encodeToString(job.toJson())))
                }

                "check" -> {
                    val jobId = obj["job_id"]?.jsonPrimitive?.content
                        ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"job_id is required\"}"))
                    val trace = jobManager.getTrace(jobId)
                    val job = jobManager.getJob(jobId)
                    listOf(UIMessagePart.Text(json.encodeToString(trace?.toJson() ?: job?.toJson() ?: buildJsonObject {
                        put("error", "Job $jobId not found")
                    })))
                }

                "wait" -> {
                    val jobIds = parseStringList(obj["job_ids"]).ifEmpty {
                        obj["job_id"]?.jsonPrimitive?.content?.let(::listOf).orEmpty()
                    }
                    val timeoutSec = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 600
                    val resultsMap = jobManager.waitJobs(jobIds, timeoutSec)
                    val resultJson = buildJsonObject {
                        put("type", "subagent_trace")
                        put("jobs", buildJsonArray {
                            resultsMap.values.forEach { job -> add(jobManager.getTrace(job.id)?.toJson() ?: job.toJson()) }
                        })
                    }
                    listOf(UIMessagePart.Text(json.encodeToString(resultJson)))
                }

                "cancel" -> {
                    val jobId = obj["job_id"]?.jsonPrimitive?.content
                        ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"job_id is required\"}"))
                    val cancelled = jobManager.cancelJob(jobId)
                    val trace = jobManager.getTrace(jobId)
                    listOf(UIMessagePart.Text(json.encodeToString(trace?.toJson() ?: buildJsonObject {
                        put("type", "subagent_trace")
                        put("job_id", jobId)
                        put("cancelled", cancelled)
                    })))
                }

                else -> listOf(UIMessagePart.Text("{\"error\":\"Unknown subagent action: $action\"}"))
            }
        }
    )

    return listOf(subagentTool)
}

private fun parseTools(element: JsonElement?): List<String>? = parseStringList(element).takeIf { it.isNotEmpty() }

private fun parseStringList(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonArray -> element.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    is JsonPrimitive -> element.content.split(',', ' ', ';').map { it.trim() }.filter { it.isNotBlank() }
    else -> emptyList()
}

fun createSpawnAgentTool(
    json: Json,
    runner: SubagentRunner,
    settings: Settings,
    model: Model,
    assistant: Assistant,
    workspaceCwd: String?,
    processingStatus: MutableStateFlow<String?>,
    buildTools: suspend (selection: String) -> List<Tool>,
): Tool {
    val templateManager: SubagentTemplateManager = GlobalContext.get().get()
    val jobManager: SubagentJobManager = GlobalContext.get().get()

    return createSubagentTools(
        json = json,
        runner = runner,
        jobManager = jobManager,
        templateManager = templateManager,
        settings = settings,
        model = model,
        assistant = assistant,
        workspaceCwd = workspaceCwd,
        processingStatus = processingStatus,
        buildTools = buildTools,
    ).first()
}
