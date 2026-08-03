package me.rerere.rikkahub.data.repository

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        Graph(nodes = nodes, edges = edges)
    }
}
