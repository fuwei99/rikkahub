package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_PROMPT
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.SubagentSpec
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.time.Duration.Companion.minutes

private const val TAG = "MemoryGraphExtractor"

/**
 * 记忆图 P3：LLM 自动图谱抽取器（对齐 Operit `MemoryLibrary.saveMemory` 管线）。
 *
 * 流程：消息预处理 → 混合预检索（候选）→ 重复检测 → 结构化 JSON 抽取 →
 * 应用顺序 **merge → update → main → new → links**（有严格依赖：merge 先于 update，
 * links 解析时先查本轮 createdMemories 再查库，保证新节点可被立即链接）。
 *
 * 门槛（selection gate）：模型返回空对象 {}（常识问答/闲聊无长期价值）→ 直接跳过不写库。
 * 输出格式对齐 Operit：main/new/update/merge/links 均为数组结构，profile_markdown 可选（暂忽略）。
 */
class MemoryGraphExtractor(
    private val memoryRepo: MemoryRepository,
    private val subagentRunner: SubagentRunner,
) {
    companion object {
        private const val MAX_SOLUTION_CHARS = 3000
        private const val MAX_HISTORY_MESSAGES = 10
        private const val MAX_HISTORY_CHARS_PER_MESSAGE = 4000
        private const val TOP_CANDIDATES = 15

        /** 注入在 user 消息末尾的 <memory> 块（动态记忆注入，见 GenerationPrompts） */
        val MEMORY_BLOCK_REGEX = Regex("<memory>.*?</memory>", RegexOption.DOT_MATCHES_ALL)

        /** 工具结果/系统标签（对齐 Operit ChatMarkupRegex.pruneToolResultContent） */
        val TOOL_RESULT_REGEX = Regex("<(?:tool|tool_result|system|status|think)\\b[\\s\\S]*?</\\1>", RegexOption.DOT_MATCHES_ALL)
    }

    /** LLM 解析出的实体（对齐 Operit ParsedEntity） */
    private data class ParsedEntity(
        val title: String,
        val content: String,
        val tags: List<String>,
        val aliasFor: String?,
        val folderPath: String?,
    )

    private data class ParsedUpdate(
        val titleToUpdate: String,
        val newContent: String,
        val reason: String,
        val newCredibility: Float?,
        val newImportance: Float?,
    )

    private data class ParsedMerge(
        val sourceTitles: List<String>,
        val newTitle: String,
        val newContent: String,
        val newTags: List<String>,
        val folderPath: String?,
        val reason: String,
    )

    private data class ParsedLink(
        val sourceTitle: String,
        val targetTitle: String,
        val type: String,
        val description: String,
        val weight: Float,
    )

    private data class ParsedAnalysis(
        val mainProblem: ParsedEntity?,
        val extractedEntities: List<ParsedEntity>,
        val links: List<ParsedLink>,
        val updatedEntities: List<ParsedUpdate>,
        val mergedEntities: List<ParsedMerge>,
    ) {
        val isEmpty: Boolean
            get() = mainProblem == null && extractedEntities.isEmpty() &&
                updatedEntities.isEmpty() && mergedEntities.isEmpty()
    }

    /**
     * 抽取一段对话并写回记忆图。返回是否发生了写入。
     * @param history user/assistant 交替消息（role 为 "user"/"assistant"，已含最近回复）
     */
    suspend fun extract(
        settings: Settings,
        assistant: Assistant,
        history: List<Pair<String, String>>,
    ): Boolean {
        val scope = assistant.id.toString()
        // 1. 预处理：剥 <memory> 注入块 / 工具结果标记，防脏文本进 prompt
        val processedHistory = history
            .filter { (role, _) -> role == "user" || role == "assistant" }
            .map { (role, content) ->
                role to content
                    .replace(MEMORY_BLOCK_REGEX, " ")
                    .replace(TOOL_RESULT_REGEX, " ")
                    .trim()
            }
            .filter { (_, content) -> content.isNotBlank() }
        val query = processedHistory.lastOrNull { it.first == "user" }?.second.orEmpty()
        if (query.isBlank() || processedHistory.none { it.first == "user" }) return false
        val solution = processedHistory.lastOrNull { it.first == "assistant" }?.second.orEmpty()

        // 2. 模型解析：memoryModelId 未配置时回落 assistant 模型 / 全局默认
        val model = settings.providers.findModelById(settings.memoryModelId)
            ?: settings.providers.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return false

        // 3. 混合预检索 + 重复检测（Operit: searchMemories top15 + findAndDescribeDuplicates）
        val candidates = runCatching { memoryRepo.searchMemories(query, scope, TOP_CANDIDATES) }
            .getOrDefault(emptyList())
        val existingMemoriesPrompt = if (candidates.isNotEmpty()) {
            "Existing memories (prefer updating or merging these over creating duplicates):\n" +
                candidates.joinToString("\n") { hit ->
                    val title = hit.memory.content.lineSequence().firstOrNull()?.trim() ?: hit.memory.id.toString()
                    "- \"$title\": ${hit.memory.content.take(150).replace("\n", " ")}..."
                }
        } else {
            "No existing memories matched this conversation."
        }
        val duplicateTitles = candidates.mapNotNull { hit ->
            runCatching { memoryRepo.findMemoriesByTitle(scope, hit.memory.content.lineSequence().firstOrNull()?.trim().orEmpty()) }
                .getOrDefault(emptyList())
        }.flatten().map { it.title ?: it.content.lineSequence().firstOrNull()?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val duplicatesPrompt = if (duplicateTitles.isNotEmpty()) {
            "Duplicate titles detected, merge them: ${duplicateTitles.joinToString(", ")}"
        } else {
            ""
        }

        // 4. Prompt：用户配置的 memoryPrompt 为核心 + 动态上下文（对齐 Operit buildKnowledgeGraphExtractionPrompt）
        val systemPrompt = buildString {
            appendLine(settings.memoryPrompt.ifBlank { DEFAULT_MEMORY_PROMPT })
            appendLine()
            appendLine(existingMemoriesPrompt)
            if (duplicatesPrompt.isNotBlank()) {
                appendLine()
                appendLine(duplicatesPrompt)
            }
        }
        val userMessage = buildString {
            appendLine("Question:")
            appendLine(query)
            appendLine()
            appendLine("Solution:")
            appendLine(solution.take(MAX_SOLUTION_CHARS))
            val recentHistory = processedHistory.takeLast(MAX_HISTORY_MESSAGES)
            if (recentHistory.isNotEmpty()) {
                appendLine()
                appendLine("History:")
                recentHistory.forEachIndexed { index, (role, content) ->
                    appendLine("#${index + 1} $role: ${content.take(MAX_HISTORY_CHARS_PER_MESSAGE)}")
                }
            }
        }

        // 5. 走 SubagentRunner 无 UI 跑一轮（复用 rikkahub 现成基建，不重复造轮子）
        val result = runCatching {
            subagentRunner.run(
                SubagentSpec(
                    task = userMessage,
                    tools = emptyList(),
                    settings = settings,
                    model = model,
                    assistant = assistant.copy(
                        systemPrompt = systemPrompt,
                        enableMemory = false,
                        contextMessageSize = 20,
                    ),
                    maxSteps = 1,
                    maxTotalTokens = 16_000,
                    timeout = 5.minutes,
                )
            )
        }.getOrElse { e ->
            Log.w(TAG, "extract: subagent run failed: ${e.message}")
            return false
        }

        // 6. 解析 JSON（对齐 Operit parseAnalysisResult）；空 {} / 解析失败 → 跳过不写库
        val analysis = parseAnalysisResult(result.summary) ?: return false
        if (analysis.isEmpty) {
            Log.i(TAG, "extract: 空分析（无长期价值），跳过写库")
            return false
        }

        // 7. 应用写回（对齐 Operit saveMemory 应用顺序：merge → update → main → new → links）
        applyAnalysis(scope, analysis)
        Log.i(TAG, "extract: 写回完成 main=${analysis.mainProblem?.title}, new=${analysis.extractedEntities.size}, " +
            "update=${analysis.updatedEntities.size}, merge=${analysis.mergedEntities.size}, links=${analysis.links.size}")
        return true
    }

    private suspend fun applyAnalysis(scope: String, analysis: ParsedAnalysis) {
        // 本轮新建/更新记忆表：links 解析时先查这里再查库（Operit createdMemories 机制）
        val createdMemories = LinkedHashMap<String, Int>()

        // merge → update → main → new → links（Operit 顺序，依赖严格）
        analysis.mergedEntities.forEach { merge ->
            runCatching {
                memoryRepo.mergeMemories(
                    scope = scope,
                    sourceTitles = merge.sourceTitles,
                    newTitle = merge.newTitle,
                    newContent = merge.newContent,
                    folderPath = merge.folderPath,
                )?.let { createdMemories[merge.newTitle] = it.id }
            }.onFailure { Log.w(TAG, "merge failed: ${merge.newTitle}: ${it.message}") }
        }

        analysis.updatedEntities.forEach { update ->
            runCatching {
                val target = memoryRepo.findMemoryByTitle(scope, update.titleToUpdate)
                if (target != null) {
                    memoryRepo.updateMemory(
                        scope = scope,
                        id = target.id,
                        content = update.newContent,
                        importance = update.newImportance,
                        credibility = update.newCredibility,
                    )
                    createdMemories[update.titleToUpdate] = target.id
                }
            }.onFailure { Log.w(TAG, "update failed: ${update.titleToUpdate}: ${it.message}") }
        }

        // main 节点（核心事件，importance=0.8 / credibility=1.0，Operit 同款）
        analysis.mainProblem?.let { main ->
            runCatching {
                val existing = memoryRepo.findMemoryByTitle(scope, main.title)
                if (existing != null) {
                    memoryRepo.updateMemory(scope, existing.id, content = main.content)
                    createdMemories[main.title] = existing.id
                } else {
                    val created = memoryRepo.createMemory(
                        scope = scope,
                        title = main.title,
                        content = main.content,
                        importance = 0.8f,
                        credibility = 1.0f,
                        folderPath = main.folderPath,
                    )
                    createdMemories[main.title] = created.id
                }
            }.onFailure { Log.w(TAG, "main failed: ${main.title}: ${it.message}") }
        }

        // new 实体（aliasFor 别名解析：先查本轮 createdMemories 再查库，Operit:431-464）
        analysis.extractedEntities.forEach { entity ->
            runCatching {
                var memoryId: Int? = null
                if (!entity.aliasFor.isNullOrBlank()) {
                    memoryId = createdMemories[entity.aliasFor]
                        ?: memoryRepo.findMemoryByTitle(scope, entity.aliasFor)?.id
                }
                if (memoryId == null) {
                    val created = memoryRepo.createMemory(
                        scope = scope,
                        title = entity.title,
                        content = entity.content,
                        folderPath = entity.folderPath,
                    )
                    memoryId = created.id
                }
                createdMemories[entity.title] = memoryId
            }.onFailure { Log.w(TAG, "new failed: ${entity.title}: ${it.message}") }
        }

        // links（先查本轮 createdMemories 再查库，保证新节点可被立即链接，Operit:468-485）
        analysis.links.forEach { link ->
            runCatching {
                val sourceId = createdMemories[link.sourceTitle]
                    ?: memoryRepo.findMemoryByTitle(scope, link.sourceTitle)?.id
                val targetId = createdMemories[link.targetTitle]
                    ?: memoryRepo.findMemoryByTitle(scope, link.targetTitle)?.id
                if (sourceId != null && targetId != null && sourceId != targetId) {
                    memoryRepo.linkMemories(
                        scope = scope,
                        sourceId = sourceId,
                        targetId = targetId,
                        type = link.type.ifBlank { "related" },
                        weight = link.weight,
                        description = link.description,
                    )
                }
            }.onFailure { Log.w(TAG, "link failed: ${link.sourceTitle}->${link.targetTitle}: ${it.message}") }
        }
    }

    // ---------------- JSON 解析（对齐 Operit parseAnalysisResult） ----------------

    private fun parseAnalysisResult(raw: String): ParsedAnalysis? {
        return runCatching {
            val json = extractJsonObject(raw) ?: return null
            if (json.isEmpty()) return ParsedAnalysis(null, emptyList(), emptyList(), emptyList(), emptyList())
            if (json.toString() == "{}") return ParsedAnalysis(null, emptyList(), emptyList(), emptyList(), emptyList())

            val mainProblem = json["main"]?.takeIf { it is JsonArray && (it as JsonArray).isNotEmpty() }
                ?.let { parseEntityArray(it as JsonArray) }
            val extractedEntities = json["new"]?.takeIf { it is JsonArray }?.let { arr ->
                (arr as JsonArray).mapNotNull { parseEntityArray(it as? JsonArray ?: return@mapNotNull null) }
            } ?: emptyList()
            val updatedEntities = json["update"]?.takeIf { it is JsonArray }?.let { arr ->
                (arr as JsonArray).mapNotNull { el ->
                    val a = el as? JsonArray ?: return@mapNotNull null
                    if (a.size < 2) return@mapNotNull null
                    ParsedUpdate(
                        titleToUpdate = a[0].jsonPrimitive.contentOrNull.orEmpty(),
                        newContent = a[1].jsonPrimitive.contentOrNull.orEmpty(),
                        reason = a.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty(),
                        newCredibility = a.getOrNull(3)?.jsonPrimitive?.floatOrNull,
                        newImportance = a.getOrNull(4)?.jsonPrimitive?.floatOrNull,
                    )
                }
            } ?: emptyList()
            val mergedEntities = json["merge"]?.takeIf { it is JsonArray }?.let { arr ->
                (arr as JsonArray).mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    ParsedMerge(
                        sourceTitles = o["source_titles"]?.let { it as? JsonArray }
                            ?.mapNotNull { e -> e.jsonPrimitive.contentOrNull } ?: emptyList(),
                        newTitle = o["new_title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        newContent = o["new_content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        newTags = o["new_tags"]?.let { it as? JsonArray }
                            ?.mapNotNull { e -> e.jsonPrimitive.contentOrNull } ?: emptyList(),
                        folderPath = o["folder_path"]?.jsonPrimitive?.contentOrNull,
                        reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            } ?: emptyList()
            val links = json["links"]?.takeIf { it is JsonArray }?.let { arr ->
                (arr as JsonArray).mapNotNull { el ->
                    val a = el as? JsonArray ?: return@mapNotNull null
                    if (a.size < 3) return@mapNotNull null
                    ParsedLink(
                        sourceTitle = a[0].jsonPrimitive.contentOrNull.orEmpty(),
                        targetTitle = a[1].jsonPrimitive.contentOrNull.orEmpty(),
                        type = a[2].jsonPrimitive.contentOrNull.orEmpty(),
                        description = a.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty(),
                        weight = a.getOrNull(4)?.jsonPrimitive?.floatOrNull ?: 1.0f,
                    )
                }
            } ?: emptyList()

            ParsedAnalysis(
                mainProblem = mainProblem,
                extractedEntities = extractedEntities,
                links = links,
                updatedEntities = updatedEntities,
                mergedEntities = mergedEntities,
            )
        }.getOrElse { e ->
            Log.w(TAG, "parseAnalysisResult failed: ${e.message}")
            null
        }
    }

    private fun parseEntityArray(a: JsonArray): ParsedEntity {
        return ParsedEntity(
            title = a.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty(),
            content = a.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty(),
            tags = a.getOrNull(2)?.let { it as? JsonArray }?.mapNotNull { e -> e.jsonPrimitive.contentOrNull }
                ?: emptyList(),
            folderPath = a.getOrNull(3)?.jsonPrimitive?.contentOrNull,
            aliasFor = a.getOrNull(4)?.jsonPrimitive?.contentOrNull,
        )
    }

    /** 提取最外层 JSON 对象（兼容思考块/围栏/多余文本，对齐 Operit ChatUtils.extractJson） */
    private fun extractJsonObject(raw: String): JsonObject? {
        val cleaned = raw
            .replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), " ")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val jsonText = cleaned.substring(start, i + 1)
                            return runCatching { JsonInstant.parseToJsonElement(jsonText).jsonObject }.getOrNull()
                        }
                    }
                }
            }
        }
        return null
    }
}
