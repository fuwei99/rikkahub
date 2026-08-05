package me.rerere.rikkahub.data.vector

import com.github.jelmerk.hnswlib.core.Item

/** Graph node vector item. The node id is scoped by the index file. */
data class GraphVectorItem(
    private val nodeId: Long,
    private val vector: FloatArray,
    private val version: Long = 0L,
) : Item<Long, FloatArray> {
    override fun id(): Long = nodeId
    override fun vector(): FloatArray = vector
    override fun dimensions(): Int = vector.size
    override fun version(): Long = version

    override fun equals(other: Any?): Boolean =
        other is GraphVectorItem && nodeId == other.nodeId

    override fun hashCode(): Int = nodeId.hashCode()
}
