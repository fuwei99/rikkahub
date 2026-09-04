package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private var urgentPushJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        startUrgentPushListener()
        startOutboxRetrySweeper()
        registerNetworkCallback()
    }

    /**
     * Urgent Push 监听器：收到信号立即触发 pushOnly，跳过 debounce。
     *
     * 聊天消息落库 / 生成完成后通过 [SyncBundleEnqueuer.emitUrgent] 发信号，
     * 这里消费后直接推，端到端延迟从 3s debounce 降到 < 200ms。
     */
    private fun startUrgentPushListener() {
        urgentPushJob?.cancel()
        urgentPushJob = appScope.launch {
            SyncBundleEnqueuer.urgentSignal.collect {
                if (syncAdvancedConfigStore.current.autoSyncEnabled) {
                    runCatching { engine.pushOnly() }
                        .onFailure { Log.w(TAG, "urgent push failed", it) }
                }
            }
        }
    }

    /**
     * 退避唤醒器。
     *
     * `countFlow()` 只在条数变化时触发，而瞬时失败（没网）不改变条数 ——
     * 于是退避到期后没有任何人会再来推它，除非用户碰巧再写一条数据。
     * 这里定时把「已过退避时间」的项重新推一遍，保证"来网了自己好"。
     */
    private fun startOutboxRetrySweeper() {
        outboxRetryJob = appScope.launch {
            while (isActive) {
                delay(OUTBOX_RETRY_SWEEP_INTERVAL_MS)
                if (!syncAdvancedConfigStore.current.autoSyncEnabled) continue
                val dao = database.syncOutboxDao()
                val ready = runCatching {
                    dao.pending(now = System.currentTimeMillis(), limit = 1).isNotEmpty()
                }.getOrDefault(false)
                if (ready) {
                    runCatching { engine.pushOnly() }
                        .onFailure { Log.w(TAG, "outbox retry sweep failed", it) }
                }
            }
        }
    }

    /**
     * 网络恢复即清退避、立刻重推：没网时攒着，来网了马上走，不必等下一个清扫周期。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
                appScope.launch {
                    runCatching {
                        // 只清退避，不动隔离区：隔离是"数据有毒"的判定，与联网无关
                        database.syncOutboxDao().clearBackoff()
                        engine.pushOnly()
                    }.onFailure { Log.w(TAG, "network-restore push failed", it) }
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    override fun onStop(owner: LifecycleOwner) {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        foregroundPullJob?.cancel()
        foregroundPullJob = null
        urgentPushJob?.cancel()
        urgentPushJob = null
        outboxRetryJob?.cancel()
        outboxRetryJob = null
        unregisterNetworkCallback()
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
        AutoSyncWorker.enqueue(context)
        appScope.launch { engine.onBackground() }
    }

    private companion object {
        private const val TAG = "SyncLifecycleObserver"
        private const val DISABLED_POLL_CHECK_INTERVAL_MS = 60_000L
        /** 退避唤醒周期：比最短退避（1s）宽松，够密到用户察觉不到延迟 */
        private const val OUTBOX_RETRY_SWEEP_INTERVAL_MS = 30_000L
    }
}
