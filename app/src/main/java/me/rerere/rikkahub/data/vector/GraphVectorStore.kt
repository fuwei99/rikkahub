package me.rerere.rikkahub.data.vector

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.common.android.MemoryGraphDebugLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated graph-node vector indexes. This must not share files with legacy memory indexes.
 *
 * 健壮性设计（review 2026-08-06 落地）：
 * - 文件名带 `v2` 格式版本：旧格式文件自然失效，升级后直接重建，不走"load 失败"异常路径；
 * - rebuild 与 search 对同一 scope 互斥（Mutex），rebuild 写 tmp+rename 原子替换；
 * - 内存 LRU 缓存已加载索引，避免每次搜索都重新反序列化整个文件；
 * - dirtyGeneration / rebuiltGenerations 持久化到 sidecar 文件，冷启动不会因 stale=true 全量重 embedding；
 * - embedding 返回维度与配置不一致时不再静默写入空索引，而是显式告警/抛错（旧路径 size=0 的真根因候选）。
 */
class GraphVectorStore(private val context: Context) {
    private val dirtyScopes = mutableSetOf<String>()
    private var dirtyGeneration = 0L
    private val rebuiltGenerations = mutableMapOf<String, Long>()

    /** 同一 scope 的 rebuild/search 互斥锁 */
    private val scopeMutexes = ConcurrentHashMap<String, Mutex>()

    /** 已加载索引 LRU 缓存（HnswIndex 官方声明线程安全，可并发 findNearest） */
    private val indexCache = object : LinkedHashMap<File, VectorIndexManager<GraphVectorItem, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<File, VectorIndexManager<GraphVectorItem, Long>>,
        ): Boolean = size > 8
    }
    private val cacheLock = Any()

    init {
        // 冷启动从 sidecar 恢复 generation 状态，避免进程重启后 stale=true 全量重 embedding
        runCatching {
            dirtyGeneration = File(indexDir(), GENERATION_FILE).readText().trim().toLongOrNull() ?: 0L
        }
        runCatching {
            File(indexDir(), REBUILT_FILE).readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) {
                    rebuiltGenerations[line.substring(0, i)] =
                        line.substring(i + 1).toLongOrNull() ?: 0L
                }
            }
        }
    }

    private fun indexDir() = File(AppPaths.filesDir(context), "memory_graph_vec").apply { mkdirs() }
    private fun sanitize(key: String) = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** 文件名带 v2 格式版本：1.2.1 官方 save/load 格式；旧格式文件自然淘汰。 */
    private fun indexFile(scope: String, indexKey: String, dimension: Int) =
        File(indexDir(), "graph_hnsw_v2_${sanitize(scope)}_${sanitize(indexKey)}_${dimension}.idx")

    private fun scopeLock(scope: String): Mutex = scopeMutexes.getOrPut(scope) { Mutex() }

    private fun cachedManager(file: File): VectorIndexManager<GraphVectorItem, Long>? =
        synchronized(cacheLock) { indexCache[file] }

    private fun cachePut(file: File, manager: VectorIndexManager<GraphVectorItem, Long>) {
        synchronized(cacheLock) { indexCache[file] = manager }
    }

    private fun cacheRemove(file: File) {
        synchronized(cacheLock) { indexCache.remove(file) }
    }

    fun markDirty(scope: String) {
        dirtyScopes.add(scope)
        MemoryGraphDebugLog.d(TAG, "markDirty: scope=$scope dirtyScopes=${dirtyScopes.joinToString(",")}")
    }

    /** Mark all graph indexes stale after a whole-table remote replacement. */
    fun markAllDirty() {
        dirtyGeneration++
        MemoryGraphDebugLog.d(TAG, "markAllDirty: dirtyGeneration=$dirtyGeneration")
        runCatching { File(indexDir(), GENERATION_FILE).writeText(dirtyGeneration.toString()) }
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
        scopeLock(scope).withLock {
            val file = indexFile(scope, indexKey, dimension)
            MemoryGraphDebugLog.i(TAG, "rebuildIndex: scope=$scope key=$indexKey dim=$dimension vectors=${vectors.size} file=${file.name}")
            file.delete()
            cacheRemove(file)
            val manager = VectorIndexManager<GraphVectorItem, Long>(
                dimensions = dimension,
                maxElements = (vectors.size * 2).coerceAtLeast(100),
                indexFile = file,
            )
            var matched = 0
            var dropped = 0
            vectors.forEach { (id, vector) ->
                if (vector.size == dimension) {
                    manager.addItem(GraphVectorItem(id, vector))
                    matched++
                } else {
                    dropped++
                }
            }
            if (dropped > 0) {
                MemoryGraphDebugLog.w(
                    TAG,
                    "rebuildIndex: $dropped/${vectors.size} vectors dimension mismatch (dim=$dimension), dropped; " +
                        "check embeddingDimension setting vs actual model output"
                )
            }
            if (vectors.isNotEmpty() && matched == 0) {
                // 全部被维度过滤掉 = 写入一个永远检索不到的空索引（旧路径 size=0 的真根因）。
                // 显式抛错让调用方（MemorySemanticSearch）记录并走失败降级，而不是静默空转。
                throw IllegalStateException(
                    "rebuildIndex: all ${vectors.size} vectors dropped by dimension check (dim=$dimension)"
                )
            }
            manager.save()
            manager.close()
            MemoryGraphDebugLog.i(TAG, "rebuildIndex saved: scope=$scope file=${file.name} size=${file.length()} bytes items=$matched")
            dirtyScopes.remove(scope)
            rebuiltGenerations[scope] = dirtyGeneration
            runCatching {
                val lines = rebuiltGenerations.map { (s, g) -> "$s=$g" }
                File(indexDir(), REBUILT_FILE).writeText(lines.joinToString("\n"))
                File(indexDir(), GENERATION_FILE).writeText(dirtyGeneration.toString())
            }
            Unit
        }
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
            val manager = cachedManager(file) ?: scopeLock(scope).withLock {
                cachedManager(file) ?: VectorIndexManager<GraphVectorItem, Long>(
                    dimensions = queryVector.size,
                    maxElements = 100,
                    indexFile = file,
                ).also { cachePut(file, it) }
            }
            MemoryGraphDebugLog.i(TAG, "search: loaded index size=${manager.size()} queryVectorSize=${queryVector.size} topK=$topK")
            val result = manager.findNearest(queryVector, topK).map { it.id() }
            MemoryGraphDebugLog.i(TAG, "search: result ids=${result.joinToString(",")}")
            result
        }
}

private const val TAG = "GraphVectorStore"
private const val GENERATION_FILE = ".generation"
private const val REBUILT_FILE = ".rebuilt"
