package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
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
        val id = dao.insert(file)
        enqueueBundleSync()
        return file.copy(id = id)
    }

    suspend fun update(file: ManagedFileEntity) {
        dao.update(file)
        enqueueBundleSync()
    }

    suspend fun getById(id: Long): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    suspend fun deleteById(id: Long): Int = dao.deleteById(id).also { enqueueBundleSync() }

    suspend fun deleteByPath(relativePath: String): Int = dao.deleteByPath(relativePath).also { enqueueBundleSync() }

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder).also { enqueueBundleSync() }
}
