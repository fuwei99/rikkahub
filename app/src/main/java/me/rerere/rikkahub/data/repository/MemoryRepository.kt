package me.rerere.rikkahub.data.repository

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryLinkDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryLinkEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.fts.MemoryFtsManager
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryLink
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY_LINKS
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Edge
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Graph
import me.rerere.rikkahub.ui.pages.assistant.detail.graph.Node
import me.rerere.rikkahub.utils.JsonInstant

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val memoryLinkDAO: MemoryLinkDAO,
    private val database: AppDatabase,
) {
    /** 记忆全文检索（Phase 2 关键词路，FTS5 BM25 + jieba） */
    private val fts = MemoryFtsManager(database)

    /** FTS 检索命中（score 越大越相关；多路融合时归一化） */
    data class MemorySearchHit(
        val memory: AssistantMemory,
        val score: Float,
    )

    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"

        /** 推荐的链接类型（与 Operit 一致，工具描述里会展示） */
        val LINK_TYPES = listOf(
            "related", "follows", "corrects", "updates",
            "involves", "happens_at", "part_of", "allied_with", "opposes",
        )
    }

    /** 云锚点同步写钩（P1）：memory 整表 bundle 入待推队列 */
    private suspend fun enqueueBundleSync() {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_MEMORY)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_MEMORY,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 云锚点同步写钩：memory_link 整表 bundle 入待推队列 */
    private suspend fun enqueueLinkBundleSync() {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_MEMORY_LINKS)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_MEMORY_LINKS,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ---------------- 记忆节点 ----------------

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        memoryLinkDAO.deleteLinksOfScope(assistantId)
        fts.deleteOfScope(assistantId)
        enqueueBundleSync()
        enqueueLinkBundleSync()
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content
        )
        memoryDAO.updateMemory(newMemory)
        fts.upsert(newMemory)
        enqueueBundleSync()
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }


    suspend fun updateContentInScope(assistantId: String, id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        require(old.assistantId == assistantId) { "Memory record #$id is not in the requested memory scope" }
        return updateContent(id, content)
    }

    suspend fun deleteMemoryInScope(assistantId: String, id: Int) {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        require(old.assistantId == assistantId) { "Memory record #$id is not in the requested memory scope" }
        deleteMemory(id)
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content
                )
            ).toInt()
        )
        fts.upsert(
            MemoryEntity(
                id = newMemory.id,
                assistantId = assistantId,
                content = newMemory.content,
            )
        )
        enqueueBundleSync()
        return newMemory
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        // 记忆节点删除时联动清除其关联边，避免悬挂链接
        memoryLinkDAO.deleteLinksOfMemory(id)
        fts.delete(id)
        enqueueBundleSync()
        enqueueLinkBundleSync()
    }

    // ---------------- 记忆图 P3：LLM 自动抽取写回（对齐 Operit MemoryRepository 标题检索/合并/更新） ----------------

    /** 按标题查记忆（抽取器用）。title 为 null 的老记忆回落 content 首行匹配。 */
    suspend fun findMemoryByTitle(scope: String, title: String): MemoryEntity? {
        val exact = memoryDAO.findMemoryByTitle(scope, title.trim())
        if (exact != null) return exact
        val firstLine = title.trim()
        return memoryDAO.getMemoriesOfAssistant(scope).firstOrNull {
            it.content.lineSequence().firstOrNull()?.trim() == firstLine
        }
    }

    /** 按标题找全部同名记忆（重复检测，对齐 Operit findMemoriesByTitle）。 */
    suspend fun findMemoriesByTitle(scope: String, title: String): List<MemoryEntity> {
        val exact = memoryDAO.findMemoriesByTitle(scope, title.trim())
        if (exact.isNotEmpty()) return exact
        val firstLine = title.trim()
        return memoryDAO.getMemoriesOfAssistant(scope).filter {
            it.content.lineSequence().firstOrNull()?.trim() == firstLine
        }
    }

    /** 显式创建记忆节点（带 title/importance/credibility；抽取器 main/new 节点用）。 */
    suspend fun createMemory(
        scope: String,
        title: String,
        content: String,
        importance: Float? = null,
        credibility: Float? = null,
        folderPath: String? = null,
    ): MemoryEntity {
        val entity = MemoryEntity(
            assistantId = scope,
            content = content,
            title = title.trim(),
            importance = importance,
            credibility = credibility,
            folderPath = folderPath,
        )
        val saved = entity.copy(id = memoryDAO.insertMemory(entity).toInt())
        fts.upsert(saved)
        enqueueBundleSync()
        return saved
    }

    /**
     * 更新记忆（抽取器 update 用）：内容/权重可改；旧内容追加到 history JSON 数组（最多 10 条），
     * 支持回溯（方案 §8.3 第 4 条）。
     */
    suspend fun updateMemory(
        scope: String,
        id: Int,
        content: String? = null,
        title: String? = null,
        importance: Float? = null,
        credibility: Float? = null,
        folderPath: String? = null,
    ): MemoryEntity {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        require(old.assistantId == scope) { "Memory record #$id is not in the requested scope" }
        val historyEntries = runCatching {
            JsonInstant.parseToJsonElement(old.history ?: "[]").jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())
        val newContent = content ?: old.content
        val newHistory = buildJsonArray {
            historyEntries.takeLast(9).forEach { add(JsonPrimitive(it)) }
            if (old.content.isNotBlank() && newContent != old.content) add(JsonPrimitive(old.content))
        }
        val updated = old.copy(
            content = newContent,
            title = title ?: old.title,
            importance = importance ?: old.importance,
            credibility = credibility ?: old.credibility,
            folderPath = folderPath ?: old.folderPath,
            history = newHistory.toString(),
        )
        memoryDAO.updateMemory(updated)
        fts.upsert(updated)
        enqueueBundleSync()
        return updated
    }

    /**
     * 合并多条记忆为新节点（对齐 Operit mergeMemories + 链接重定向）：
     * 1. 创建合并后新节点（newTitle/newContent）；
     * 2. 源节点全部出入边重定向到新节点（防悬挂，Operit 合并时同样做链接重定向）；
     * 3. 删除源节点（连带清 FTS 与边）。
     */
    suspend fun mergeMemories(
        scope: String,
        sourceTitles: List<String>,
        newTitle: String,
        newContent: String,
        folderPath: String? = null,
    ): MemoryEntity? {
        val sources = sourceTitles.mapNotNull { findMemoryByTitle(scope, it) }.distinctBy { it.id }
        if (sources.isEmpty()) return null
        val merged = createMemory(
            scope = scope,
            title = newTitle,
            content = newContent,
            importance = 0.6f,
            credibility = 0.8f,
            folderPath = folderPath,
        )
        // 链接重定向：源节点的出入边改为指向新节点
        val sourceIds = sources.map { it.id }.toSet()
        memoryLinkDAO.getLinksOfScope(scope).forEach { link ->
            if (link.sourceId !in sourceIds && link.targetId !in sourceIds) return@forEach
            val newSource = if (link.sourceId in sourceIds) merged.id else link.sourceId
            val newTarget = if (link.targetId in sourceIds) merged.id else link.targetId
            memoryLinkDAO.deleteById(link.id)
            if (newSource != newTarget) {
                runCatching {
                    linkMemories(scope, newSource, newTarget, link.type, link.weight, link.description)
                }
            }
        }
        sources.forEach {
            memoryDAO.deleteMemory(it.id)
            fts.delete(it.id)
        }
        enqueueBundleSync()
        enqueueLinkBundleSync()
        return merged
    }

    // ---------------- 记忆检索（Phase 2 关键词路：FTS5 BM25 + jieba） ----------------

    /**
     * 关键词检索：FTS5 BM25 召回，限定 scope（assistant id 或 GLOBAL_MEMORY_ID）。
     * score 由 BM25 rank 转换（越大越相关）。AND 命中不足自动降级 OR（MemoryFtsManager 内处理）。
     */
    suspend fun searchMemories(
        query: String,
        scope: String,
        topK: Int = 10,
    ): List<MemorySearchHit> {
        if (query.isBlank()) return emptyList()
        return fts.search(keyword = query, scope = scope, limit = topK)
            .map { hit ->
                MemorySearchHit(
                    memory = AssistantMemory(id = hit.memoryId, content = hit.content),
                    // BM25 rank 为负数，越接近 0 越相关；取负后越大越相关
                    score = -hit.rank,
                )
            }
    }

    // ---------------- 记忆链接（Memory Graph P1） ----------------

    /**
     * 建边：同 source+type+target 幂等（已存在则直接返回，不重复建边，Operit 同款语义）。
     * 链接仅允许同 scope。
     */
    suspend fun linkMemories(
        scope: String,
        sourceId: Int,
        targetId: Int,
        type: String = "related",
        weight: Float = 0.7f,
        description: String = "",
    ): MemoryLink {
        require(sourceId != targetId) { "source_id and target_id must be different" }
        val source = memoryDAO.getMemoryById(sourceId)
            ?: error("Memory record #$sourceId not found")
        require(source.assistantId == scope) { "source memory is not in the requested scope" }
        val target = memoryDAO.getMemoryById(targetId)
            ?: error("Memory record #$targetId not found")
        require(target.assistantId == scope) { "target memory is not in the requested scope" }

        val existing = memoryLinkDAO.findDuplicate(scope, sourceId, targetId, type)
        if (existing != null) {
            return existing.toModel(source.content, target.content)
        }

        val link = MemoryLinkEntity(
            sourceId = sourceId,
            targetId = targetId,
            type = type,
            weight = weight.coerceIn(0f, 1f),
            description = description,
            scope = scope,
            createdAt = System.currentTimeMillis(),
        )
        val id = memoryLinkDAO.insert(link)
        enqueueLinkBundleSync()
        return link.copy(id = id).toModel(source.content, target.content)
    }

    /**
     * 查询链接。memoryId 为空返回作用域全部链接；否则返回该记忆的出入边。
     * 批量取两端节点内容，避免 N+1。
     */
    suspend fun queryMemoryLinks(
        scope: String,
        memoryId: Int? = null,
        type: String? = null,
    ): List<MemoryLink> {
        val links = if (memoryId != null) {
            memoryLinkDAO.getLinksOfMemory(scope, memoryId)
        } else {
            memoryLinkDAO.getLinksOfScope(scope)
        }
        val filtered = if (type != null) links.filter { it.type == type } else links
        val ids = filtered.flatMap { listOf(it.sourceId, it.targetId) }.distinct()
        val contentById = if (ids.isEmpty()) emptyMap() else memoryDAO.getMemoriesByIds(ids).associateBy { it.id }
        return filtered.map { link ->
            link.toModel(
                sourceContent = contentById[link.sourceId]?.content.orEmpty(),
                targetContent = contentById[link.targetId]?.content.orEmpty(),
            )
        }
    }

    /** 删边（校验 scope） */
    suspend fun unlink(scope: String, linkId: Long) {
        val link = memoryLinkDAO.getById(linkId) ?: error("Memory link #$linkId not found")
        require(link.scope == scope) { "Memory link is not in the requested scope" }
        memoryLinkDAO.deleteById(linkId)
        enqueueLinkBundleSync()
    }

    /**
     * 多跳邻域 BFS（P2 图传播检索的基座）。
     * 返回去重后的邻居节点 id（不含起点），maxHops ≥ 1。
     */
    suspend fun getNeighbors(scope: String, memoryId: Int, maxHops: Int = 1): List<Int> {
        require(maxHops >= 1) { "maxHops must be >= 1" }
        val visited = mutableSetOf(memoryId)
        var frontier = setOf(memoryId)
        repeat(maxHops) {
            val next = mutableSetOf<Int>()
            for (id in frontier) {
                memoryLinkDAO.getLinksOfMemory(scope, id).forEach { link ->
                    if (link.sourceId != id) next.add(link.sourceId)
                    if (link.targetId != id) next.add(link.targetId)
                }
            }
            next.removeAll(visited)
            visited.addAll(next)
            frontier = next
            if (frontier.isEmpty()) return@repeat
        }
        return (visited - memoryId).toList()
    }

    private fun MemoryLinkEntity.toModel(sourceContent: String = "", targetContent: String = "") = MemoryLink(
        id = id,
        sourceId = sourceId,
        sourceContent = sourceContent,
        targetId = targetId,
        targetContent = targetContent,
        type = type,
        weight = weight,
        description = description,
        scope = scope,
    )

    /** 节点主色（图谱可视化） */
    private fun nodeColor(scope: String): Color =
        if (scope == GLOBAL_MEMORY_ID) Color(0xFFE8554F) else Color(0xFF4F8EF7)

    /**
     * 记忆图谱（P4 可视化）：以 scope 内全部记忆为节点、链接为有向边构图。
     * Node.metadata 携带完整内容（点击查看详情）；Edge.metadata 携带关系描述。
     */
    suspend fun getMemoryGraph(scope: String): Graph = withContext(Dispatchers.IO) {
        val memories = memoryDAO.getMemoriesOfAssistant(scope)
        val links = memoryLinkDAO.getLinksOfScope(scope)
        buildGraph(scope, memories, links)
    }

    /**
     * 检索结果子图（P4 收尾，对齐 Operit getGraphForMemories）：
     * 以指定记忆为种子，扩展一跳邻居后构图 —— 子图 = 检索结果 ∪ 直接邻居，
     * 边 = 集合内节点之间的全部链接。
     */
    suspend fun getGraphForMemories(scope: String, memoryIds: List<Int>): Graph = withContext(Dispatchers.IO) {
        if (memoryIds.isEmpty()) return@withContext Graph(nodes = emptyList(), edges = emptyList())
        val seedIds = memoryIds.distinct()
        val neighborIds = seedIds.flatMap { memoryLinkDAO.getLinksOfMemory(scope, it) }
            .flatMap { listOf(it.sourceId, it.targetId) }
            .filter { it !in seedIds }
        val nodeIds = (seedIds + neighborIds).distinct()
        val memories = memoryDAO.getMemoriesByIds(nodeIds).associateBy { it.id }
        val links = memoryLinkDAO.getLinksOfScope(scope).filter { it.sourceId in nodeIds && it.targetId in nodeIds }
        buildGraph(scope, memories.values.toList(), links)
    }

    private fun buildGraph(
        scope: String,
        memories: List<MemoryEntity>,
        links: List<MemoryLinkEntity>,
    ): Graph {
        val nodes = memories.map { m ->
            val firstLine = m.content.lineSequence().firstOrNull()?.trim().orEmpty()
            Node(
                id = m.id.toString(),
                label = firstLine.ifEmpty { "#${m.id}" }.let {
                    if (it.length > 24) it.take(24) + "…" else it
                },
                color = nodeColor(scope),
                metadata = mapOf(
                    "memoryId" to m.id.toString(),
                    "content" to m.content,
                ),
            )
        }
        val edges = links.map { l ->
            Edge(
                id = l.id,
                sourceId = l.sourceId.toString(),
                targetId = l.targetId.toString(),
                label = l.type,
                weight = l.weight,
                metadata = mapOf(
                    "linkId" to l.id.toString(),
                    "type" to l.type,
                    "description" to l.description,
                ),
            )
        }
        return Graph(nodes = nodes, edges = edges)
    }
}
