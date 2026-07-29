package me.rerere.rikkahub.data.ai.subagent

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "SubagentTemplateManager"

class SubagentTemplateManager(
    private val context: Context,
    private val json: Json,
) {
    fun getSubagentsDir(workspaceRoot: File? = null): File {
        if (workspaceRoot != null) {
            val workspaceSubagents = File(workspaceRoot, "subagents")
            if (workspaceSubagents.exists() && workspaceSubagents.isDirectory) {
                return workspaceSubagents
            }
        }
        val appDir = File(context.filesDir, "subagents")
        if (!appDir.exists()) {
            appDir.mkdirs()
            ensureDefaultTemplates(appDir)
        }
        return appDir
    }

    fun listTemplates(workspaceRoot: File? = null, includeDisabled: Boolean = false): List<SubagentTemplate> {
        val dir = getSubagentsDir(workspaceRoot)
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                json.decodeFromString<SubagentTemplate>(file.readText())
            }.onFailure {
                Log.e(TAG, "Failed to parse subagent template JSON: ${file.name}", it)
            }.getOrNull()
        }.filter { includeDisabled || it.enabled }
    }

    fun getTemplate(id: String, workspaceRoot: File? = null): SubagentTemplate? {
        return listTemplates(workspaceRoot).firstOrNull { it.id == id }
    }

    fun setTemplateEnabled(id: String, enabled: Boolean, workspaceRoot: File? = null): Boolean {
        val dir = getSubagentsDir(workspaceRoot)
        val file = dir.listFiles { _, name -> name.endsWith(".json") }
            ?.firstOrNull { file ->
                runCatching { json.decodeFromString<SubagentTemplate>(file.readText()).id == id }.getOrDefault(false)
            } ?: return false
        return runCatching {
            val current = json.decodeFromString<SubagentTemplate>(file.readText())
            file.writeText(json.encodeToString(SubagentTemplate.serializer(), current.copy(enabled = enabled)))
        }.isSuccess
    }

    private fun ensureDefaultTemplates(dir: File) {
        val grepTemplate = SubagentTemplate(
            id = "grep_search",
            name = "Grep Code Search Agent",
            description = "High-efficiency search agent to find code patterns across workspace files.",
            systemPrompt = "You are a code search subagent. Use grep efficiently to find matches and report concise structural findings.",
            defaultTools = listOf("workspace_grep", "workspace_read_file"),
            maxSteps = 20,
            timeoutMinutes = 5,
            recommendedModel = ModelOverride(
                providerName = "Antigravity",
                modelId = "gemini-3.6-flash-high",
                reasoningEffort = "high"
            )
        )
        val refactorTemplate = SubagentTemplate(
            id = "code_refactor",
            name = "Code Refactoring Agent",
            description = "Autonomous agent for batch refactoring and code updates across multiple files.",
            systemPrompt = "You are a code refactoring subagent. Make precise edits, ensure code consistency, and report all modified files.",
            defaultTools = listOf("workspace_edit_file", "workspace_read_file", "workspace_apply_patch"),
            maxSteps = 50,
            timeoutMinutes = 15,
            recommendedModel = ModelOverride(
                providerName = "Antigravity",
                modelId = "gemini-3.6-flash-high",
                reasoningEffort = "high"
            )
        )
        runCatching {
            File(dir, "grep_search.json").writeText(json.encodeToString(SubagentTemplate.serializer(), grepTemplate))
            File(dir, "code_refactor.json").writeText(json.encodeToString(SubagentTemplate.serializer(), refactorTemplate))
        }.onFailure {
            Log.e(TAG, "Failed to write default subagent templates", it)
        }
    }
}
