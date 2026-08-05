package me.rerere.rikkahub.data.ai.memory

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.VectorProviderSetting
import me.rerere.ai.provider.providers.VectorProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.MemoryGraphSearchHit
import me.rerere.rikkahub.data.repository.MemoryGraphRepository
import me.rerere.rikkahub.data.vector.GraphVectorStore

/**
 * 记忆图节点语义检索（与 legacy memory 完全隔离）。
 *
 * - embedding 渠道：独立的「向量模型服务」配置区块（`Settings.vectorProviders`，
 *   `Settings.memorySearch` 指定 channelId + modelId + dimension）；
 * - 索引：HNSW（hnswlib-core，cosine），graph per-scope 文件，维度变化自动换文件；
 * - 重建：搜索前 lazy 检查（文件缺失或 dirty）→ 当前 scope 的图节点全量 embedding；
 * - 失败降级：任何一步失败返回空，调用方保留关键词检索结果。
 */
class MemorySemanticSearch(
    private val vectorProvider: VectorProvider,
    private val graphRepo: MemoryGraphRepository,
    private val vectorStore: GraphVectorStore,
) {
    private val batchSize = 32

    /**
     * 语义检索：返回按语义排名排序的图节点。
     * 渠道未配置 / embedding 失败 / 索引缺失且构建失败时返回空。
     */
    suspend fun search(
        settings: Settings,
        query: String,
        scope: String,
        topK: Int = 10,
    ): List<MemoryGraphSearchHit> = withContext(Dispatchers.IO) {
        if (query.isBlank() || topK <= 0) return@withContext emptyList()
        val cfg = settings.memorySearch
        val channelId = cfg.embeddingChannelId ?: return@withContext emptyList()
        val modelId = cfg.embeddingModelId ?: return@withContext emptyList()
        val dimension = cfg.embeddingDimension.coerceIn(64, 4096)

        val channel = settings.vectorProviders.firstOrNull { it.id == channelId }
            ?: return@withContext emptyList()
        channel.models.firstOrNull { it.id == modelId && it.type == ModelType.EMBEDDING }
            ?: return@withContext emptyList()

        val indexKey = "$channelId-$modelId"
        if (vectorStore.needsRebuild(scope, indexKey, dimension)) {
            if (!rebuildIndex(settings, channelId, modelId, indexKey, dimension, scope)) {
                return@withContext emptyList()
            }
        }

        val queryVector = embed(settings, channelId, modelId, dimension, listOf(query)).firstOrNull()
            ?: return@withContext emptyList()
        val ids = vectorStore.search(scope, indexKey, queryVector, topK)
        if (ids.isEmpty()) return@withContext emptyList()

        val nodesById = graphRepo.getNodesByIds(ids)
        ids.mapIndexedNotNull { index, id ->
            nodesById[id]?.let { node ->
                // HNSW 当前只返回有序 id，不暴露距离；用稳定的 rank score 参与混合召回。
                val rankScore = (topK - index).toFloat() / topK.toFloat()
                MemoryGraphSearchHit(node = node, score = rankScore)
            }
        }
    }

    private suspend fun rebuildIndex(
        settings: Settings,
        channelId: kotlin.uuid.Uuid,
        modelId: kotlin.uuid.Uuid,
        indexKey: String,
        dimension: Int,
        scope: String,
    ): Boolean = runCatching {
        val nodes = graphRepo.getNodes(scope)
            .filter { it.title.isNotBlank() || it.content.isNotBlank() }
        if (nodes.isEmpty()) {
            // 空 scope：重建一个空索引，避免每次搜索都重复尝试 embedding。
            vectorStore.rebuildIndex(scope, indexKey, dimension, emptyList())
            return@runCatching true
        }

        val vectors = mutableListOf<Pair<Long, FloatArray>>()
        nodes.chunked(batchSize).forEach { batch ->
            val batchVectors = embed(
                settings = settings,
                channelId = channelId,
                modelId = modelId,
                dimension = dimension,
                inputs = batch.map { "${it.title}\n${it.content}" },
            )
            if (batchVectors.size != batch.size) {
                throw IllegalStateException("embedding batch size mismatch: ${batchVectors.size} != ${batch.size}")
            }
            batch.forEachIndexed { index, node ->
                vectors.add(node.id to batchVectors[index])
            }
        }
        vectorStore.rebuildIndex(scope, indexKey, dimension, vectors)
        true
    }.getOrElse {
        Log.w(TAG, "rebuildIndex failed scope=$scope", it)
        false
    }

    /** 批量 embedding（保持输入顺序）；任何失败抛异常由调用方兜底。 */
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
        return result.embeddings.map { it.toFloatArray() }
    }

    companion object {
        private const val TAG = "MemorySemanticSearch"
    }
}
