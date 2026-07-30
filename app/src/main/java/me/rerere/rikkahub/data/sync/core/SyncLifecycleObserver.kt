package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * 前后台生命周期挂钩（P1）：
 * - ON_START：推积压 + 静默拉差异（Room Flow 自动刷新 UI）
 * - 前台停留：定时静默拉差异，避免另一台设备写入后本机长时间不刷新
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
    private var foregroundPullJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        SnapshotWorker.enqueuePeriodic(context)
        foregroundSyncJob?.cancel()
        foregroundPullJob?.cancel()
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
        foregroundPullJob = appScope.launch {
            while (isActive) {
                delay(FOREGROUND_PULL_INTERVAL_MS)
                runCatching { engine.syncOnce() }
                    .onFailure { Log.w(TAG, "foreground periodic sync failed", it) }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        foregroundPullJob?.cancel()
        foregroundPullJob = null
        AutoSyncWorker.enqueue(context)
        appScope.launch { engine.onBackground() }
    }

    private companion object {
        private const val TAG = "SyncLifecycleObserver"
        private const val FOREGROUND_PULL_INTERVAL_MS = 30_000L
    }
}
