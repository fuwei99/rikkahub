package me.rerere.rikkahub.data.vector

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.common.android.MemoryGraphDebugLog
import java.io.File

/**
 * Dedicated graph-node vector indexes. This must not share files with legacy memory indexes.
 */
class GraphVectorStore(private val context: Context) {
    private val dirtyScopes = mutableSetOf<String>()
    private var dirtyGeneration = 0L
    private val rebuiltGenerations = mutableMapOf<String, Long>()

    private fun indexDir() = File(AppPaths.filesDir(context), "memory_graph_vec").apply { mkdirs() }
    private fun sanitize(key: String) = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    private fun indexFile(scope: String, indexKey: String, dimension: Int) =
        File(indexDir(), "graph_hnsw_${sanitize(scope)}_${sanitize(indexKey)}_${dimension}.idx")

    fun markDirty(scope: String) {
        dirtyScopes.add(scope)
        MemoryGraphDebugLog.d(TAG, "markDirty: scope=$scope dirtyScopes=${dirtyScopes.joinToString(",")}")
    }

    /** Mark all graph indexes stale after a whole-table remote replacement. */
    fun markAllDirty() {
        dirtyGeneration++
        MemoryGraphDebugLog.d(TAG, "markAllDirty: dirtyGeneration=$dirtyGeneration")
    }

    fun needsRebuild(scope: String, indexKey: String, dimension: Int): Boolean {
        val file = indexFile(scope, indexKey, dimension)
        val missing = !file.exists()
        val dirty = scope in dirtyScopes
        val stale = rebuiltGenerations[scope] != dirtyGeneration
        val rebuild = missing || dirty || stale
        MemoryGraphDebugLog.d(
            TAG,
            "needsRebuild: scope=$scope key=$indexKey dim=$dimension file=${file.name} " +
                "exists=${file.exists()} missing=$missing dirty=$dirty stale=$stale => $rebuild " +
                "rebuiltGen=${rebuiltGenerations[scope]} curGen=$dirtyGeneration"
        )
        return rebuild
    }

    suspend fun rebuildIndex(
        scope: String,
        indexKey: String,
        dimension: Int,
        vectors: List<Pair<Long, FloatArray>>,
    ) = withContext(Dispatchers.IO) {
        val file = indexFile(scope, indexKey, dimension)
        MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope key=$indexKey dim=$dimension vectors=${vectors.size} file=${file.name}")
        file.delete()
        val manager = VectorIndexManager<GraphVectorItem, Long>(
            dimensions = dimension,
            maxElements = (vectors.size * 2).coerceAtLeast(100),
            indexFile = file,
        )
        vectors.forEach { (id, vector) ->
            if (vector.size == dimension) manager.addItem(GraphVectorItem(id, vector))
        }
        manager.save()
        manager.close()
        MemoryGraphDebugLog.i(TAG, "rebuildIndex saved: scope=$scope file=${file.name} size=${file.length()} bytes")
        dirtyScopes.remove(scope)
        rebuiltGenerations[scope] = dirtyGeneration
    }

    suspend fun search(
        scope: String,
        indexKey: String,
        queryVector: FloatArray,
        topK: Int,
    ): List<Long> =
        withContext(Dispatchers.IO) {
            val file = indexFile(scope, indexKey, queryVector.size)
            MemoryGraphDebugLog.d(TAG, "search: scope=$scope key=$indexKey file=${file.name} exists=${file.exists()}")
            if (!file.exists()) {
                MemoryGraphDebugLog.w(TAG, "search: index file missing, empty result")
                return@withContext emptyList()
            }
            val manager = VectorIndexManager<GraphVectorItem, Long>(
                dimensions = queryVector.size,
                maxElements = 100,
                indexFile = file,
            )
            MemoryGraphDebugLog.i(TAG, "search: loaded index size=${manager.size()} queryVectorSize=${queryVector.size} topK=$topK")
            val result = manager.findNearest(queryVector, topK).map { it.id() }
            manager.close()
            MemoryGraphDebugLog.i(TAG, "search: result ids=${result.joinToString(",")}")
            result
        }
}

private const val TAG = "GraphVectorStore"
