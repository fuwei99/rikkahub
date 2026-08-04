package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memoryentity WHERE id IN (:ids)")
    suspend fun getMemoriesByIds(ids: List<Int>): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)

    /** 云锚点同步（P1）：应用云端全量时清空后重建 */
    @Query("DELETE FROM memoryentity")
    suspend fun deleteAllMemories()

    // ---- 记忆图 P3：LLM 自动抽取按标题查/去重 ----

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND title = :title LIMIT 1")
    suspend fun findMemoryByTitle(assistantId: String, title: String): MemoryEntity?

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND title = :title")
    suspend fun findMemoriesByTitle(assistantId: String, title: String): List<MemoryEntity>
}
