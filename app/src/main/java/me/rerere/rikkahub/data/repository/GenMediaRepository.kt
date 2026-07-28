package me.rerere.rikkahub.data.repository

import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.BUNDLE_GENMEDIA
import me.rerere.rikkahub.data.sync.core.SyncApplyGate

class GenMediaRepository(
    private val dao: GenMediaDAO,
    private val database: AppDatabase,
) {
    /** 云锚点同步写钩（P1）：genmedia 整表 bundle 入待推队列 */
    private suspend fun enqueueBundleSync() {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_GENMEDIA)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_GENMEDIA,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun getAllMediaList(): List<GenMediaEntity> = dao.getAllMedia()

    suspend fun insertMedia(media: GenMediaEntity) {
        dao.insert(media)
        enqueueBundleSync()
    }

    suspend fun deleteMedia(id: Int) {
        dao.delete(id)
        enqueueBundleSync()
    }

    suspend fun deleteMediaByPath(path: String): Int = dao.deleteByPath(path).also {
        enqueueBundleSync()
    }
}
