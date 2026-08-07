package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryGraphEntity

@Dao
interface MemoryGraphDAO {
    @Query("SELECT * FROM memory_graph ORDER BY sort_order DESC, created_at ASC")
    suspend fun getAll(): List<MemoryGraphEntity>

    @Query("SELECT * FROM memory_graph ORDER BY sort_order DESC, created_at ASC")
    fun getAllFlow(): Flow<List<MemoryGraphEntity>>

    @Query("SELECT * FROM memory_graph WHERE id = :id")
    suspend fun getById(id: String): MemoryGraphEntity?

    @Query("SELECT * FROM memory_graph WHERE slug = :slug LIMIT 1")
    suspend fun getBySlug(slug: String): MemoryGraphEntity?

    @Query("SELECT * FROM memory_graph WHERE kind = :kind ORDER BY sort_order DESC, created_at ASC")
    suspend fun getByKind(kind: String): List<MemoryGraphEntity>

    @Query("SELECT * FROM memory_graph WHERE kind = 'ASSISTANT' AND bound_assistant_id = :assistantId LIMIT 1")
    suspend fun getByAssistantId(assistantId: String): MemoryGraphEntity?

    @Query("SELECT COUNT(*) FROM memory_graph")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM memory_graph WHERE created_by = :createdBy")
    suspend fun countByCreator(createdBy: String): Int

    /** 每张图的节点数（注册表里没有但节点表里有的 scope 不会出现在这里，由孤儿自愈补齐） */
    @Query("SELECT scope AS scope, COUNT(*) AS count FROM memory_graph_node GROUP BY scope")
    suspend fun nodeCounts(): List<ScopeCount>

    /** 节点表里出现过但注册表里没有的 scope（云同步孤儿自愈用） */
    @Query("SELECT DISTINCT scope FROM memory_graph_node WHERE scope NOT IN (SELECT id FROM memory_graph)")
    suspend fun orphanNodeScopes(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(graph: MemoryGraphEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(graph: MemoryGraphEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(graphs: List<MemoryGraphEntity>)

    @Update
    suspend fun update(graph: MemoryGraphEntity)

    @Query("DELETE FROM memory_graph WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_graph")
    suspend fun deleteAll()

    /** 把 auto_extract_target 收敛成单选（管理页切换落点时先清后置） */
    @Query("UPDATE memory_graph SET auto_extract_target = 0 WHERE auto_extract_target = 1")
    suspend fun clearAutoExtractTargets()
}

data class ScopeCount(
    val scope: String,
    val count: Int,
)
