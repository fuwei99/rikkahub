package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryGraphLinkDAO
import me.rerere.rikkahub.data.db.dao.MemoryGraphNodeDAO
import me.rerere.rikkahub.data.db.entity.MemoryGraphLinkEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphNodeEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.model.MemoryGraphData
import me.rerere.rikkahub.data.model.MemoryGraphLink
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.model.MemoryGraphSearchHit
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY_GRAPH_LINKS
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY_GRAPH_NODES
import me.rerere.rikkahub.data.sync.core.SyncApplyGate

/**
 * 独立记忆图仓库（与 legacy MemoryEntity / MemoryRepository 完全解耦）。
 *
 * - 只读写 memory_graph_node / memory_graph_link 两张独立表；
 * - 图谱检索/自动提炼/图可视化只走这里，绝不触碰传统记忆表；
 * - 传统记忆的开关、注入、编辑均不受记忆图影响。
 */
class MemoryGraphRepository(
    private val nodeDAO: MemoryGraphNodeDAO,
    private val linkDAO: MemoryGraphLinkDAO,
    private val database: AppDatabase,
) {
    companion object {
        const val GLOBAL_SCOPE = "__global__"
        val LINK_TYPES = listOf(
            "related", "follows", "corrects", "updates",
            "involves", "happens_at", "part_of", "allied_with", "opposes",
        )
    }

    /** 云锚点同步写钩：图谱整表 bundle 入待推队列（与 legacy 记忆 bundle 分开，互不影响） */
    private suspend fun enqueueBundleSync(key: String) {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, key)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = key,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun enqueueNodeSync() = enqueueBundleSync(BUNDLE_MEMORY_GRAPH_NODES)
    private suspend fun enqueueLinkSync() = enqueueBundleSync(BUNDLE_MEMORY_GRAPH_LINKS)

    // ---------------- 节点 CRUD ----------------

    fun getNodesFlow(scope: String): Flow<List<MemoryGraphNode>> =
        nodeDAO.getByScopeFlow(scope).map { it.map { node -> node.toModel() } }

    suspend fun getNodes(scope: String): List<MemoryGraphNode> =
        nodeDAO.getByScope(scope).map { it.toModel() }

    suspend fun getNode(id: Long): MemoryGraphNode? = nodeDAO.getById(id)?.toModel()

    suspend fun getNodesByIds(ids: List<Long>): Map<Long, MemoryGraphNode> =
        nodeDAO.getByIds(ids).associateBy { it.id }.mapValues { it.value.toModel() }

    suspend fun createNode(
        scope: String,
        title: String,
        content: String,
        importance: Float = 0.5f,
        credibility: Float = 0.5f,
        folderPath: String? = null,
        sourceConversationId: String? = null,
    ): MemoryGraphNode {
        require(title.isNotBlank()) { "title is required" }
        val id = nodeDAO.insert(
            MemoryGraphNodeEntity(
                scope = scope,
                title = title.trim(),
                content = content,
                importance = importance.coerceIn(0f, 1f),
                credibility = credibility.coerceIn(0f, 1f),
                folderPath = folderPath,
                sourceConversationId = sourceConversationId,
            )
        )
        enqueueNodeSync()
        return nodeDAO.getById(id)?.toModel()
            ?: error("Memory graph node #$id not found after insert")
    }

    suspend fun updateNode(
        scope: String,
        id: Long,
        title: String? = null,
        content: String? = null,
        importance: Float? = null,
        credibility: Float? = null,
        folderPath: String? = null,
    ): MemoryGraphNode {
        val old = nodeDAO.getById(id) ?: error("Memory graph node #$id not found")
        require(old.scope == scope) { "node #$id is not in the requested scope" }
        val updated = old.copy(
            title = title?.trim() ?: old.title,
            content = content ?: old.content,
            importance = importance?.coerceIn(0f, 1f) ?: old.importance,
            credibility = credibility?.coerceIn(0f, 1f) ?: old.credibility,
            folderPath = folderPath ?: old.folderPath,
            updatedAt = System.currentTimeMillis(),
        )
        nodeDAO.update(updated)
        enqueueNodeSync()
        return updated.toModel()
    }

    suspend fun deleteNode(scope: String, id: Long) {
        val node = nodeDAO.getById(id)
        if (node != null) {
            require(node.scope == scope) { "node #$id is not in the requested scope" }
            linkDAO.getByNode(scope, id).forEach { linkDAO.deleteById(it.id) }
        }
        nodeDAO.deleteById(id)
        enqueueNodeSync()
        enqueueLinkSync()
    }

    // ---------------- 边 CRUD ----------------

    suspend fun linkNodes(
        scope: String,
        sourceId: Long,
        targetId: Long,
        type: String = "related",
        weight: Float = 0.7f,
        description: String = "",
    ): MemoryGraphLink {
        require(sourceId != targetId) { "source_id and target_id must be different" }
        val source = nodeDAO.getById(sourceId) ?: error("Memory graph node #$sourceId not found")
        require(source.scope == scope) { "source node is not in the requested scope" }
        val target = nodeDAO.getById(targetId) ?: error("Memory graph node #$targetId not found")
        require(target.scope == scope) { "target node is not in the requested scope" }

        val existing = linkDAO.findDuplicate(scope, sourceId, targetId, type)
        if (existing != null) return existing.toModel(source, target)

        val id = linkDAO.insert(
            MemoryGraphLinkEntity(
                scope = scope,
                sourceId = sourceId,
                targetId = targetId,
                type = type,
                weight = weight.coerceIn(0f, 1f),
                description = description,
            )
        )
        enqueueLinkSync()
        return linkDAO.getById(id)?.toModel(source, target)
            ?: error("Memory graph link #$id not found after insert")
    }

    suspend fun updateLink(
        scope: String,
        id: Long,
        type: String? = null,
        weight: Float? = null,
        description: String? = null,
    ): MemoryGraphLink {
        val old = linkDAO.getById(id) ?: error("Memory graph link #$id not found")
        require(old.scope == scope) { "link #$id is not in the requested scope" }
        val updated = old.copy(
            type = type ?: old.type,
            weight = weight?.coerceIn(0f, 1f) ?: old.weight,
            description = description ?: old.description,
            updatedAt = System.currentTimeMillis(),
        )
        linkDAO.update(updated)
        enqueueLinkSync()
        val source = nodeDAO.getById(updated.sourceId)
        val target = nodeDAO.getById(updated.targetId)
        return updated.toModel(source, target)
    }

    suspend fun deleteLink(scope: String, id: Long) {
        val link = linkDAO.getById(id)
        if (link != null) require(link.scope == scope) { "link #$id is not in the requested scope" }
        linkDAO.deleteById(id)
        enqueueLinkSync()
    }

    suspend fun getLinks(scope: String): List<MemoryGraphLink> {
        val nodes = getNodesByIds(linkDAO.getByScope(scope).flatMap { listOf(it.sourceId, it.targetId) })
        return linkDAO.getByScope(scope).map { it.toModelByIds(nodes) }
    }

    suspend fun getLinksOfNode(scope: String, nodeId: Long): List<MemoryGraphLink> {
        val nodes = getNodesByIds(linkDAO.getByNode(scope, nodeId).flatMap { listOf(it.sourceId, it.targetId) })
        return linkDAO.getByNode(scope, nodeId).map { it.toModelByIds(nodes) }
    }

    // ---------------- 图构建 / 检索 ----------------

    /** 全量图（scope 内全部节点 + 边） */
    suspend fun getGraph(scope: String): MemoryGraphData = withContext(Dispatchers.IO) {
        MemoryGraphData(nodes = getNodes(scope), links = getLinks(scope))
    }

    /** 关键词检索（Room LIKE，独立于 legacy FTS；后续可换成 graph FTS5 表） */
    suspend fun searchNodes(query: String, scope: String, topK: Int = 10): List<MemoryGraphSearchHit> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@withContext emptyList()
            val all = nodeDAO.getByScope(scope)
            val scored = all.mapNotNull { node ->
                val title = node.title
                val content = node.content
                var score = 0f
                tokens.forEach { token ->
                    val tk = token.lowercase()
                    if (title.contains(tk, ignoreCase = true)) score += 3f
                    if (content.contains(tk, ignoreCase = true)) score += 1f
                }
                if (score > 0f) MemoryGraphSearchHit(node.toModel(), score) else null
            }
            scored.sortedByDescending { it.score }.take(topK)
        }
    }

    /** 单跳/多跳 BFS 邻居（图传播检索基座），返回去重节点 id（不含起点） */
    suspend fun getNeighbors(scope: String, nodeId: Long, maxHops: Int = 1): List<Long> {
        require(maxHops >= 1)
        val visited = mutableSetOf(nodeId)
        var frontier = setOf(nodeId)
        repeat(maxHops) {
            val next = mutableSetOf<Long>()
            for (id in frontier) {
                linkDAO.getByNode(scope, id).forEach { link ->
                    if (link.sourceId != id) next.add(link.sourceId)
                    if (link.targetId != id) next.add(link.targetId)
                }
            }
            next.removeAll(visited)
            visited.addAll(next)
            frontier = next
            if (frontier.isEmpty()) return@repeat
        }
        return (visited - nodeId).toList()
    }

    /** 检索结果子图：命中节点 + 一跳邻居（对齐 Operit getGraphForMemories） */
    suspend fun getGraphForNodes(scope: String, seedIds: List<Long>): MemoryGraphData = withContext(Dispatchers.IO) {
        if (seedIds.isEmpty()) return@withContext MemoryGraphData()
        val ids = seedIds.distinct()
        val neighborIds = ids.flatMap { linkDAO.getByNode(scope, it) }
            .flatMap { listOf(it.sourceId, it.targetId) }
            .filter { it !in ids }
        val nodeIds = (ids + neighborIds).toSet()
        val nodes = getNodesByIds(nodeIds.toList()).values.toList()
        val links = linkDAO.getByScope(scope)
            .filter { it.sourceId in nodeIds && it.targetId in nodeIds }
            .let { linkEntities ->
                val nodeMap = nodes.associateBy { it.id }
                linkEntities.map { l ->
                    MemoryGraphLink(
                        id = l.id,
                        scope = l.scope,
                        sourceId = l.sourceId,
                        targetId = l.targetId,
                        sourceTitle = nodeMap[l.sourceId]?.title.orEmpty(),
                        targetTitle = nodeMap[l.targetId]?.title.orEmpty(),
                        type = l.type,
                        weight = l.weight,
                        description = l.description,
                    )
                }
            }
        MemoryGraphData(nodes = nodes, links = links)
    }

    // ---------------- 抽取器专用 ----------------

    suspend fun findByTitle(scope: String, title: String): MemoryGraphNode? =
        nodeDAO.findByTitle(scope, title.trim())?.toModel()

    suspend fun findAllByTitle(scope: String, title: String): List<MemoryGraphNode> =
        nodeDAO.findAllByTitle(scope, title.trim()).map { it.toModel() }

    suspend fun mergeNodes(
        scope: String,
        sourceTitles: List<String>,
        newTitle: String,
        newContent: String,
        folderPath: String? = null,
    ): MemoryGraphNode? {
        val sources = sourceTitles.mapNotNull { nodeDAO.findByTitle(scope, it.trim()) }.distinctBy { it.id }
        if (sources.isEmpty()) return null
        val merged = database.withTransaction {
            val mergedId = nodeDAO.insert(
                MemoryGraphNodeEntity(
                    scope = scope,
                    title = newTitle.trim(),
                    content = newContent,
                    importance = 0.6f,
                    credibility = 0.8f,
                    folderPath = folderPath,
                )
            )
            val mergedNode = nodeDAO.getById(mergedId) ?: error("merged node not found")
            val sourceIds = sources.map { it.id }.toSet()
            linkDAO.getByScope(scope).forEach { link ->
                if (link.sourceId !in sourceIds && link.targetId !in sourceIds) return@forEach
                val newSource = if (link.sourceId in sourceIds) mergedNode.id else link.sourceId
                val newTarget = if (link.targetId in sourceIds) mergedNode.id else link.targetId
                linkDAO.deleteById(link.id)
                if (newSource != newTarget && linkDAO.findDuplicate(scope, newSource, newTarget, link.type) == null) {
                    linkDAO.insert(
                        link.copy(
                            id = 0,
                            sourceId = newSource,
                            targetId = newTarget,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            sources.forEach { nodeDAO.deleteById(it.id) }
            mergedNode.toModel()
        }
        enqueueNodeSync()
        enqueueLinkSync()
        return merged
    }

    suspend fun deleteNodes(scope: String, ids: List<Long>) {
        val validIds = nodeDAO.getByIds(ids).filter { it.scope == scope }.map { it.id }
        validIds.forEach { linkDAO.getByNode(scope, it).forEach { linkDAO.deleteById(link.id) } }
        validIds.forEach { nodeDAO.deleteById(it) }
        enqueueNodeSync()
        enqueueLinkSync()
    }

    suspend fun deleteScope(scope: String) {
        nodeDAO.deleteByScope(scope)
        linkDAO.deleteByScope(scope)
        enqueueNodeSync()
        enqueueLinkSync()
    }

    // ---------------- 转换 ----------------

    private fun MemoryGraphNodeEntity.toModel() = MemoryGraphNode(
        id = id,
        scope = scope,
        title = title,
        content = content,
        importance = importance,
        credibility = credibility,
        folderPath = folderPath,
    )

    private fun MemoryGraphLinkEntity.toModel(source: MemoryGraphNodeEntity?, target: MemoryGraphNodeEntity?) = MemoryGraphLink(
        id = id,
        scope = scope,
        sourceId = sourceId,
        targetId = targetId,
        sourceTitle = source?.title ?: "#$sourceId",
        targetTitle = target?.title ?: "#$targetId",
        type = type,
        weight = weight,
        description = description,
    )

    private fun MemoryGraphLinkEntity.toModelByIds(nodes: Map<Long, MemoryGraphNode>) = MemoryGraphLink(
        id = id,
        scope = scope,
        sourceId = sourceId,
        targetId = targetId,
        sourceTitle = nodes[sourceId]?.title ?: "#$sourceId",
        targetTitle = nodes[targetId]?.title ?: "#$targetId",
        type = type,
        weight = weight,
        description = description,
    )
}
