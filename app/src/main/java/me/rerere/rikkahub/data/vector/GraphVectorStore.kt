package me.rerere.rikkahub.data.vector

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.AppPaths
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

    fun markDirty(scope: String) { dirtyScopes.add(scope) }

    /** Mark all graph indexes stale after a whole-table remote replacement. */
    fun markAllDirty() {
        dirtyGeneration++
    }

    fun needsRebuild(scope: String, indexKey: String, dimension: Int): Boolean =
        !indexFile(scope, indexKey, dimension).exists() ||
            scope in dirtyScopes ||
            rebuiltGenerations[scope] != dirtyGeneration

    suspend fun rebuildIndex(
        scope: String,
        indexKey: String,
        dimension: Int,
        vectors: List<Pair<Long, FloatArray>>,
    ) = withContext(Dispatchers.IO) {
        val file = indexFile(scope, indexKey, dimension)
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
            if (!file.exists()) return@withContext emptyList()
            val manager = VectorIndexManager<GraphVectorItem, Long>(
                dimensions = queryVector.size,
                maxElements = 100,
                indexFile = file,
            )
            val result = manager.findNearest(queryVector, topK).map { it.id() }
            manager.close()
            result
        }
}
