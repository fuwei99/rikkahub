package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaUploadOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.BUNDLE_MANAGED_FILES
import me.rerere.rikkahub.data.sync.core.SyncApplyGate

class FilesRepository(
    private val dao: ManagedFileDAO,
    private val database: AppDatabase,
) {
    private suspend fun enqueueBundleSync() {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_MANAGED_FILES)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_MANAGED_FILES,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity {
        val inserted = dao.insert(file)
        enqueueBundleSync()
        return if (inserted == -1L) {
            dao.getByPath(file.relativePath) ?: dao.getById(file.id) ?: file
        } else {
            file
        }
    }

    suspend fun update(file: ManagedFileEntity) {
        dao.update(file)
        enqueueBundleSync()
    }

    /**
     * 请求补一份云副本（幂等入队，由 AssetResolver.processCloudUploadOutbox 消费）。
     *
     * 给 [FilesManager.syncFolder] 用：本地文件没了但索引还得留住的资产，
     * 光保留索引不够，还要把 R2 副本补上才能重新解析出图。这里直接写 outbox 表，
     * 不去依赖 AssetResolver —— AssetResolver 本身依赖 FilesManager，反向注入会成环。
     */
    suspend fun enqueueCloudUpload(assetId: String) {
        runCatching {
            database.mediaUploadOutboxDao().insert(MediaUploadOutboxEntity(assetId = assetId))
        }
    }

    suspend fun getById(id: String): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    /** 重命名（只改中文名，物理文件名保持 UUID 不变） */
    suspend fun rename(id: String, nameZh: String?) {
        dao.updateNameZh(id, nameZh?.trim()?.takeIf { it.isNotEmpty() }, System.currentTimeMillis())
        enqueueBundleSync()
    }

    suspend fun updateOcrResult(
        id: String,
        ocrText: String?,
        description: String?,
        nameZh: String?,
        nameEn: String?,
    ) {
        dao.updateOcrResult(
            id = id,
            ocrText = ocrText,
            description = description,
            nameZh = nameZh?.trim()?.takeIf { it.isNotEmpty() },
            nameEn = nameEn?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = System.currentTimeMillis(),
        )
        enqueueBundleSync()
    }

    suspend fun getBySha256(sha256: String): ManagedFileEntity? = dao.getBySha256(sha256)

    suspend fun getByContentSha256(contentSha256: String): ManagedFileEntity? =
        dao.getByContentSha256(contentSha256)

    suspend fun updateContentSha256(id: String, contentSha256: String?) {
        dao.updateContentSha256(id, contentSha256)
        enqueueBundleSync()
    }

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    fun listAll(): Flow<List<ManagedFileEntity>> = dao.listAll()

    suspend fun deleteById(id: String): Int = dao.deleteById(id).also { enqueueBundleSync() }

    suspend fun deleteByPath(relativePath: String): Int = dao.deleteByPath(relativePath).also { enqueueBundleSync() }

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder).also { enqueueBundleSync() }
}
