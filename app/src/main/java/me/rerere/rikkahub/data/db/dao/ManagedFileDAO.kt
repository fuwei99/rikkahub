package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity

@Dao
interface ManagedFileDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: ManagedFileEntity): Long

    @Update
    suspend fun update(file: ManagedFileEntity)

    @Query("SELECT * FROM managed_files WHERE id = :id")
    suspend fun getById(id: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE relative_path = :relativePath")
    suspend fun getByPath(relativePath: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE r2_key = :r2Key AND r2_acct = :r2Acct LIMIT 1")
    suspend fun getByR2Ref(r2Key: String, r2Acct: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE external_url = :externalUrl LIMIT 1")
    suspend fun getByExternalUrl(externalUrl: String): ManagedFileEntity?

    @Query("SELECT * FROM managed_files WHERE sha256 = :sha256 LIMIT 1")
    suspend fun getBySha256(sha256: String): ManagedFileEntity?

    @Query("SELECT ocr_text FROM managed_files WHERE id = :id")
    suspend fun getOcrText(id: String): String?

    @Query("UPDATE managed_files SET ocr_text = :text, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateOcrText(id: String, text: String, updatedAt: Long)

    @Query("SELECT * FROM managed_files ORDER BY created_at DESC")
    suspend fun getAllFiles(): List<ManagedFileEntity>

    @Query("DELETE FROM managed_files")
    suspend fun deleteAll()

    @Query("SELECT * FROM managed_files WHERE folder = :folder AND deleted = 0 ORDER BY created_at DESC")
    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>>

    @Query("DELETE FROM managed_files WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM managed_files WHERE relative_path = :relativePath")
    suspend fun deleteByPath(relativePath: String): Int

    @Query("DELETE FROM managed_files WHERE folder = :folder")
    suspend fun deleteByFolder(folder: String): Int
}
