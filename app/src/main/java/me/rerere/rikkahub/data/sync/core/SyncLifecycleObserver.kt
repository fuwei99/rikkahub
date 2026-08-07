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
 * 前后台生命周期挂钩：
 * - ON_START：推积压 + 静默拉差异（Room Flow 自动刷新 UI）
 * - 前台停留：定时静默拉差异，避免另一台设备写入后本机长时间不刷新
 * - ON_STOP ：入队 WorkManager 兜底 + 立即尽力推积压
 *
 * 全部受 [SyncAdvancedConfig.autoSyncEnabled] 控制：关闭后只有手动按钮会联网。
 */
@OptIn(FlowPreview::class)
class SyncLifecycleObserver(
    private val context: Context,
    private val engine: SyncEngine,
    private val appScope: AppScope,
    private val database: AppDatabase,
    private val syncAdvancedConfigStore: SyncAdvancedConfigStore,
) : DefaultLifecycleObserver {
    private var foregroundSyncJob: Job? = null
    private var foregroundPullJob: Job? = null

    override fun onStart(owner: LifecycleOwner) {
        SnapshotWorker.enqueuePeriodic(context)
        foregroundSyncJob?.cancel()
        foregroundPullJob?.cancel()
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
        foregroundSyncJob = appScope.launch {
            engine.onForeground()
            database.syncOutboxDao().countFlow()
                .drop(1)
                .distinctUntilChanged()
                .debounce { syncAdvancedConfigStore.current.outboxFlushDebounceMs }
                .collect { count ->
                    // 自动同步可能在运行中被关闭，每次触发前重新确认
                    if (count > 0 && syncAdvancedConfigStore.current.autoSyncEnabled) {
                        engine.pushOnly()
                    }
                }
        }
        foregroundPullJob = appScope.launch {
            while (isActive) {
                val config = syncAdvancedConfigStore.current
                val interval = config.foregroundPullIntervalMs
                if (!config.autoSyncEnabled || interval <= 0L) {
                    delay(DISABLED_POLL_CHECK_INTERVAL_MS)
                    continue
                }
                delay(interval)
                if (!syncAdvancedConfigStore.current.autoSyncEnabled) continue
                runCatching { engine.pullOnly() }
                    .onFailure { Log.w(TAG, "foreground periodic pull failed", it) }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        foregroundPullJob?.cancel()
        foregroundPullJob = null
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
        AutoSyncWorker.enqueue(context)
        appScope.launch { engine.onBackground() }
    }

    private companion object {
        private const val TAG = "SyncLifecycleObserver"
        private const val DISABLED_POLL_CHECK_INTERVAL_MS = 60_000L
    }
}
