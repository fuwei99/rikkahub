package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryLinkEntity

@Dao
interface MemoryLinkDAO {
    @Insert
    suspend fun insert(link: MemoryLinkEntity): Long

    @Update
    suspend fun update(link: MemoryLinkEntity)

    @Query("SELECT * FROM memory_link WHERE id = :id")
    suspend fun getById(id: Long): MemoryLinkEntity?

    @Query("SELECT * FROM memory_link")
    suspend fun getAll(): List<MemoryLinkEntity>

    @Query("SELECT * FROM memory_link WHERE scope = :scope")
    suspend fun getLinksOfScope(scope: String): List<MemoryLinkEntity>

    @Query("SELECT * FROM memory_link WHERE scope = :scope")
    fun getLinksOfScopeFlow(scope: String): Flow<List<MemoryLinkEntity>>

    /** 某记忆节点的出边 + 入边（同 scope） */
    @Query("SELECT * FROM memory_link WHERE scope = :scope AND (source_id = :memoryId OR target_id = :memoryId)")
    suspend fun getLinksOfMemory(scope: String, memoryId: Int): List<MemoryLinkEntity>

    /** 建边去重: 同 source+type+target 不重复创建（Operit linkMemories 同款语义） */
    @Query("SELECT * FROM memory_link WHERE scope = :scope AND source_id = :sourceId AND target_id = :targetId AND type = :type LIMIT 1")
    suspend fun findDuplicate(scope: String, sourceId: Int, targetId: Int, type: String): MemoryLinkEntity?

    @Query("DELETE FROM memory_link WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清除某作用域全部边（清空助手记忆时联动） */
    @Query("DELETE FROM memory_link WHERE scope = :scope")
    suspend fun deleteLinksOfScope(scope: String)

    /** 删除某记忆节点时清除其关联边（memory id 全局唯一, 无需 scope 过滤） */
    @Query("DELETE FROM memory_link WHERE source_id = :memoryId OR target_id = :memoryId")
    suspend fun deleteLinksOfMemory(memoryId: Int)

    /** 云锚点同步: 应用云端全量时清空后重建 */
    @Query("DELETE FROM memory_link")
    suspend fun deleteAll()

    /**
     * 悬挂链接清理：删除两端节点已不存在的边。
     * 记忆表是整表 bundle 同步（先删后插），且旧客户端 payload 不带 id 会引发 id 漂移；
     * 每次应用云端记忆/链接后执行一次，保证图不悬挂（借鉴 Operit cleanupDanglingLinksIfNeeded）。
     */
    @Query("DELETE FROM memory_link WHERE source_id NOT IN (SELECT id FROM memoryentity) OR target_id NOT IN (SELECT id FROM memoryentity)")
    suspend fun deleteDanglingLinks()
}
