package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryGraphLinkEntity

@Dao
interface MemoryGraphLinkDAO {
    @Insert
    suspend fun insert(link: MemoryGraphLinkEntity): Long

    @Update
    suspend fun update(link: MemoryGraphLinkEntity)

    @Delete
    suspend fun delete(link: MemoryGraphLinkEntity)

    @Query("SELECT * FROM memory_graph_link")
    suspend fun getAll(): List<MemoryGraphLinkEntity>

    @Query("SELECT * FROM memory_graph_link WHERE id = :id")
    suspend fun getById(id: Long): MemoryGraphLinkEntity?

    @Query("SELECT * FROM memory_graph_link WHERE scope = :scope")
    suspend fun getByScope(scope: String): List<MemoryGraphLinkEntity>

    @Query("SELECT * FROM memory_graph_link WHERE scope = :scope")
    fun getByScopeFlow(scope: String): Flow<List<MemoryGraphLinkEntity>>

    @Query("SELECT * FROM memory_graph_link WHERE scope = :scope AND (source_id = :nodeId OR target_id = :nodeId)")
    suspend fun getByNode(scope: String, nodeId: Long): List<MemoryGraphLinkEntity>

    @Query("SELECT * FROM memory_graph_link WHERE scope = :scope AND source_id = :sourceId AND target_id = :targetId AND type = :type LIMIT 1")
    suspend fun findDuplicate(scope: String, sourceId: Long, targetId: Long, type: String): MemoryGraphLinkEntity?

    @Query("DELETE FROM memory_graph_link WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_graph_link WHERE scope = :scope")
    suspend fun deleteByScope(scope: String)

    @Query("DELETE FROM memory_graph_link")
    suspend fun deleteAll()

    @Query("DELETE FROM memory_graph_link WHERE source_id NOT IN (SELECT id FROM memory_graph_node) OR target_id NOT IN (SELECT id FROM memory_graph_node)")
    suspend fun deleteDangling()
}
