package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryGraphNodeEntity

@Dao
interface MemoryGraphNodeDAO {
    @Query("SELECT * FROM memory_graph_node ORDER BY updated_at DESC")
    suspend fun getAll(): List<MemoryGraphNodeEntity>

    @Query("SELECT * FROM memory_graph_node WHERE scope = :scope ORDER BY updated_at DESC")
    fun getByScopeFlow(scope: String): Flow<List<MemoryGraphNodeEntity>>

    @Query("SELECT * FROM memory_graph_node WHERE scope = :scope ORDER BY updated_at DESC")
    suspend fun getByScope(scope: String): List<MemoryGraphNodeEntity>

    @Query("SELECT * FROM memory_graph_node WHERE id = :id")
    suspend fun getById(id: Long): MemoryGraphNodeEntity?

    @Query("SELECT * FROM memory_graph_node WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MemoryGraphNodeEntity>

    @Query("SELECT * FROM memory_graph_node WHERE scope = :scope AND title = :title LIMIT 1")
    suspend fun findByTitle(scope: String, title: String): MemoryGraphNodeEntity?

    @Query("SELECT * FROM memory_graph_node WHERE scope = :scope AND title = :title")
    suspend fun findAllByTitle(scope: String, title: String): List<MemoryGraphNodeEntity>

    @Insert
    suspend fun insert(node: MemoryGraphNodeEntity): Long

    @Update
    suspend fun update(node: MemoryGraphNodeEntity)

    @Query("DELETE FROM memory_graph_node WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_graph_node WHERE scope = :scope")
    suspend fun deleteByScope(scope: String)

    @Query("DELETE FROM memory_graph_node")
    suspend fun deleteAll()
}
