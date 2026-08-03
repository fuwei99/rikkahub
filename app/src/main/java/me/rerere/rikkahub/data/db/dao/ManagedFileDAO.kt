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

    /**
     * 重命名：只改 name_zh，不动 relative_path。
     *
     * 物理文件名保持 UUID 不变，因为它同时是多端同步的身份标识，
     * 也被历史会话里的 file:// 引用直接指着 —— 改名等于制造死链。
     */
    @Query("UPDATE managed_files SET name_zh = :nameZh, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateNameZh(id: String, nameZh: String?, updatedAt: Long)

    /** OCR 结构化结果回写：描述 / 中英文名一次落库 */
    @Query(
        """
        UPDATE managed_files
        SET ocr_text = :ocrText,
            description = :description,
            name_zh = COALESCE(:nameZh, name_zh),
            name_en = COALESCE(:nameEn, name_en),
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateOcrResult(
        id: String,
        ocrText: String?,
        description: String?,
        nameZh: String?,
        nameEn: String?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM managed_files WHERE content_sha256 = :contentSha256 AND deleted = 0 LIMIT 1")
    suspend fun getByContentSha256(contentSha256: String): ManagedFileEntity?

    @Query("UPDATE managed_files SET content_sha256 = :contentSha256 WHERE id = :id")
    suspend fun updateContentSha256(id: String, contentSha256: String?)

    /**
     * 存量回填用：挑出还没算过内容摘要的图片。
     * 带 limit 是因为回填要读整个文件算 hash，几百张一次性做会卡住 IO。
     */
    @Query(
        """
        SELECT * FROM managed_files
        WHERE content_sha256 IS NULL AND deleted = 0
          AND mime_type LIKE 'image/%'
          AND relative_path NOT LIKE 'remote/%'
        ORDER BY created_at DESC
        LIMIT :limit
        """
    )
    suspend fun listMissingContentSha256(limit: Int): List<ManagedFileEntity>

    @Query("SELECT * FROM managed_files ORDER BY created_at DESC")
    suspend fun getAllFiles(): List<ManagedFileEntity>

    @Query("DELETE FROM managed_files")
    suspend fun deleteAll()

    @Query("SELECT * FROM managed_files WHERE folder = :folder AND deleted = 0 ORDER BY created_at DESC")
    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>>

    @Query("SELECT * FROM managed_files WHERE deleted = 0 ORDER BY created_at DESC")
    fun listAll(): Flow<List<ManagedFileEntity>>

    @Query("DELETE FROM managed_files WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM managed_files WHERE relative_path = :relativePath")
    suspend fun deleteByPath(relativePath: String): Int

    @Query("DELETE FROM managed_files WHERE folder = :folder")
    suspend fun deleteByFolder(folder: String): Int
}
