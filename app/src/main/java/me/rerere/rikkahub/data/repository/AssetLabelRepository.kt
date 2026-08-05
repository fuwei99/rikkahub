package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.AssetLabelDAO
import me.rerere.rikkahub.data.db.entity.AssetLabelEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.sync.core.BUNDLE_ASSET_LABELS
import me.rerere.rikkahub.data.sync.core.SyncApplyGate

/**
 * 相册标签 / 附加分类的读写口。
 *
 * 每次写入都会往 outbox 塞一条 bundle 同步任务，语义与 FilesRepository 一致：
 * 同一个 refKey 先删后插，多次改动只留最后一条，避免推 N 遍全量 JSON。
 */
class AssetLabelRepository(
    private val dao: AssetLabelDAO,
    private val database: AppDatabase,
) {
    private suspend fun enqueueSync() {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val outbox = database.syncOutboxDao()
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, BUNDLE_ASSET_LABELS)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = BUNDLE_ASSET_LABELS,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** assetId -> 该资产的标签值集合。相册一次性取全表，避免每个网格项各查一次 */
    fun observeTagMap(): Flow<Map<String, Set<String>>> =
        dao.observeAll().map { labels ->
            labels.asSequence()
                .filter { it.kind == AssetLabelEntity.KIND_TAG }
                .groupBy { it.assetId }
                .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.value } }
        }

    /** assetId -> 附加分类集合（物理 folder 之外额外归属的分类） */
    fun observeFolderMap(): Flow<Map<String, Set<String>>> =
        dao.observeAll().map { labels ->
            labels.asSequence()
                .filter { it.kind == AssetLabelEntity.KIND_FOLDER }
                .groupBy { it.assetId }
                .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.value } }
        }

    suspend fun getTags(assetId: String): Set<String> =
        dao.getByAsset(assetId)
            .filter { it.kind == AssetLabelEntity.KIND_TAG }
            .mapTo(mutableSetOf()) { it.value }

    /** 覆盖式设置某张图的标签集合 */
    suspend fun setTags(assetId: String, tagIds: Collection<String>) {
        database.withTransaction {
            dao.deleteByAssetAndKind(assetId, AssetLabelEntity.KIND_TAG)
            if (tagIds.isNotEmpty()) {
                dao.insertAll(
                    tagIds.distinct().map {
                        AssetLabelEntity(assetId = assetId, kind = AssetLabelEntity.KIND_TAG, value = it)
                    }
                )
            }
        }
        enqueueSync()
    }

    suspend fun addTag(assetId: String, tagId: String) {
        dao.insert(AssetLabelEntity(assetId = assetId, kind = AssetLabelEntity.KIND_TAG, value = tagId))
        enqueueSync()
    }

    suspend fun removeTag(assetId: String, tagId: String) {
        dao.delete(assetId, AssetLabelEntity.KIND_TAG, tagId)
        enqueueSync()
    }

    /** 批量给多张图加同一个标签（相册批量操作用） */
    suspend fun addTagToAll(assetIds: Collection<String>, tagId: String) {
        if (assetIds.isEmpty()) return
        dao.insertAll(
            assetIds.distinct().map {
                AssetLabelEntity(assetId = it, kind = AssetLabelEntity.KIND_TAG, value = tagId)
            }
        )
        enqueueSync()
    }

    suspend fun removeTagFromAll(assetIds: Collection<String>, tagId: String) {
        if (assetIds.isEmpty()) return
        assetIds.distinct().forEach { dao.delete(it, AssetLabelEntity.KIND_TAG, tagId) }
        enqueueSync()
    }

    suspend fun addFolder(assetId: String, folder: String) {
        dao.insert(AssetLabelEntity(assetId = assetId, kind = AssetLabelEntity.KIND_FOLDER, value = folder))
        enqueueSync()
    }

    suspend fun removeFolder(assetId: String, folder: String) {
        dao.delete(assetId, AssetLabelEntity.KIND_FOLDER, folder)
        enqueueSync()
    }

    suspend fun listFolders(): List<String> = dao.getValues(AssetLabelEntity.KIND_FOLDER)

    suspend fun deleteFolder(folder: String) {
        dao.deleteByKindAndValue(AssetLabelEntity.KIND_FOLDER, folder)
        enqueueSync()
    }

    /** 用户在设置里删了某个标签：清掉所有图上的残留引用 */
    suspend fun purgeTag(tagId: String) {
        dao.deleteByLabel(AssetLabelEntity.KIND_TAG, tagId)
        enqueueSync()
    }
}
