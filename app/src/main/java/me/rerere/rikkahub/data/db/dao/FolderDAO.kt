package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.FolderEntity

@Dao
interface FolderDAO {
    @Query("SELECT * FROM conversation_folder WHERE assistant_id = :assistantId ORDER BY sort_index ASC, create_at ASC")
    fun getFoldersOfAssistant(assistantId: String): Flow<List<FolderEntity>>

    /** 云锚点同步（P1）：导出全表为 bundle（保留 id，conversation.folder_id 引用之） */
    @Query("SELECT * FROM conversation_folder ORDER BY create_at ASC")
    suspend fun getAllList(): List<FolderEntity>

    /** 云锚点同步（P1）：应用云端全量时清空后重建 */
    @Query("DELETE FROM conversation_folder")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversation_folder WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    /** 按名字取文件夹（agent 模板文件夹复用：同模板永远落同一个夹） */
    @Query("SELECT * FROM conversation_folder WHERE assistant_id = :assistantId AND name = :name ORDER BY create_at ASC LIMIT 1")
    suspend fun getFolderByName(assistantId: String, name: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE conversation_folder SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM conversation_folder WHERE id = :id")
    suspend fun deleteById(id: String)
}
