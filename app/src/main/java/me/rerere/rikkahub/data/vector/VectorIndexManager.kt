package me.rerere.rikkahub.data.vector

import android.util.Log
import com.github.jelmerk.hnswlib.core.DistanceFunctions
import com.github.jelmerk.hnswlib.core.Item
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex
import java.io.File
import java.io.IOException

/**
 * 精简 HNSW 向量索引管理器（移植自 Operit VectorIndexManager）。
 *
 * - 纯 JVM（com.github.jelmerk:hnswlib-core:1.2.1），cosine 距离，支持删除；
 * - 索引对象直接 Java 序列化到文件（per-scope / per-dimension 文件，见 MemoryVectorStore）；
 * - 维度漂移天然隔离：维度变了就是新文件名，互不影响。
 */
class VectorIndexManager<T : Item<Id, FloatArray>, Id : Any>(
    private val dimensions: Int,
    private val maxElements: Int,
    private val indexFile: File? = null,
) {
    private var index: HnswIndex<Id, FloatArray, T, Float>? = null

    init {
        initIndex()
    }

    /** 初始化索引（新建或加载） */
    fun initIndex() {
        index = if (indexFile != null && indexFile.exists()) {
            try {
                @Suppress("UNCHECKED_CAST")
                // 必须用官方 save/load 持久化：HnswIndex 的 Java 对象序列化只写出
                // 序列化委托（HnswIndexSerializationDelegate），反序列化后向量数据丢失
                // （表现为 loaded index size=0，语义检索永远返回空）。
                HnswIndex.load(indexFile.toPath()) as HnswIndex<Id, FloatArray, T, Float>
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load index, creating new one: ${indexFile.absolutePath}", e)
                // 加载失败删除可能损坏的文件并新建
                indexFile.delete()
                newIndex()
            }
        } else {
            newIndex()
        }
    }

    private fun newIndex(): HnswIndex<Id, FloatArray, T, Float> = HnswIndex
        .newBuilder(dimensions, DistanceFunctions.FLOAT_COSINE_DISTANCE, maxElements)
        .withRemoveEnabled()
        .build()

    /** 添加一个向量项 */
    fun addItem(item: T) {
        ensureCapacity(size() + 1)
        index?.add(item)
    }

    /** 删除一个向量项 */
    fun removeItem(id: Id, version: Long = Long.MAX_VALUE): Boolean {
        return index?.remove(id, version) ?: false
    }

    /** 查询最近的 K 个邻居 */
    fun findNearest(query: FloatArray, k: Int): List<T> {
        return index?.findNearest(query, k)?.map { it.item() } ?: emptyList()
    }

    fun size(): Int = index?.size() ?: 0

    fun maxItemCount(): Int = index?.maxItemCount ?: maxElements

    fun ensureCapacity(minCapacity: Int) {
        val current = index ?: return
        if (minCapacity > current.maxItemCount) {
            current.resize(minCapacity)
        }
    }

    /** 保存索引到文件 */
    fun save() {
        if (indexFile != null && index != null) {
            try {
                indexFile.parentFile?.mkdirs()
                index?.save(indexFile.toPath())
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save index to ${indexFile.absolutePath}", e)
            }
        }
    }

    /** 关闭索引（释放引用） */
    fun close() {
        index = null
    }

    companion object {
        private const val TAG = "VectorIndexManager"
    }
}
