package me.rerere.rikkahub.data.sync.core

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * 前后台生命周期挂钩（P1）：
 * - ON_START：推积压 + 静默拉差异（Room Flow 自动刷新 UI）
 * - ON_STOP ：入队 WorkManager 兜底 + 立即尽力推积压
 */
@OptIn(FlowPreview::class)
class SyncLifecycleObserver(
    private val context: Context,
    private val engine: SyncEngine,
    private val appScope: AppScope,
    private val database: AppDatabase,
) : DefaultLifecycleObserver {
    private var foregroundSyncJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        SnapshotWorker.enqueuePeriodic(context)
        foregroundSyncJob?.cancel()
        foregroundSyncJob = appScope.launch {
            engine.onForeground()
            database.syncOutboxDao().countFlow()
                .drop(1)
                .distinctUntilChanged()
                .debounce(5_000L)
                .collect { count ->
                    if (count > 0) engine.flushPending()
                }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        AutoSyncWorker.enqueue(context)
        appScope.launch { engine.onBackground() }
    }
}
