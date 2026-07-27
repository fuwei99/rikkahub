package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
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
): List<Tool> {
    val workspaceRoot = workspaceCwd?.let { File(it) }
    val templates = templateManager.listTemplates(workspaceRoot)
    val templateListDesc = if (templates.isNotEmpty()) {
        "Available subagent templates: " + templates.joinToString(", ") { "${it.id} (${it.description})" }
    } else ""

    fun resolveModel(modelOverrideObj: kotlinx.serialization.json.JsonObject?, template: SubagentTemplate?): Model {
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

        val baseModel = if (targetProvider != null && !overrideModelId.isNullOrBlank()) {
            targetProvider.models.firstOrNull { it.modelId == overrideModelId }
                ?: Model(modelId = overrideModelId, providerOverwrite = targetProvider)
        } else if (!overrideModelId.isNullOrBlank()) {
            model.copy(modelId = overrideModelId)
        } else {
            model
        }

        return baseModel
    }

    fun resolveAssistant(modelOverrideObj: kotlinx.serialization.json.JsonObject?, template: SubagentTemplate?): Assistant {
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

    val spawnAgentTool = Tool(
        name = "spawn_agent",
        description = """
            Spawn an isolated subagent to autonomously complete a sub-task (blocking until finish).
            Only final summary is returned. $templateListDesc
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "Task instruction for the subagent.")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional background info.")
                    })
                    put("template", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional template ID (e.g. 'grep_search', 'code_refactor').")
                    })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Tools list for the subagent, e.g. ['workspace_grep', 'workspace_read_file'].")
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max agent-loop steps.")
                    })
                    put("model_override", buildJsonObject {
                        put("type", "object")
                        put("description", "Override model for the subagent, e.g. { provider_name: 'Antigravity', model_id: 'gemini-3.6-flash-high', reasoning_effort: 'high' }")
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
            val templateId = obj["template"]?.jsonPrimitive?.content
            val template = templateId?.let { templateManager.getTemplate(it, workspaceRoot) }

            val selectionTools = obj["tools"]?.let { element ->
                runCatching { json.decodeFromJsonElement<List<String>>(element) }.getOrNull()
            } ?: template?.defaultTools ?: listOf("workspace")

            val maxSteps = obj["max_steps"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: template?.maxSteps ?: SubagentSpec.DEFAULT_MAX_STEPS
            val timeoutMinutes = template?.timeoutMinutes ?: 15

            val effectiveModel = resolveModel(obj["model_override"]?.jsonObject, template)
            val effectiveAssistant = resolveAssistant(obj["model_override"]?.jsonObject, template)
            
            // Build tool list based on selection
            val allTools = buildTools("all")
            val childTools = if (selectionTools.contains("all") || selectionTools.contains("workspace")) {
                allTools.map { it.unattended() }
            } else {
                allTools.filter { it.name in selectionTools }.map { it.unattended() }
            }

            try {
                val result = runner.run(
                    SubagentSpec(
                        task = task,
                        context = context,
                        tools = childTools,
                        maxSteps = maxSteps,
                        timeout = timeoutMinutes.toLong().minutes,
                        settings = settings,
                        model = effectiveModel,
                        assistant = effectiveAssistant,
                        workspaceCwd = workspaceCwd,
                        systemPrompt = template?.systemPrompt,
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
                listOf(UIMessagePart.Text("{\"error\":\"Subagent timed out and was cancelled.\"}"))
            } catch (e: IllegalStateException) {
                processingStatus.value = null
                listOf(UIMessagePart.Text("{\"error\":\"${e.message}\"}"))
            }
        }
    )

    val spawnAgentAsyncTool = Tool(
        name = "spawn_agent_async",
        description = "Spawn a non-blocking background subagent. Returns job_id immediately.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "string")
                        put("description", "Task instruction.")
                    })
                    put("context", buildJsonObject {
                        put("type", "string")
                    })
                    put("template", buildJsonObject {
                        put("type", "string")
                    })
                    put("tools", buildJsonObject {
                        put("type", "string")
                    })
                    put("model_override", buildJsonObject {
                        put("type", "object")
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
            val templateId = obj["template"]?.jsonPrimitive?.content
            val template = templateId?.let { templateManager.getTemplate(it, workspaceRoot) }

            val selectionTools = obj["tools"]?.let { element ->
                runCatching { json.decodeFromJsonElement<List<String>>(element) }.getOrNull()
            } ?: template?.defaultTools ?: listOf("workspace")

            val maxSteps = obj["max_steps"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: template?.maxSteps ?: SubagentSpec.DEFAULT_MAX_STEPS
            val timeoutMinutes = template?.timeoutMinutes ?: 15

            val effectiveModel = resolveModel(obj["model_override"]?.jsonObject, template)
            val effectiveAssistant = resolveAssistant(obj["model_override"]?.jsonObject, template)
            
            // Build tool list based on selection
            val allTools = buildTools("all")
            val childTools = if (selectionTools.contains("all") || selectionTools.contains("workspace")) {
                allTools.map { it.unattended() }
            } else {
                allTools.filter { it.name in selectionTools }.map { it.unattended() }
            }

            val job = jobManager.submitJob(
                SubagentSpec(
                    task = task,
                    context = context,
                    tools = childTools,
                    maxSteps = template?.maxSteps ?: SubagentSpec.DEFAULT_MAX_STEPS,
                    timeout = (template?.timeoutMinutes ?: 15).toLong().minutes,
                    settings = settings,
                    model = effectiveModel,
                    assistant = effectiveAssistant,
                    workspaceCwd = workspaceCwd,
                    systemPrompt = template?.systemPrompt,
                )
            )

            listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        buildJsonObject {
                            put("job_id", job.id)
                            put("status", job.status.name.lowercase())
                        }
                    )
                )
            )
        }
    )

    val checkAgentTool = Tool(
        name = "check_agent",
        description = "Check the status and summary of a background subagent job.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_id", buildJsonObject {
                        put("type", "string")
                    })
                },
                required = listOf("job_id"),
            )
        },
        execute = { args ->
            val jobId = args.jsonObject["job_id"]?.jsonPrimitive?.content
                ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"job_id is required\"}"))
            val job = jobManager.getJob(jobId)
                ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"Job $jobId not found\"}"))

            listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        buildJsonObject {
                            put("job_id", job.id)
                            put("status", job.status.name.lowercase())
                            put("steps", job.steps)
                            put("tool_calls", job.toolCalls)
                            job.result?.summary?.let { put("summary", it) }
                            job.error?.let { put("error", it) }
                        }
                    )
                )
            )
        }
    )

    val waitAgentTool = Tool(
        name = "wait_agent",
        description = "Wait for one or more background subagent jobs to complete.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_ids", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("timeout_seconds", buildJsonObject {
                        put("type", "integer")
                    })
                },
                required = listOf("job_ids"),
            )
        },
        execute = { args ->
            val jsonArray = args.jsonObject["job_ids"]
            val jobIds = jsonArray?.let { arr ->
                runCatching { json.decodeFromJsonElement<List<String>>(arr) }.getOrNull()
            } ?: emptyList()
            val timeoutSec = args.jsonObject["timeout_seconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 600

            val resultsMap = jobManager.waitJobs(jobIds, timeoutSec)
            val resultJson = buildJsonObject {
                resultsMap.forEach { (id, job) ->
                    put(id, buildJsonObject {
                        put("status", job.status.name.lowercase())
                        put("steps", job.steps)
                        put("tool_calls", job.toolCalls)
                        job.result?.summary?.let { put("summary", it) }
                        job.error?.let { put("error", it) }
                    })
                }
            }

            listOf(UIMessagePart.Text(json.encodeToString(resultJson)))
        }
    )

    val cancelAgentTool = Tool(
        name = "cancel_agent",
        description = "Cancel a running background subagent job.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("job_id", buildJsonObject { put("type", "string") })
                },
                required = listOf("job_id"),
            )
        },
        execute = { args ->
            val jobId = args.jsonObject["job_id"]?.jsonPrimitive?.content
                ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"job_id is required\"}"))
            val cancelled = jobManager.cancelJob(jobId)
            listOf(
                UIMessagePart.Text(
                    json.encodeToString(
                        buildJsonObject {
                            put("job_id", jobId)
                            put("cancelled", cancelled)
                        }
                    )
                )
            )
        }
    )

    return listOf(spawnAgentTool, spawnAgentAsyncTool, checkAgentTool, waitAgentTool, cancelAgentTool)
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
