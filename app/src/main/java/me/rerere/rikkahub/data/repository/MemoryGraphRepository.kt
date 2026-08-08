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
import me.rerere.rikkahub.data.model.MemoryGraphMatchEligibility
import me.rerere.rikkahub.data.model.MemoryGraphNode
import me.rerere.rikkahub.data.model.MemoryGraphSearchHit
import me.rerere.rikkahub.data.model.MEMORY_GRAPH_UNLOCKS_TYPE
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY_GRAPH_LINKS
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY_GRAPH_NODES
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
import me.rerere.rikkahub.data.vector.GraphVectorStore
import me.rerere.common.android.MemoryGraphDebugLog

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
    private val graphVectorStore: GraphVectorStore,
) {
    companion object {
        const val GLOBAL_SCOPE = "__global__"
        /** 推荐 link type（模型/UI 可选），`unlocks` 是系统保留 type，不在推荐之列。 */
        val LINK_TYPES = listOf(
            "related", "follows", "corrects", "updates",
            "involves", "happens_at", "part_of", "allied_with", "opposes",
        )
        /** 系统保留 link type：解锁边。source 节点命中时 target（gated）解锁参与匹配。 */
        const val UNLOCKS_TYPE = MEMORY_GRAPH_UNLOCKS_TYPE
        private const val TAG = "MemoryGraphRepository"
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
        matchEligibility: Int = MemoryGraphMatchEligibility.ALWAYS,
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
                matchEligibility = matchEligibility,
                folderPath = folderPath,
                sourceConversationId = sourceConversationId,
            )
        )
        enqueueNodeSync()
        graphVectorStore.markDirty(scope)
        return nodeDAO.getById(id)?.toModel()
            ?: error("Memory graph node #$id not found after insert")
    }

    suspend fun updateNode(
        scope: String,
        id: Long,
        title: String? = null,
        content: String? = null,
        importance: Float? = null,
        matchEligibility: Int? = null,
        folderPath: String? = null,
    ): MemoryGraphNode {
        val old = nodeDAO.getById(id) ?: error("Memory graph node #$id not found")
        require(old.scope == scope) { "node #$id is not in the requested scope" }
        val updated = old.copy(
            title = title?.trim() ?: old.title,
            content = content ?: old.content,
            importance = importance?.coerceIn(0f, 1f) ?: old.importance,
            matchEligibility = matchEligibility ?: old.matchEligibility,
            folderPath = folderPath ?: old.folderPath,
            updatedAt = System.currentTimeMillis(),
        )
        nodeDAO.update(updated)
        enqueueNodeSync()
        graphVectorStore.markDirty(scope)
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
        graphVectorStore.markDirty(scope)
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
        // 一次查询复用：原实现对同一 scope 调了两次 getByScope（多图后每轮开销 ×N）
        val links = linkDAO.getByScope(scope)
        val nodes = getNodesByIds(links.flatMap { listOf(it.sourceId, it.targetId) })
        return links.map { it.toModelByIds(nodes) }
    }

    suspend fun getLinksOfNode(scope: String, nodeId: Long): List<MemoryGraphLink> {
        val links = linkDAO.getByNode(scope, nodeId)
        val nodes = getNodesByIds(links.flatMap { listOf(it.sourceId, it.targetId) })
        return links.map { it.toModelByIds(nodes) }
    }

    // ---------------- 图构建 / 检索 ----------------

    /** 全量图（scope 内全部节点 + 边） */
    suspend fun getGraph(scope: String): MemoryGraphData = withContext(Dispatchers.IO) {
        MemoryGraphData(nodes = getNodes(scope), links = getLinks(scope))
    }

    /**
     * 关键词检索（独立于 legacy FTS；后续可换成 graph FTS5 表）。
     *
     * @param eligibleNodeIds 匹配资格门：非 null 时只在这组节点内打分（常驻池/已解锁集），
     *   gated 未解锁节点物理上不参与匹配；null = 不过滤（UI 浏览 / memory_tool 全池查询）。
     */
    suspend fun searchNodes(
        query: String,
        scope: String,
        topK: Int = 10,
        eligibleNodeIds: Set<Long>? = null,
    ): List<MemoryGraphSearchHit> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            // 中文查询通常没有空格。仅按空白切分会把“我是程天赢，你记得我吗”
            // 当成一个完整 token，因而匹配不到“程天赢 | 基本身份”。
            val tokens = tokenizeForSearch(query)
            if (tokens.isEmpty()) return@withContext emptyList()
            val all = nodeDAO.getByScope(scope)
            MemoryGraphDebugLog.i(TAG, "searchNodes: scope=$scope totalNodes=${all.size} " +
                "tokens=${tokens.joinToString(",") { it.take(12) }}")
            val scored = all.mapNotNull { node ->
                if (eligibleNodeIds != null && node.id !in eligibleNodeIds) return@mapNotNull null
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
            val result = scored.sortedByDescending { it.score }.take(topK)
            MemoryGraphDebugLog.i(TAG, "searchNodes: scope=$scope hit=${result.size} " +
                "titles=${result.joinToString(",") { it.node.title.take(20) + ":" + String.format(java.util.Locale.US, "%.1f", it.score) }}")
            result
        }
    }

    /**
     * 门控池轻量匹配：对给定节点集（已解锁的 gated 节点）按 query 打分排序。
     * 与 [searchNodes] 共用打分规则，但只扫传入的 id 集 —— 解锁后「参与后续匹配」
     * 的这一轮就落在这里。返回的节点保证都在 [nodeIds] 内。
     */
    suspend fun scoreNodesByQuery(
        query: String,
        scope: String,
        nodeIds: Collection<Long>,
        topK: Int = 10,
    ): List<MemoryGraphSearchHit> {
        if (query.isBlank() || nodeIds.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val tokens = tokenizeForSearch(query)
            if (tokens.isEmpty()) return@withContext emptyList()
            val target = nodeIds.toSet()
            val scored = nodeDAO.getByIds(nodeIds.toList()).mapNotNull { node ->
                if (node.scope != scope || node.id !in target) return@mapNotNull null
                var score = 0f
                tokens.forEach { token ->
                    val tk = token.lowercase()
                    if (node.title.contains(tk, ignoreCase = true)) score += 3f
                    if (node.content.contains(tk, ignoreCase = true)) score += 1f
                }
                if (score > 0f) MemoryGraphSearchHit(node.toModel(), score) else null
            }
            MemoryGraphDebugLog.i(
                TAG,
                "scoreNodesByQuery: scope=$scope pool=${target.size} hit=${scored.size} " +
                    "titles=${scored.joinToString(",", limit = 8) { it.node.title.take(20) }}"
            )
            scored.sortedByDescending { it.score }.take(topK)
        }
    }

    /**
     * 解锁边解析：给定当前激活节点集，返回它们通过 `unlocks` 边解锁的 gated 节点 id。
     * 激活语义 OR —— 任一 unlocker 命中即解锁（AND 太容易死锁）。
     */
    suspend fun getUnlockedNodeIds(scope: String, activeNodeIds: Collection<Long>): Set<Long> {
        if (activeNodeIds.isEmpty()) return emptySet()
        val active = activeNodeIds.toSet()
        return withContext(Dispatchers.IO) {
            linkDAO.getByScope(scope)
                .asSequence()
                .filter { it.type == UNLOCKS_TYPE && it.sourceId in active }
                .map { it.targetId }
                .toSet()
        }
    }

    /** 常驻池节点 id（match_eligibility = ALWAYS），注入检索第一轮只在池内扫。 */
    suspend fun getAlwaysEligibleNodeIds(scope: String): Set<Long> =
        withContext(Dispatchers.IO) {
            nodeDAO.getByScope(scope)
                .asSequence()
                .filter { it.matchEligibility == MemoryGraphMatchEligibility.ALWAYS }
                .map { it.id }
                .toSet()
        }

    /** 全部 gated 节点 id（锁池），供目录标注 / UI 展示用。 */
    suspend fun getGatedNodeIds(scope: String): Set<Long> =
        withContext(Dispatchers.IO) {
            nodeDAO.getByScope(scope)
                .asSequence()
                .filter { it.matchEligibility == MemoryGraphMatchEligibility.GATED }
                .map { it.id }
                .toSet()
        }

    /** 面向中英文混合查询的轻量分词。 */
    private fun tokenizeForSearch(query: String): List<String> {
        val result = LinkedHashSet<String>()
        Regex("[\\p{IsHan}]+|[A-Za-z0-9_]+")
            .findAll(query)
            .forEach { match ->
                val token = match.value.lowercase()
                if (token.any { it.code in 0x4E00..0x9FFF }) {
                    // 保留完整中文片段，并生成 2~4 字 n-gram，覆盖自然语言中的人名/短语。
                    result += token
                    for (size in 2..4) {
                        if (token.length >= size) {
                            for (start in 0..token.length - size) {
                                result += token.substring(start, start + size)
                            }
                        }
                    }
                } else {
                    result += token
                }
            }
        return result.toList()
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

    /** 检索结果子图：命中节点 + maxHops 跳邻居（0 = 只返回命中节点；默认一跳，对齐 Operit getGraphForMemories） */
    suspend fun getGraphForNodes(
        scope: String,
        seedIds: List<Long>,
        maxHops: Int = 1,
    ): MemoryGraphData = withContext(Dispatchers.IO) {
        if (seedIds.isEmpty()) return@withContext MemoryGraphData()
        val ids = seedIds.distinct()
        // 0 跳：repeat(0) 不执行，neighborIds 为空，即只返回种子节点及种子之间的边
        val hops = maxHops.coerceIn(0, 5)
        // 逐层 BFS 扩展，跳数由设置控制；已访问节点不再重复展开。
        val visited = ids.toMutableSet()
        var frontier: Set<Long> = ids.toSet()
        var hopsDone = 0
        while (hopsDone < hops && frontier.isNotEmpty()) {
            val next = mutableSetOf<Long>()
            frontier.forEach { id ->
                linkDAO.getByNode(scope, id).forEach { link ->
                    if (link.sourceId !in visited) next.add(link.sourceId)
                    if (link.targetId !in visited) next.add(link.targetId)
                }
            }
            if (next.isEmpty()) break // 无新邻居：提前结束，不再空转剩余跳数
            visited.addAll(next)
            frontier = next
            hopsDone++
        }
        val neighborIds = (visited - ids.toSet()).toList()
        val nodeIds = (ids + neighborIds).toSet()
        val nodes = getNodesByIds(nodeIds.toList()).values.filter { it.scope == scope }
        val scopedNodeIds = nodes.map { it.id }.toSet()
        val links = linkDAO.getByScope(scope)
            .filter { it.sourceId in scopedNodeIds && it.targetId in scopedNodeIds }
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
                    // 任一 source 常驻则合并产物常驻；全 gated 保持 gated（不因合并泄漏进匹配池）
                    matchEligibility = if (sources.any { it.matchEligibility == MemoryGraphMatchEligibility.ALWAYS }) {
                        MemoryGraphMatchEligibility.ALWAYS
                    } else {
                        MemoryGraphMatchEligibility.GATED
                    },
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
        graphVectorStore.markDirty(scope)
        return merged
    }

    suspend fun deleteNodes(scope: String, ids: List<Long>) {
        val validIds = nodeDAO.getByIds(ids).filter { it.scope == scope }.map { it.id }
        validIds.forEach { id -> linkDAO.getByNode(scope, id).forEach { link -> linkDAO.deleteById(link.id) } }
        validIds.forEach { nodeDAO.deleteById(it) }
        if (validIds.isNotEmpty()) graphVectorStore.markDirty(scope)
        enqueueNodeSync()
        enqueueLinkSync()
    }

    suspend fun deleteScope(scope: String) {
        nodeDAO.deleteByScope(scope)
        linkDAO.deleteByScope(scope)
        graphVectorStore.markDirty(scope)
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
        matchEligibility = matchEligibility,
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
