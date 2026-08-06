package me.rerere.rikkahub.data.vector

import com.github.jelmerk.hnswlib.core.Item

/** Graph node vector item. The node id is scoped by the index file. */
data class GraphVectorItem(
    private val nodeId: Long,
    private val vector: FloatArray,
    private val version: Long = 0L,
) : Item<Long, FloatArray> {
    // hnswlib 默认 JavaObjectSerializer 用 Java 序列化把 item 写进索引文件；
    // 显式固定 UID，避免版本升级（类结构微调）导致默认 UID 漂移 → InvalidClassException → 整库重建。
    companion object {
        private const val serialVersionUID: Long = 1L
    }

    override fun id(): Long = nodeId
    override fun vector(): FloatArray = vector
    override fun dimensions(): Int = vector.size
    override fun version(): Long = version

    override fun equals(other: Any?): Boolean =
        other is GraphVectorItem && nodeId == other.nodeId

    override fun hashCode(): Int = nodeId.hashCode()
}
