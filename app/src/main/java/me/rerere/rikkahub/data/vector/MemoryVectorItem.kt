package me.rerere.rikkahub.data.vector

import com.github.jelmerk.hnswlib.core.Item

/**
 * 记忆向量项：id = memoryId（Room MemoryEntity.id），vector = embedding。
 * 查询命中后由调用方按 memoryId 批量回表补内容（避免在索引里冗余存大文本）。
 */
data class MemoryVectorItem(
    private val memoryId: Int,
    private val vector: FloatArray,
    private val version: Long = 0L,
) : Item<Int, FloatArray> {
    // 同 GraphVectorItem：显式固定 serialVersionUID，防止升级后默认 UID 漂移导致旧索引 InvalidClassException。
    companion object {
        private const val serialVersionUID: Long = 1L
    }


    override fun id(): Int = memoryId
    override fun vector(): FloatArray = vector
    override fun dimensions(): Int = vector.size
    override fun version(): Long = version

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryVectorItem
        return memoryId == other.memoryId
    }

    override fun hashCode(): Int = memoryId.hashCode()
}
