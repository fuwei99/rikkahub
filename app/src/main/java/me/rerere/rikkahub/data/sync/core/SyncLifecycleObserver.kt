package me.rerere.rikkahub.data.sync.core

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope

/**
 * 前后台生命周期挂钩（P1）：
 * - ON_START：推积压 + 静默拉差异（Room Flow 自动刷新 UI）
 * - ON_STOP ：入队 WorkManager 兜底 + 立即尽力推积压
 */
class SyncLifecycleObserver(
    private val context: Context,
    private val engine: SyncEngine,
    private val appScope: AppScope,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        SnapshotWorker.enqueuePeriodic(context)
        appScope.launch { engine.onForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        AutoSyncWorker.enqueue(context)
        appScope.launch { engine.onBackground() }
    }
}
