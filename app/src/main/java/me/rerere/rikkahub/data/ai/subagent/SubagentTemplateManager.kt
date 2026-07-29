package me.rerere.rikkahub.data.ai.subagent

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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
        }
        // 每次都补齐缺失的默认模板（不再依赖目录是否存在），
        // 防止 schema 升级 / 用户误删 / 旧文件解析失败后一片空白。
        ensureDefaultTemplates(appDir)
        return appDir
    }

    fun listTemplates(workspaceRoot: File? = null, includeDisabled: Boolean = false): List<SubagentTemplate> {
        val dir = getSubagentsDir(workspaceRoot)
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            // 兼容旧 schema：defaultTools 从 String -> List<String>
            runCatching { migrateFileIfNeeded(file) }.onFailure {
                Log.w(TAG, "migrate failed: ${file.name}", it)
            }
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

    /**
     * 就地把老 schema 的字段修成新 schema，可安全重入。
     * 目前处理：
     * - defaultTools: String -> List<String>
     */
    private fun migrateFileIfNeeded(file: File) {
        val text = file.readText()
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        var changed = false
        val newMap = root.toMutableMap()

        val defaultTools = root["defaultTools"]
        if (defaultTools is JsonPrimitive && defaultTools.isString) {
            newMap["defaultTools"] = buildJsonArray { add(JsonPrimitive(defaultTools.content)) }
            changed = true
        }

        if (changed) {
            val newObj = JsonObject(newMap)
            file.writeText(json.encodeToString(JsonObject.serializer(), newObj))
            Log.i(TAG, "migrated legacy schema for ${file.name}")
        }
    }

    private fun ensureDefaultTemplates(dir: File) {
        val existingIds = dir.listFiles { _, name -> name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    val root = json.parseToJsonElement(file.readText()) as? JsonObject
                    root?.get("id")?.jsonPrimitive?.content
                }.getOrNull()
            }?.toSet() ?: emptySet()

        val defaults = listOf(
            SubagentTemplate(
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
            ),
            SubagentTemplate(
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
            ),
        )

        defaults.forEach { tmpl ->
            if (tmpl.id !in existingIds) {
                runCatching {
                    File(dir, "${tmpl.id}.json").writeText(
                        json.encodeToString(SubagentTemplate.serializer(), tmpl)
                    )
                    Log.i(TAG, "restored default template: ${tmpl.id}")
                }.onFailure {
                    Log.e(TAG, "Failed to write default subagent template: ${tmpl.id}", it)
                }
            }
        }
    }
}
