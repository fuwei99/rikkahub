package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.repository.LightConversationEntity

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = '' ORDER BY is_pinned DESC, update_at DESC")
    fun getUnfiledConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE folder_id = :folderId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfFolderPaging(folderId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    /**
     * 「最近聊天」工具专用：**纯时间序**，置顶不参与排序。
     *
     * 会话列表 UI 用 `is_pinned DESC, update_at DESC` 是对的（置顶就该钉在顶上），
     * 但工具语义是「最近发生了什么」——按置顶排会让置顶会话永远霸榜，
     * limit 小的时候返回的全是收藏置顶、真正的最近活动被挤掉（2026-08-11 修）。
     *
     * LEFT JOIN agent_session 用于识别/排除 agent 会话（子 agent、定时任务、监督查岗
     * 都在 agent_session 里有行）——调用方查「用户最近在聊什么」时，agent 自己的
     * 工作对话是噪音，尤其查岗 agent 会把自己捞出来。
     */
    @Query(
        "SELECT c.id AS id, c.assistant_id AS assistantId, c.title AS title, " +
            "c.is_pinned AS isPinned, c.create_at AS createAt, c.update_at AS updateAt, " +
            "c.folder_id AS folderId, s.template_id AS agentTemplateId, s.status AS agentStatus " +
            "FROM conversationentity c LEFT JOIN agent_session s ON s.child_id = c.id " +
            "WHERE (:assistantId IS NULL OR c.assistant_id = :assistantId) " +
            "AND (:excludeAgents = 0 OR s.child_id IS NULL) " +
            "AND (:excludeId IS NULL OR c.id != :excludeId) " +
            "AND (:sinceMillis IS NULL OR c.update_at >= :sinceMillis) " +
            "ORDER BY c.update_at DESC LIMIT :limit"
    )
    suspend fun getRecentConversationRows(
        assistantId: String?,
        excludeAgents: Boolean,
        excludeId: String?,
        sinceMillis: Long?,
        limit: Int,
    ): List<RecentConversationRow>

    @Query("SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)

    @Query("UPDATE conversationentity SET folder_id = '' WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    /**
     * 删除某 workspace 时清理对话级挂载引用。
     *
     * workspaceId 是纯对话级两态字段（''=不挂载），所以这里把所有挂着它的对话
     * 一律清成「不挂载」即可，不存在「继承助手」需要额外考虑的分支。
     *
     * 注意：只改 DB。对话正开着时 ChatService 内存里那份 Conversation 仍带旧 id，
     * 由 WorkspaceRepository 侧负责同步内存态（见调用点）。
     */
    @Query("UPDATE conversationentity SET workspace_id = '' WHERE workspace_id = :workspaceId")
    suspend fun clearWorkspaceId(workspaceId: String)

    @Query("UPDATE conversationentity SET workspace_id = :workspaceId WHERE assistant_id = :assistantId AND workspace_id = ''")
    suspend fun backfillWorkspaceIdOfAssistant(assistantId: String, workspaceId: String): Int

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)

/** [ConversationDAO.getRecentConversationRows] 的投影行：会话摘要 + agent 身份 */
data class RecentConversationRow(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String,
    val agentTemplateId: String?,
    val agentStatus: String?,
)
