package me.rerere.rikkahub.data.sync.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import org.koin.core.context.GlobalContext

private const val TAG = "SyncBundleEnqueuer"

/**
 * Lightweight bridge for file/preference backed data that does not go through a Room repository.
 * It only marks the relevant bundle dirty; SyncEngine will serialize the current source of truth.
 */
object SyncBundleEnqueuer {
    fun enqueue(key: String) {
        if (SyncApplyGate.applyingRemote) return
        runCatching {
            val koin = GlobalContext.get()
            val appScope: AppScope = koin.get()
            val database: AppDatabase = koin.get()
            appScope.launch(Dispatchers.IO) {
                runCatching {
                    val outbox = database.syncOutboxDao()
                    outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, key)
                    outbox.insert(
                        SyncOutboxEntity(
                            kind = SyncOutboxEntity.KIND_BUNDLE,
                            refKey = key,
                            op = SyncOutboxEntity.OP_UPSERT,
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                }.onFailure {
                    Log.e(TAG, "enqueue: failed for bundle $key", it)
                }
            }
        }.onFailure {
            Log.e(TAG, "enqueue: Koin unavailable for bundle $key", it)
        }
    }
}
