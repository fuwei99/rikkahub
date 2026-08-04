package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.ai.provider.providers.VectorProvider
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.vector.MemoryVectorStore

/**
 * 记忆语义检索（记忆图 Phase 2 语义路）。
 *
 * - embedding 渠道：独立的「向量模型服务」配置区块（`Settings.vectorProviders`，
 *   `Settings.memorySearch` 指定 channelId + modelId + dimension，
 *   火山方舟 Plan/免费、Fireworks、阿里百炼、智谱等 OpenAI 兼容端点零改动接入）；
 * - 索引：HNSW（hnswlib-core，cosine），per-scope 文件，维度变化自动换文件；
 * - 重建：搜索前 lazy 检查（文件缺失或 dirty）→ 全量 embedding 重建（记忆量小，个人量级可接受）；
 * - 失败降级：任何一步失败返回空，调用方自然落到关键词路（FTS5）。
 */
class MemorySemanticSearch(
    private val vectorProvider: VectorProvider,
    private val memoryDAO: MemoryDAO,
    private val vectorStore: MemoryVectorStore,
) {
    private val batchSize = 32

    /**
     * 语义检索：返回按相似度降序的完整记忆（id + content）。
     * 渠道未配置 / embedding 失败 / 索引缺失且构建失败时返回空。
     */
    suspend fun search(
        settings: Settings,
        query: String,
        scope: String,
        topK: Int = 10,
    ): List<AssistantMemory> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val cfg = settings.memorySearch
        val channelId = cfg.embeddingChannelId ?: return@withContext emptyList()
        val modelId = cfg.embeddingModelId ?: return@withContext emptyList()
        val dimension = cfg.embeddingDimension.coerceIn(64, 4096)

        val channel = settings.vectorProviders.firstOrNull { it.id == channelId }
            ?: return@withContext emptyList()
        val model = channel.models.firstOrNull { it.id == modelId && it.type == ModelType.EMBEDDING }
            ?: return@withContext emptyList()

        // 1. 确保索引（缺失 / dirty → 全量重建）
        if (vectorStore.needsRebuild(scope, dimension)) {
            if (!rebuildIndex(settings, channelId, modelId, dimension, scope)) {
                return@withContext emptyList()
            }
        }

        // 2. 编码 query
        val queryVector = embed(settings, channelId, modelId, dimension, listOf(query)).firstOrNull()
            ?: return@withContext emptyList()

        // 3. HNSW 最近邻 + 回表补内容
        val ids = vectorStore.search(scope, queryVector, topK)
        if (ids.isEmpty()) return@withContext emptyList()
        val contentById = memoryDAO.getMemoriesByIds(ids).associateBy { it.id }
        ids.mapNotNull { id ->
            contentById[id]?.let { AssistantMemory(it.id, it.content) }
        }
    }

    private suspend fun rebuildIndex(
        settings: Settings,
        channelId: kotlin.uuid.Uuid,
        modelId: kotlin.uuid.Uuid,
        dimension: Int,
        scope: String,
    ): Boolean = runCatching {
        val memories = memoryDAO.getMemoriesOfAssistant(scope)
            .filter { it.content.isNotBlank() }
        if (memories.isEmpty()) {
            // 空 scope：重建一个空索引，避免每次搜索都重建
            vectorStore.rebuildIndex(scope, dimension, emptyList())
            return@runCatching true
        }
        val vectors = mutableListOf<Pair<Int, FloatArray>>()
        memories.chunked(batchSize).forEach { batch ->
            val batchVectors = embed(settings, channelId, modelId, dimension, batch.map { it.content })
            if (batchVectors.size != batch.size) {
                throw IllegalStateException("embedding batch size mismatch: ${batchVectors.size} != ${batch.size}")
            }
            batch.forEachIndexed { i, memory ->
                vectors.add(memory.id to batchVectors[i])
            }
        }
        vectorStore.rebuildIndex(scope, dimension, vectors)
        true
    }.getOrElse {
        Log.w(TAG, "rebuildIndex failed scope=$scope", it)
        false
    }

    /** 批量 embedding（保持输入顺序）；任何失败抛异常由调用方兜底 */
    private suspend fun embed(
        settings: Settings,
        channelId: kotlin.uuid.Uuid,
        modelId: kotlin.uuid.Uuid,
        dimension: Int,
        inputs: List<String>,
    ): List<FloatArray> {
        if (inputs.isEmpty()) return emptyList()
        val channel = settings.vectorProviders.firstOrNull { it.id == channelId }
            ?: error("embedding channel not found: $channelId")
        val model = channel.models.firstOrNull { it.id == modelId }
            ?: error("embedding model not found: $modelId")
        if (channel !is VectorProviderSetting.OpenAI) {
            error("embedding channel is not OpenAI-compatible: ${channel.id}")
        }
        val result = vectorProvider.generateEmbedding(
            setting = channel,
            input = inputs,
            dimensions = dimension,
            modelId = model.modelId,
        )
        return result.embeddings
    }

    companion object {
        private const val TAG = "MemorySemanticSearch"
    }
}
