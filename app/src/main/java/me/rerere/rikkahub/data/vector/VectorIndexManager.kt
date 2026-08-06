package me.rerere.rikkahub.data.vector

import android.util.Log
import com.github.jelmerk.hnswlib.core.DistanceFunctions
import com.github.jelmerk.hnswlib.core.Item
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex
import me.rerere.common.android.MemoryGraphDebugLog
import java.io.File
import java.io.IOException

/**
 * 精简 HNSW 向量索引管理器（移植自 Operit VectorIndexManager）。
 *
 * - 纯 JVM（com.github.jelmerk:hnswlib-core:1.2.1），cosine 距离，支持删除；
 * - 官方 save(Path)/load(Path) 持久化（per-scope / per-dimension 文件，见 MemoryVectorStore）；
 * - 维度漂移天然隔离：维度变了就是新文件名，互不影响。
 *
 * 踩坑记录（1.2.1 源码核实）：
 * - `HnswIndex.load(Path)` 单参版走 `Thread.currentThread().getContextClassLoader()`，
 *   Android 上 IO 线程的 contextClassLoader 不保证是 app classloader，反序列化 item 类
 *   会抛 ClassNotFoundException（被包装成 IllegalArgumentException）。必须显式传 ClassLoader。
 * - `HnswIndex` 的 Java 序列化（writeObject/readObject 钩子）写的是完整自定义格式，
 *   不存在"委托壳丢向量"的问题；真正容易出问题的点：item 用默认 JavaObjectSerializer
 *   序列化，item 类结构/类名被 R8 改变后旧文件会 InvalidClassException（见 proguard keep）。
 * - 保存非原子：写 tmp + rename 原子替换，进程被杀不会留下半截文件。
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
        // 用局部 val 承接，避免可空属性在 lambda 内无法智能转换
        val file = indexFile
        index = if (file != null && file.exists()) {
            try {
                // Java 静态泛型方法 load(Path, ClassLoader) 的类型参数只出现在返回类型中，
                // Kotlin 无法推断，必须显式指定：<TId, TVector, TItem, TDistance>。
                // 必须用 app classloader（VectorIndexManager 的 classloader），不能走线程 contextClassLoader。
                val loader = VectorIndexManager::class.java.classLoader
                    ?: ClassLoader.getSystemClassLoader()
                HnswIndex.load<Id, FloatArray, T, Float>(file.toPath(), loader)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load index, creating new one: ${file.absolutePath}", e)
                MemoryGraphDebugLog.e(TAG, "Failed to load index: ${file.absolutePath}", e)
                // 不删除原文件：先把坏文件挪走备份（保留现场供分析），
                // 避免每次启动都重试解析同一个坏文件，也避免"load 失败→删文件→
                // 空索引被当正常→下一轮 needsRebuild 又全量重 embedding"的死循环。
                val backup = File(
                    file.parentFile,
                    file.name + ".corrupt." + System.currentTimeMillis()
                )
                runCatching { file.renameTo(backup) }
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

    /** 保存索引到文件（写 tmp + rename 原子替换，中断不会留下半截文件） */
    fun save() {
        if (indexFile != null && index != null) {
            try {
                indexFile.parentFile?.mkdirs()
                val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
                index?.save(tmp.toPath())
                val replaced = tmp.renameTo(indexFile)
                if (!replaced) {
                    // rename 失败（罕见，比如目标被占用）：退化为直接保存
                    tmp.delete()
                    index?.save(indexFile.toPath())
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save index to ${indexFile.absolutePath}", e)
                MemoryGraphDebugLog.e(TAG, "Failed to save index to ${indexFile.absolutePath}", e)
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
