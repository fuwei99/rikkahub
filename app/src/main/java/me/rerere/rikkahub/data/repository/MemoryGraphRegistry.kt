package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.common.android.MemoryGraphDebugLog
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryGraphDAO
import me.rerere.rikkahub.data.db.entity.MemoryGraphEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.model.MemoryGraphCreator
import me.rerere.rikkahub.data.model.MemoryGraphKind
import me.rerere.rikkahub.data.model.MemoryGraphMeta
import me.rerere.rikkahub.data.sync.core.BUNDLE_MEMORY_GRAPHS
import me.rerere.rikkahub.data.sync.core.SyncApplyGate
import me.rerere.rikkahub.data.vector.GraphVectorStore
import kotlin.uuid.Uuid

/**
 * 记忆图注册表仓库（方案 2026-08-07 阶段一）。
 *
 * 职责边界：只管「有哪些图、图叫什么、谁能改」，图里的节点/边仍然全部走
 * [MemoryGraphRepository]（它本来就是 scope 参数化的，一行不用改）。
 *
 * ID 分层（review §3）：
 * - [MemoryGraphMeta.id] = canonical id = 节点表 scope，是唯一在链路里传递的标识；
 * - [MemoryGraphMeta.slug] 只是用户 / tool 的可读引用；
 * - `assistant` / `global` 别名只在 [resolve] 入口兼容解析。
 * resolve 之后不允许再把 slug / 别名往下传。
 */
class MemoryGraphRegistry(
    private val dao: MemoryGraphDAO,
    private val database: AppDatabase,
    private val graphRepo: MemoryGraphRepository,
    private val graphVectorStore: GraphVectorStore,
) {
    companion object {
        private const val TAG = "MemoryGraphRegistry"

        /** 向后兼容别名：老会话 tool call、老 prompt tag、legacy scope 参数继续有效 */
        const val ALIAS_ASSISTANT = "assistant"
        const val ALIAS_GLOBAL = "global"

        /** 图数量护栏：总数与 AI 自建数各自硬上限 */
        const val MAX_GRAPHS = 64
        const val MAX_AI_GRAPHS = 16

        private const val GLOBAL_SLUG = "global"

        /** 内置助手图通用默认名：迁移种子/缺助手名时用；名字仍停在这个值时会被同步成助手名 */
        const val DEFAULT_ASSISTANT_GRAPH_NAME = "助手记忆图"
    }

    // ---------------- 读 ----------------

    fun listFlow(): Flow<List<MemoryGraphMeta>> =
        dao.getAllFlow().map { list -> list.map { it.toMeta() } }

    suspend fun list(): List<MemoryGraphMeta> = withContext(Dispatchers.IO) {
        dao.getAll().map { it.toMeta() }
    }

    suspend fun get(id: String): MemoryGraphMeta? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toMeta()
    }

    suspend fun nodeCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        runCatching { dao.nodeCounts().associate { it.scope to it.count } }.getOrDefault(emptyMap())
    }

    /**
     * 引用解析：id 精确 → slug → 别名（assistant / global）→ null。
     *
     * @param assistantId 解析 `assistant` 别名所需的宿主助手 id；为 null 时该别名不可用。
     */
    suspend fun resolve(ref: String, assistantId: String?): MemoryGraphMeta? = withContext(Dispatchers.IO) {
        val key = ref.trim()
        if (key.isBlank()) return@withContext null
        dao.getById(key)?.let { return@withContext it.toMeta() }
        dao.getBySlug(key)?.let { return@withContext it.toMeta() }
        when (key.lowercase()) {
            ALIAS_ASSISTANT -> assistantId?.let { ensureAssistantGraph(it) }
            ALIAS_GLOBAL -> ensureGlobalGraph()
            else -> null
        }
    }

    // ---------------- 内置图懒创建（getOrCreate 语义，幂等） ----------------

    suspend fun ensureGlobalGraph(): MemoryGraphMeta = withContext(Dispatchers.IO) {
        val id = MemoryGraphRepository.GLOBAL_SCOPE
        dao.getById(id)?.let { return@withContext normalizeSlugIfNeeded(it, GLOBAL_SLUG).toMeta() }
        val now = System.currentTimeMillis()
        val entity = MemoryGraphEntity(
            id = id,
            slug = uniqueSlug(GLOBAL_SLUG, reserveAliases = false),
            name = "全局记忆图",
            description = "跨助手共享的全局记忆图",
            kind = MemoryGraphKind.GLOBAL.name,
            builtin = true,
            createdBy = MemoryGraphCreator.USER.name,
            autoExtractTarget = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertIgnore(entity)
        enqueueSync()
        (dao.getById(id) ?: entity).toMeta()
    }

    suspend fun ensureAssistantGraph(assistantId: String, assistantName: String? = null): MemoryGraphMeta = withContext(Dispatchers.IO) {
        dao.getById(assistantId)?.let {
            // 迁移期 slug 直接写成了 scope 全值，这里在首次使用时规范化成可读短名
            val normalized = normalizeSlugIfNeeded(it, "assistant_${assistantId.take(8)}")
            // 内置助手图名字还是通用默认名时同步成助手名：多助手时一排「助手记忆图」根本分不清谁是谁
            return@withContext syncAssistantGraphName(normalized, assistantName).toMeta()
        }
        val now = System.currentTimeMillis()
        val entity = MemoryGraphEntity(
            id = assistantId,
            slug = uniqueSlug("assistant_${assistantId.take(8)}"),
            name = assistantName?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_ASSISTANT_GRAPH_NAME,
            description = "该助手专属的记忆图",
            kind = MemoryGraphKind.ASSISTANT.name,
            boundAssistantId = assistantId,
            builtin = true,
            createdBy = MemoryGraphCreator.USER.name,
            // 老行为：自动提炼恒定写助手图，故助手图默认就是提炼落点
            autoExtractTarget = true,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertIgnore(entity)
        enqueueSync()
        (dao.getById(assistantId) ?: entity).toMeta()
    }

    // ---------------- 写 ----------------

    suspend fun create(
        name: String,
        description: String,
        emoji: String? = null,
        createdBy: MemoryGraphCreator = MemoryGraphCreator.USER,
        sortOrder: Int = 0,
    ): MemoryGraphMeta = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "name is required" }
        // 空描述的图在多图选择阶段等于永不被召回，故强制必填；校验 trim 后的值，
        // 避免纯空格描述绕过护栏并把不可选的图写入注册表。
        require(description.trim().isNotBlank()) {
            "description is required (it is the only basis for graph selection)"
        }
        require(dao.count() < MAX_GRAPHS) { "too many memory graphs (max $MAX_GRAPHS)" }
        if (createdBy == MemoryGraphCreator.AI) {
            require(dao.countByCreator(MemoryGraphCreator.AI.name) < MAX_AI_GRAPHS) {
                "too many AI-created memory graphs (max $MAX_AI_GRAPHS)"
            }
        }
        val now = System.currentTimeMillis()
        val entity = MemoryGraphEntity(
            id = Uuid.random().toString(),
            slug = uniqueSlug(slugify(name)),
            name = name.trim(),
            description = description.trim(),
            kind = MemoryGraphKind.CUSTOM.name,
            emoji = emoji?.takeIf { it.isNotBlank() },
            builtin = false,
            createdBy = createdBy.name,
            sortOrder = sortOrder,
            autoExtractTarget = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        enqueueSync()
        MemoryGraphDebugLog.i(TAG, "create: id=${entity.id} slug=${entity.slug} name=${entity.name} by=$createdBy")
        entity.toMeta()
    }

    suspend fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        emoji: String? = null,
        sortOrder: Int? = null,
        autoExtractTarget: Boolean? = null,
    ): MemoryGraphMeta? = withContext(Dispatchers.IO) {
        val old = dao.getById(id) ?: return@withContext null
        if (description != null) {
            require(description.trim().isNotBlank()) { "description is required (it is the only basis for graph selection)" }
        }
        // 提炼落点是单选：置 true 前先把其它图清掉
        if (autoExtractTarget == true) dao.clearAutoExtractTargets()
        val updated = old.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: old.name,
            description = description?.trim() ?: old.description,
            emoji = emoji ?: old.emoji,
            sortOrder = sortOrder ?: old.sortOrder,
            autoExtractTarget = autoExtractTarget ?: old.autoExtractTarget,
            updatedAt = System.currentTimeMillis(),
        )
        dao.update(updated)
        enqueueSync()
        updated.toMeta()
    }

    /**
     * 删图：builtin 拒删；级联删节点 / 边，并**真删向量索引文件**。
     *
     * `MemoryGraphRepository.deleteScope()` 只 markDirty，不删 idx 文件，
     * 于是重建同名 scope 的图会命中旧向量（review2 §五）。这里补上真删。
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext
        require(!entity.builtin) { "builtin memory graph cannot be deleted, clear its nodes instead" }
        graphRepo.deleteScope(id)
        runCatching { graphVectorStore.deleteScopeIndexes(id) }
            .onFailure { MemoryGraphDebugLog.e(TAG, "delete vector indexes failed for $id", it) }
        dao.deleteById(id)
        enqueueSync()
        MemoryGraphDebugLog.i(TAG, "delete: id=$id slug=${entity.slug}")
    }

    /**
     * 内置助手图名字同步：名字还停在通用默认名（「助手记忆图」）时跟随助手名。
     * 用户改过名就不再等于默认名，不会被覆盖；同步失败保持原状，不影响主流程。
     */
    private suspend fun syncAssistantGraphName(entity: MemoryGraphEntity, assistantName: String?): MemoryGraphEntity {
        val target = assistantName?.trim()?.takeIf { it.isNotBlank() } ?: return entity
        if (entity.name != DEFAULT_ASSISTANT_GRAPH_NAME || entity.name == target) return entity
        return runCatching {
            val updated = entity.copy(name = target, updatedAt = System.currentTimeMillis())
            dao.update(updated)
            enqueueSync()
            MemoryGraphDebugLog.i(TAG, "syncAssistantGraphName: id=${entity.id} \"${entity.name}\" -> \"$target\"")
            updated
        }.getOrDefault(entity)
    }

    /** 清空图内容但保留注册记录（内置图的「删除」等价物）。 */
    suspend fun clear(id: String) = withContext(Dispatchers.IO) {
        graphRepo.deleteScope(id)
        runCatching { graphVectorStore.deleteScopeIndexes(id) }
    }

    /**
     * 云同步孤儿自愈：节点表里有 scope 但注册表里没有记录时补一条 CUSTOM，
     * 杜绝「节点在但图不见了」的孤儿态。
     */
    suspend fun healOrphanScopes(): Int = withContext(Dispatchers.IO) {
        val orphans = runCatching { dao.orphanNodeScopes() }.getOrDefault(emptyList())
        if (orphans.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        orphans.forEach { scope ->
            runCatching {
                dao.insertIgnore(
                    MemoryGraphEntity(
                        id = scope,
                        slug = uniqueSlug("graph_${scope.take(8)}"),
                        name = "未命名图(${scope.take(8)})",
                        description = "云同步恢复的记忆图，请补充描述",
                        kind = MemoryGraphKind.CUSTOM.name,
                        builtin = false,
                        createdBy = MemoryGraphCreator.USER.name,
                        autoExtractTarget = false,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }
        }
        MemoryGraphDebugLog.i(TAG, "healOrphanScopes: recovered=${orphans.size} scopes=${orphans.joinToString(",")}")
        orphans.size
    }

    // ---------------- 同步 ----------------

    /** 整表快照 bundle 入待推队列（与 nodes / links 同构，见 review2 §三）。 */
    private suspend fun enqueueSync() {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_MEMORY_GRAPHS)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_MEMORY_GRAPHS,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ---------------- slug ----------------

    private fun slugify(raw: String): String {
        val base = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "_")
            .trim('_')
        return base.ifBlank { "graph" }.take(32)
    }

    /** slug 上有 UNIQUE 索引，且 assistant/global 是保留别名，冲突时补数字后缀。 */
    private suspend fun uniqueSlug(base: String, reserveAliases: Boolean = true): String {
        var seed = slugify(base)
        if (reserveAliases && seed.lowercase() in setOf(ALIAS_ASSISTANT, ALIAS_GLOBAL)) {
            seed = "${seed}_graph"
        }
        if (dao.getBySlug(seed) == null) return seed
        for (i in 2..999) {
            val candidate = "${seed.take(28)}_$i"
            if (dao.getBySlug(candidate) == null) return candidate
        }
        return "${seed.take(20)}_${System.currentTimeMillis()}"
    }

    /**
     * 迁移期 slug 被写成了 scope 全值（为了避开 UNIQUE 崩库），
     * 首次使用该图时规范化成可读短名；失败就保持原样，绝不因为改名失败影响主流程。
     */
    private suspend fun normalizeSlugIfNeeded(entity: MemoryGraphEntity, preferred: String): MemoryGraphEntity {
        if (entity.slug != entity.id) return entity
        return runCatching {
            val updated = entity.copy(
                slug = uniqueSlug(
                    preferred,
                    reserveAliases = entity.kind != MemoryGraphKind.GLOBAL.name,
                ),
                updatedAt = System.currentTimeMillis(),
            )
            dao.update(updated)
            enqueueSync()
            updated
        }.getOrDefault(entity)
    }

    // ---------------- 转换 ----------------

    private fun MemoryGraphEntity.toMeta() = MemoryGraphMeta(
        id = id,
        slug = slug,
        name = name,
        description = description,
        kind = MemoryGraphKind.fromWire(kind),
        boundAssistantId = boundAssistantId,
        emoji = emoji,
        builtin = builtin,
        createdBy = MemoryGraphCreator.fromWire(createdBy),
        sortOrder = sortOrder,
        autoExtractTarget = autoExtractTarget,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
