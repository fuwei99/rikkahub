package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.common.android.MemoryGraphDebugLog
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_INJECT_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.MemoryGraphLink
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.time.Duration.Companion.seconds

/**
 * 注入选择器（方案 2026-08-06）：用一个轻量 LLM 取代向量语义检索。
 *
 * 记忆图规模很小（几十节点、上千字），直接把**整份目录**（`id 标题: 正文` + 关系行）
 * 和最近几轮对话丢给模型，让它回一个 `{"assistant":[..],"global":[..]}`，
 * 选出来的 id 就是命中集，后续图传播 / 上限裁剪 / 注入格式全部复用 GenerationHandler 旧逻辑。
 *
 * 失败（无模型 / 超时 / 解析不出 id）返回 null，由调用方决定回落到关键词+语义还是直接不注入。
 * 模型明确选空（合法 JSON 但两个数组都空）返回空结果，表示"本轮无需注入"，不算失败。
 */
class MemoryGraphSelector(
    private val providerManager: ProviderManager,
    private val graphRepo: MemoryGraphRepository,
) {
    data class Selection(
        val assistantIds: List<Long>,
        val globalIds: List<Long>,
    ) {
        val isEmpty: Boolean get() = assistantIds.isEmpty() && globalIds.isEmpty()
    }

    /**
     * @param assistantScope assistant scope id（不参与 assistant 检索时传 null）
     * @param includeGlobal 是否把 global scope 目录一起给模型
     * @param conversation 已裁剪好的最近对话文本（GenerationHandler 复用同一份 query 构造）
     */
    suspend fun select(
        settings: Settings,
        assistantScope: String?,
        includeGlobal: Boolean,
        conversation: String,
    ): Selection? = withContext(Dispatchers.IO) {
        val cfg = settings.memoryInject.sanitized()
        if (conversation.isBlank()) return@withContext null
        val model = settings.findModelById(settings.memoryInjectModelId)
            ?: settings.findModelById(settings.memoryModelId)
            ?: run {
                MemoryGraphDebugLog.w(TAG, "select abort: no inject model configured")
                return@withContext null
            }
        val provider = model.findProvider(settings.providers) ?: run {
            MemoryGraphDebugLog.w(TAG, "select abort: provider not found for model=${model.modelId}")
            return@withContext null
        }

        val assistantNodes = if (assistantScope != null) loadNodes(assistantScope, cfg.maxCandidateNodes) else emptyList()
        val globalNodes = if (includeGlobal) {
            loadNodes(MemoryGraphRepository.GLOBAL_SCOPE, cfg.maxCandidateNodes)
        } else {
            emptyList()
        }
        if (assistantNodes.isEmpty() && globalNodes.isEmpty()) {
            MemoryGraphDebugLog.w(TAG, "select abort: catalog empty (assistantScope=$assistantScope global=$includeGlobal)")
            return@withContext null
        }
        val assistantLinks = if (cfg.includeLinks && assistantScope != null) loadLinks(assistantScope) else emptyList()
        val globalLinks = if (cfg.includeLinks && includeGlobal) {
            loadLinks(MemoryGraphRepository.GLOBAL_SCOPE)
        } else {
            emptyList()
        }

        val catalog = buildCatalog(
            assistantNodes = assistantNodes,
            assistantLinks = assistantLinks,
            globalNodes = globalNodes,
            globalLinks = globalLinks,
            contentMaxChars = cfg.candidateContentMaxChars,
        )
        val userMessage = buildString {
            appendLine(catalog)
            appendLine("<conversation>")
            appendLine(conversation.take(cfg.contextMaxChars))
            appendLine("</conversation>")
            appendLine()
            appendLine("Select at most ${cfg.maxSelectNodes} node ids in total.")
        }
        val systemPrompt = settings.memoryInjectPrompt.ifBlank { DEFAULT_MEMORY_INJECT_PROMPT }
        MemoryGraphDebugLog.i(
            TAG,
            "select: model=${model.modelId} catalogNodes=${assistantNodes.size}+${globalNodes.size} " +
                "links=${assistantLinks.size}+${globalLinks.size} promptChars=${userMessage.length} " +
                "maxSelect=${cfg.maxSelectNodes} timeout=${cfg.timeoutSeconds}s"
        )

        val raw = runCatching {
            withTimeout(cfg.timeoutSeconds.seconds) {
                val handler = providerManager.getProviderByType(provider)
                val chunk = handler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.system(systemPrompt), UIMessage.user(userMessage)),
                    params = TextGenerationParams(
                        model = model,
                        reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.memoryInjectThinkingBudget),
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
                chunk.choices.firstOrNull()?.message?.toText().orEmpty()
            }
        }.getOrElse {
            Log.w(TAG, "select: llm call failed", it)
            MemoryGraphDebugLog.e(TAG, "select: llm call failed", it)
            return@withContext null
        }
        MemoryGraphDebugLog.i(TAG, "select: raw response=${raw.take(600)}")

        val allowedAssistant = assistantNodes.map { it.id }.toSet()
        val allowedGlobal = globalNodes.map { it.id }.toSet()
        val parsed = parse(raw, allowedAssistant, allowedGlobal, cfg.maxSelectNodes)
        if (parsed == null) {
            MemoryGraphDebugLog.w(TAG, "select: parse FAILED, no usable ids in response")
            return@withContext null
        }
        MemoryGraphDebugLog.i(
            TAG,
            "select: picked assistant=${parsed.assistantIds.joinToString(",")} " +
                "global=${parsed.globalIds.joinToString(",")}"
        )
        parsed
    }

    private suspend fun loadNodes(scope: String, limit: Int): List<MemoryGraphNode> =
        runCatching { graphRepo.getNodes(scope) }
            .getOrDefault(emptyList())
            .filter { it.title.isNotBlank() || it.content.isNotBlank() }
            .sortedByDescending { it.importance }
            .take(limit)

    private suspend fun loadLinks(scope: String): List<MemoryGraphLink> =
        runCatching { graphRepo.getLinks(scope) }.getOrDefault(emptyList())

    /** 目录格式与注入块保持一致（`id 标题: 正文` / `a -type-> b`），模型只需学一种格式。 */
    private fun buildCatalog(
        assistantNodes: List<MemoryGraphNode>,
        assistantLinks: List<MemoryGraphLink>,
        globalNodes: List<MemoryGraphNode>,
        globalLinks: List<MemoryGraphLink>,
        contentMaxChars: Int,
    ): String = buildString {
        fun flatten(text: String) = text.replace(Regex("\\s+"), " ").trim()
        fun clip(text: String) =
            if (contentMaxChars <= 0 || text.length <= contentMaxChars) text
            else text.take(contentMaxChars) + "…"

        fun appendScope(tag: String, nodes: List<MemoryGraphNode>, links: List<MemoryGraphLink>) {
            if (nodes.isEmpty()) return
            val ids = nodes.map { it.id }.toSet()
            appendLine("<$tag>")
            nodes.forEach { appendLine("${it.id} ${flatten(it.title)}: ${flatten(clip(it.content))}") }
            links.filter { it.sourceId in ids && it.targetId in ids }
                .forEach { appendLine("${it.sourceId} -${it.type}-> ${it.targetId}") }
            appendLine("</$tag>")
        }

        appendLine("<memory_catalog>")
        appendScope("assistant_graph", assistantNodes, assistantLinks)
        appendScope("global_graph", globalNodes, globalLinks)
        appendLine("</memory_catalog>")
    }

    /**
     * 宽松解析：先按 JSON（允许被 ```json 包裹或前后带解释），
     * 失败再退化为「全文抓所有数字，按 scope 归属过滤」——
     * 轻量模型经常多嘴，不该因为一句废话就整轮丢掉记忆。
     */
    private fun parse(
        raw: String,
        allowedAssistant: Set<Long>,
        allowedGlobal: Set<Long>,
        maxSelect: Int,
    ): Selection? {
        val jsonText = raw.substringAfter("```json", raw).substringBefore("```")
            .let { if (it.isBlank()) raw else it }
        val objText = jsonText.substringAfter('{', "").substringBeforeLast('}', "")
            .takeIf { it.isNotBlank() }
            ?.let { "{$it}" }
        if (objText != null) {
            val fromJson = runCatching {
                val obj = JsonInstant.parseToJsonElement(objText) as JsonObject
                fun ids(key: String, allowed: Set<Long>): List<Long> =
                    (obj[key] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.content.trim().toLongOrNull() }
                        ?.filter { it in allowed }
                        ?.distinct()
                        .orEmpty()
                Selection(
                    assistantIds = ids("assistant", allowedAssistant),
                    globalIds = ids("global", allowedGlobal),
                )
            }.getOrNull()
            // 合法 JSON（含明确选空）直接采用；解析异常才走正则兜底
            if (fromJson != null) return fromJson.capped(maxSelect)
        }
        val numbers = Regex("\\d+").findAll(raw).mapNotNull { it.value.toLongOrNull() }.distinct().toList()
        val assistantIds = numbers.filter { it in allowedAssistant }
        val globalIds = numbers.filter { it in allowedGlobal && it !in allowedAssistant }
        if (assistantIds.isEmpty() && globalIds.isEmpty()) return null
        return Selection(assistantIds, globalIds).capped(maxSelect)
    }

    /** 总量上限：assistant 优先吃额度（更贴近当前对话），global 吃剩下的。 */
    private fun Selection.capped(maxSelect: Int): Selection {
        if (assistantIds.size + globalIds.size <= maxSelect) return this
        val assistant = assistantIds.take(maxSelect)
        return Selection(assistant, globalIds.take(maxSelect - assistant.size))
    }

    companion object {
        private const val TAG = "MemoryGraphSelector"
    }
}
