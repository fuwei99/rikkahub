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
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.math.ceil
import kotlin.time.Duration.Companion.seconds

/**
 * 注入选择器（方案 2026-08-06，2026-08-07 多图化）：用一个轻量 LLM 取代向量语义检索。
 *
 * 记忆图规模很小（几十节点、上千字），直接把**整份目录**（`id 标题: 正文` + 关系行）
 * 和最近几轮对话丢给模型，让它回一个 `{"ids":[..]}`，
 * 选出来的 id 就是命中集，后续图传播 / 上限裁剪 / 注入格式全部复用 GenerationHandler 旧逻辑。
 *
 * 多图化的两个关键决定：
 * 1. **输出改扁平 id**：分桶 JSON（`{"assistant":[],"global":[]}`）随图数线性膨胀，
 *    而且模型频繁把 id 放错桶；节点 id 是整表 autoincrement 跨图唯一，扁平 id 是 scale-free 的，
 *    更省 token 更难错，scope 由本地回填。老格式仍兼容解析（用户可能自定义过 prompt）。
 * 2. **目录全局预算**：maxCandidateNodes 是 per-graph 的，10 张图就是 10×N 节点全文进 prompt，
 *    而注入模型每轮都调 —— 故加 catalogTotalMaxNodes 硬预算，按 sortOrder 逐图吃额度。
 *
 * 失败（无模型 / 超时 / 解析不出 id）返回 null，由调用方决定回落到关键词+语义还是直接不注入。
 * 模型明确选空（合法 JSON 但数组为空）返回空结果，表示"本轮无需注入"，不算失败。
 */
class MemoryGraphSelector(
    private val providerManager: ProviderManager,
    private val graphRepo: MemoryGraphRepository,
) {
    /**
     * 选择结果。[idsByGraph] 按 canonical graph id 分组（本地按节点归属回填，不依赖模型分桶）。
     */
    data class Selection(
        val idsByGraph: Map<String, List<Long>>,
    ) {
        val isEmpty: Boolean get() = idsByGraph.values.all { it.isEmpty() }

        fun idsFor(graphId: String): List<Long> = idsByGraph[graphId].orEmpty()
    }

    /**
     * @param graphs 本轮参与检索的图（已由 MemoryGraphBindingResolver 解析 + 排序 + 截断）
     * @param conversation 已裁剪好的最近对话文本（GenerationHandler 复用同一份 query 构造）
     */
    suspend fun select(
        settings: Settings,
        graphs: List<MemoryGraphMeta>,
        conversation: String,
    ): Selection? = withContext(Dispatchers.IO) {
        val cfg = settings.memoryInject.sanitized()
        if (conversation.isBlank()) return@withContext null
        if (graphs.isEmpty()) {
            MemoryGraphDebugLog.w(TAG, "select abort: no graphs bound")
            return@withContext null
        }
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

        // 目录预算：全局硬上限内按 sortOrder 顺序逐图吃额度，单图另受 per-graph 均分约束。
        val perGraphCap = maxOf(
            1,
            minOf(cfg.maxCandidateNodes, ceil(cfg.catalogTotalMaxNodes.toDouble() / graphs.size).toInt()),
        )
        var remaining = cfg.catalogTotalMaxNodes
        val loaded = mutableListOf<Triple<MemoryGraphMeta, List<MemoryGraphNode>, List<MemoryGraphLink>>>()
        for (graph in graphs) {
            if (remaining <= 0) break
            val nodes = loadNodes(graph.id, minOf(perGraphCap, remaining))
            if (nodes.isEmpty()) continue
            remaining -= nodes.size
            val links = if (cfg.includeLinks) loadLinks(graph.id) else emptyList()
            loaded += Triple(graph, nodes, links)
        }
        if (loaded.isEmpty()) {
            MemoryGraphDebugLog.w(TAG, "select abort: catalog empty (graphs=${graphs.joinToString(",") { it.name }})")
            return@withContext null
        }

        val catalog = buildCatalog(loaded, cfg.candidateContentMaxChars)
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
            "select: model=${model.modelId} graphs=${loaded.size} " +
                "catalogNodes=${loaded.sumOf { it.second.size }} links=${loaded.sumOf { it.third.size }} " +
                "budget=${cfg.catalogTotalMaxNodes} perGraphCap=$perGraphCap " +
                "promptChars=${userMessage.length} maxSelect=${cfg.maxSelectNodes} timeout=${cfg.timeoutSeconds}s"
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

        // scope 归属由本地节点数据回填，模型只需给扁平 id
        val graphIdByNode = mutableMapOf<Long, String>()
        loaded.forEach { (graph, nodes, _) -> nodes.forEach { graphIdByNode[it.id] = graph.id } }

        val parsed = parse(raw, graphIdByNode, cfg.maxSelectNodes)
        if (parsed == null) {
            MemoryGraphDebugLog.w(TAG, "select: parse FAILED, no usable ids in response")
            return@withContext null
        }
        MemoryGraphDebugLog.i(
            TAG,
            "select: picked " + parsed.idsByGraph.entries.joinToString(" ") { (id, ids) ->
                "$id=[${ids.joinToString(",")}]"
            }
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
        graphs: List<Triple<MemoryGraphMeta, List<MemoryGraphNode>, List<MemoryGraphLink>>>,
        contentMaxChars: Int,
    ): String = buildString {
        fun flatten(text: String) = text.replace(Regex("\\s+"), " ").trim()
        fun clip(text: String) =
            if (contentMaxChars <= 0 || text.length <= contentMaxChars) text
            else text.take(contentMaxChars) + "…"

        appendLine("<memory_catalog>")
        graphs.forEach { (graph, nodes, links) ->
            if (nodes.isEmpty()) return@forEach
            val ids = nodes.map { it.id }.toSet()
            val desc = graph.description.takeIf { it.isNotBlank() }
                ?.let { " desc=\"${flatten(it)}\"" }.orEmpty()
            appendLine("<graph id=\"${graph.wireId}\" name=\"${flatten(graph.name)}\"$desc>")
            nodes.forEach { appendLine("${it.id} ${flatten(it.title)}: ${flatten(clip(it.content))}") }
            links.filter { it.sourceId in ids && it.targetId in ids }
                .forEach { appendLine("${it.sourceId} -${it.type}-> ${it.targetId}") }
            appendLine("</graph>")
        }
        appendLine("</memory_catalog>")
    }

    /**
     * 宽松解析：先按 JSON（允许被 ```json 包裹或前后带解释），
     * 失败再退化为「全文抓所有数字，按允许集过滤」——
     * 轻量模型经常多嘴，不该因为一句废话就整轮丢掉记忆。
     *
     * 三种输入格式都吃：
     * - 新格式 `{"ids":[1,2,3]}`；
     * - 老格式 `{"assistant":[1],"global":[2]}`（用户可能自定义过 prompt，不能因为改格式就失效）；
     * - 纯文本里散落的数字（正则兜底）。
     */
    private fun parse(
        raw: String,
        graphIdByNode: Map<Long, String>,
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
                fun arrayIds(key: String): List<Long> =
                    (obj[key] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.content.trim().toLongOrNull() }
                        .orEmpty()
                // 新格式优先；老双数组格式作为兼容读取
                val ids = (arrayIds("ids") + arrayIds("assistant") + arrayIds("global"))
                    .filter { it in graphIdByNode }
                    .distinct()
                // 合法 JSON（含明确选空）直接采用
                if (obj.containsKey("ids") || obj.containsKey("assistant") || obj.containsKey("global")) {
                    ids
                } else {
                    null
                }
            }.getOrNull()
            if (fromJson != null) return group(fromJson, graphIdByNode, maxSelect)
        }
        val numbers = Regex("\\d+").findAll(raw)
            .mapNotNull { it.value.toLongOrNull() }
            .filter { it in graphIdByNode }
            .distinct()
            .toList()
        if (numbers.isEmpty()) return null
        return group(numbers, graphIdByNode, maxSelect)
    }

    /** 总量上限后按 graph 分组，保持模型给出的相对顺序（越前越相关）。 */
    private fun group(
        ids: List<Long>,
        graphIdByNode: Map<Long, String>,
        maxSelect: Int,
    ): Selection {
        val capped = ids.take(maxSelect)
        val grouped = mutableMapOf<String, MutableList<Long>>()
        capped.forEach { id ->
            val graphId = graphIdByNode[id] ?: return@forEach
            grouped.getOrPut(graphId) { mutableListOf() }.add(id)
        }
        return Selection(grouped.mapValues { it.value.toList() })
    }

    companion object {
        private const val TAG = "MemoryGraphSelector"

        /** 老 tag 别名 → canonical id 的兜底映射（仅供历史 trace 使用） */
        internal fun legacyScopeToGraphId(scope: String, assistantId: String): String = when (scope) {
            "assistant" -> assistantId
            "global" -> MemoryGraphRepository.GLOBAL_SCOPE
            else -> scope
        }
    }
}
