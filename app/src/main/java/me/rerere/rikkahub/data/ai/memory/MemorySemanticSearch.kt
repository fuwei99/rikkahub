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
import me.rerere.common.android.MemoryGraphDebugLog

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
        val channelId = cfg.embeddingChannelId
        val modelId = cfg.embeddingModelId
        val dimension = cfg.embeddingDimension.coerceIn(64, 4096)
        MemoryGraphDebugLog.i(
            TAG,
            "search entry: scope=$scope query=\"${query.take(120)}\" topK=$topK " +
                "channelId=${channelId ?: "NULL"} modelId=${modelId ?: "NULL"} dimension=$dimension " +
                "keywordSearch=${cfg.keywordSearch} semanticSearch=${cfg.semanticSearch} " +
                "vectorProviders=${settings.vectorProviders.size}"
        )
        if (channelId == null) {
            MemoryGraphDebugLog.w(TAG, "search abort: embeddingChannelId is NULL, semantic search disabled")
            return@withContext emptyList()
        }
        if (modelId == null) {
            MemoryGraphDebugLog.w(TAG, "search abort: embeddingModelId is NULL")
            return@withContext emptyList()
        }

        val channel = settings.vectorProviders.firstOrNull { it.id == channelId }
        if (channel == null) {
            MemoryGraphDebugLog.w(TAG, "search abort: channelId=$channelId not found in vectorProviders " +
                "[${settings.vectorProviders.map { it.id }.joinToString(",")}]")
            return@withContext emptyList()
        }
        MemoryGraphDebugLog.i(TAG, "channel found: id=${channel.id} type=${channel.javaClass.simpleName} " +
            "models=[${channel.models.joinToString(",") { it.id.toString() + ":" + it.type } }]")
        channel.models.firstOrNull { it.id == modelId && it.type == ModelType.EMBEDDING }
            ?: run {
                MemoryGraphDebugLog.w(TAG, "search abort: modelId=$modelId type=EMBEDDING not found in channel")
                return@withContext emptyList()
            }

        val indexKey = "$channelId-$modelId"
        val needsRebuild = vectorStore.needsRebuild(scope, indexKey, dimension)
        MemoryGraphDebugLog.i(TAG, "index: scope=$scope key=$indexKey needsRebuild=$needsRebuild")
        if (needsRebuild) {
            MemoryGraphDebugLog.i(TAG, "rebuildIndex start: scope=$scope dimension=$dimension")
            if (!rebuildIndex(settings, channelId, modelId, indexKey, dimension, scope)) {
                MemoryGraphDebugLog.w(TAG, "rebuildIndex FAILED scope=$scope, semantic search returns empty")
                return@withContext emptyList()
            }
            MemoryGraphDebugLog.i(TAG, "rebuildIndex done: scope=$scope")
        }

        MemoryGraphDebugLog.i(TAG, "embedding query: scope=$scope inputs=1")
        val queryVector = embed(settings, channelId, modelId, dimension, listOf(query)).firstOrNull()
            ?: run {
                MemoryGraphDebugLog.w(TAG, "embed query FAILED: returned null vector")
                return@withContext emptyList()
            }
        MemoryGraphDebugLog.i(TAG, "embed query done: vectorSize=${queryVector.size}")
        val ids = vectorStore.search(scope, indexKey, queryVector, topK)
        MemoryGraphDebugLog.i(TAG, "hnsw search: returned ids=${ids.joinToString(",")}")
        if (ids.isEmpty()) {
            MemoryGraphDebugLog.w(TAG, "hnsw search returned EMPTY, semantic search returns empty")
            return@withContext emptyList()
        }

        val nodesById = graphRepo.getNodesByIds(ids)
        val hits = ids.mapIndexedNotNull { index, id ->
            nodesById[id]?.let { node ->
                // HNSW 当前只返回有序 id，不暴露距离；用稳定的 rank score 参与混合召回。
                val rankScore = (topK - index).toFloat() / topK.toFloat()
                MemoryGraphSearchHit(node = node, score = rankScore)
            }
        }
        MemoryGraphDebugLog.i(
            TAG,
            "result: scope=$scope ids=${ids.size} mapped=${hits.size} " +
                "titles=${hits.joinToString(",") { it.node.title.take(20) }}"
        )
        hits
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
        MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope totalNodes=${graphRepo.getNodes(scope).size} " +
            "nonEmpty=${nodes.size}")
        if (nodes.isEmpty()) {
            // 空 scope：重建一个空索引，避免每次搜索都重复尝试 embedding。
            MemoryGraphDebugLog.w(TAG, "rebuildIndex: scope=$scope has 0 nodes, building empty index")
            vectorStore.rebuildIndex(scope, indexKey, dimension, emptyList())
            return@runCatching true
        }

        val vectors = mutableListOf<Pair<Long, FloatArray>>()
        var batchIndex = 0
        nodes.chunked(batchSize).forEach { batch ->
            batchIndex++
            MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope batch#$batchIndex size=${batch.size}")
            val batchVectors = embed(
                settings = settings,
                channelId = channelId,
                modelId = modelId,
                dimension = dimension,
                inputs = batch.map { "${it.title}\n${it.content}" },
            )
            MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope batch#$batchIndex embed done " +
                "returned=${batchVectors.size} firstVectorSize=${batchVectors.firstOrNull()?.size}")
            if (batchVectors.size != batch.size) {
                throw IllegalStateException("embedding batch size mismatch: ${batchVectors.size} != ${batch.size}")
            }
            batch.forEachIndexed { index, node ->
                vectors.add(node.id to batchVectors[index])
            }
        }
        MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope vectors=${vectors.size} save index")
        vectorStore.rebuildIndex(scope, indexKey, dimension, vectors)
        MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope index saved OK")
        true
    }.getOrElse {
        Log.w(TAG, "rebuildIndex failed scope=$scope", it)
        MemoryGraphDebugLog.e(TAG, "rebuildIndex failed scope=$scope", it)
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
        MemoryGraphDebugLog.i(
            TAG,
            "embed resp: model=${model.modelId} input=${inputs.size} returned=${result.embeddings.size} " +
                "dims=${result.embeddings.firstOrNull()?.let { it.size } ?: -1}"
        )
        return result.embeddings.map { it.toFloatArray() }
    }

    companion object {
        private const val TAG = "MemorySemanticSearch"
    }
}
