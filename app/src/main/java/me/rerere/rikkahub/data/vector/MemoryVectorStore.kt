package me.rerere.rikkahub.data.vector

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 记忆语义向量索引存储（记忆图 Phase 2）。
 *
 * - 索引文件：`filesDir/memory_vec/memory_hnsw_{scope}_{dimension}.idx`；
 * - **维度漂移天然隔离**：换模型/维度 = 新文件名，旧索引不冲突；
 * - 重建策略：全量重建（个人记忆量级几百条，embedding 一次几十秒内可接受）。
 *   搜索前 lazy ensure：文件缺失（首次/换维度）或 dirty 标记（写入后置位）时重建；
 * - dirty 用内存标记即可（进程内保证一致；重启后下次搜索会因文件缺失/过期重建——
 *   见 [MemoryVectorStore.dirty]，重启丢失 dirty 的影响：写入过但没重建的索引在下次
 *   语义搜索时命中旧数据，可接受（语义搜索默认关闭，用户显式开启）。
 */
class MemoryVectorStore(private val context: Context) {

    private val dirtyScopes = mutableSetOf<String>()

    private fun indexDir(): File =
        File(AppPaths.filesDir(context), "memory_vec").apply { mkdirs() }

    private fun sanitize(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** 文件名带 v2 格式版本：1.2.1 官方 save/load 格式；旧格式文件自然淘汰。 */
    fun indexFile(scope: String, dimension: Int): File =
        File(indexDir(), "memory_hnsw_v2_${sanitize(scope)}_${dimension}.idx")

    fun exists(scope: String, dimension: Int): Boolean = indexFile(scope, dimension).exists()

    /** 写入记忆后置 dirty（下次语义搜索前重建索引） */
    fun markDirty(scope: String) {
        dirtyScopes.add(scope)
    }

    /** 是否需要重建（文件缺失 或 dirty） */
    fun needsRebuild(scope: String, dimension: Int): Boolean =
        !exists(scope, dimension) || dirtyScopes.contains(scope)

    /**
     * 全量重建 scope 的索引。
     * @param vectors memoryId -> embedding，维度必须一致
     */
    suspend fun rebuildIndex(
        scope: String,
        dimension: Int,
        vectors: List<Pair<Int, FloatArray>>,
    ) = withContext(Dispatchers.IO) {
        val file = indexFile(scope, dimension)
        file.delete() // 重建：不加载旧索引
        val manager = VectorIndexManager<MemoryVectorItem, Int>(
            dimensions = dimension,
            maxElements = (vectors.size * 2).coerceAtLeast(100),
            indexFile = file,
        )
        var matched = 0
        var dropped = 0
        vectors.forEach { (memoryId, vector) ->
            if (vector.size == dimension) {
                manager.addItem(MemoryVectorItem(memoryId, vector))
                matched++
            } else {
                dropped++
            }
        }
        if (dropped > 0) {
            Log.w(TAG, "rebuildIndex: $dropped/${vectors.size} vectors dimension mismatch (dim=$dimension), dropped")
        }
        if (vectors.isNotEmpty() && matched == 0) {
            throw IllegalStateException(
                "rebuildIndex: all ${vectors.size} vectors dropped by dimension check (dim=$dimension)"
            )
        }
        manager.save()
        manager.close()
        dirtyScopes.remove(scope)
    }

    /**
     * 语义搜索：返回命中的 memoryId（按相似度降序）。
     * 索引不存在（未构建/维度变化）时返回空，调用方决定降级。
     */
    suspend fun search(
        scope: String,
        queryVector: FloatArray,
        topK: Int,
    ): List<Int> = withContext(Dispatchers.IO) {
        val file = indexFile(scope, queryVector.size)
        if (!file.exists()) return@withContext emptyList()
        val manager = VectorIndexManager<MemoryVectorItem, Int>(
            dimensions = queryVector.size,
            maxElements = 100,
            indexFile = file,
        )
        val hits = manager.findNearest(queryVector, topK)
        manager.close()
        hits.map { it.id() }
    }
}

private const val TAG = "MemoryVectorStore"
