package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import okhttp3.OkHttpClient

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
    private val settingsStore: SettingsStore,
    /** T7 信令客户端；为 null 时完全退化为原有轮询行为 */
    private val notifyClient: SyncNotifyClient? = null,
    /** 全局 OkHttp；网络切换时用来驱逐僵尸连接。为 null 时跳过清池。 */
    private val okHttpClient: OkHttpClient? = null,
) : DefaultLifecycleObserver {
    private var foregroundSyncJob: Job? = null
    private var foregroundPullJob: Job? = null
    private var urgentPushJob: Job? = null
    private var outboxRetryJob: Job? = null
    private var configWatchJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStart(owner: LifecycleOwner) {
        SnapshotWorker.enqueuePeriodic(context)
        // 前台有本类的轮询 + T7 信令在管，后台保活让位，免得两条腿一起拉撞在一起
        appScope.launch { BackgroundSyncKeepAlive.setForeground(true) }
        foregroundSyncJob?.cancel()
        // 2026-09-21 修正（Step I-5 收尾 · 装配时机）：开关是**热**的。
        //
        // 旧实现把「装 job」整段塞在 `if (!autoSyncEnabled) return` 之后，而 onStart 只在
        // 生命周期回调时跑一次。用户在前台把自动同步从关拨到开，onStart 不会因此重跑
        // → 四个 job 一个都没装上，**而且不会有任何报错**。现场表现：开关是开的，队列纹丝不动。
        //
        // 现在把「开关 → job」变成订阅关系，无论何时拨动，下一拍就生效。
        configWatchJob?.cancel()
        configWatchJob = appScope.launch {
            syncAdvancedConfigStore.configFlow
                .map { it.autoSyncEnabled }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) startAutoSyncJobs() else stopAutoSyncJobs() }
        }
    }

    /**
     * 装上自动同步的全部前台 job。**幂等**：重复调用不会叠出第二份轮询。
     *
     * `foregroundSyncJob` 兼作「装上了没」的标志位 —— 它是这套里第一个装的。
     */
    private fun startAutoSyncJobs() {
        if (foregroundSyncJob?.isActive == true) return
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
        // T7：前台才持有信令长连接。后台一律断开，不占 DO 连接数也不焊电。
        runCatching { notifyClient?.start() }
            .onFailure { Log.w(TAG, "notify client start failed", it) }
    }

    /** 撤掉全部前台 job。与 [startAutoSyncJobs] 成对，幂等。 */
    private fun stopAutoSyncJobs() {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = null
        foregroundPullJob?.cancel()
        foregroundPullJob = null
        urgentPushJob?.cancel()
        urgentPushJob = null
        outboxRetryJob?.cancel()
        outboxRetryJob = null
        runCatching { notifyClient?.stop() }
            .onFailure { Log.w(TAG, "notify client stop failed", it) }
        unregisterNetworkCallback()
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
        // 幂等：重复装上（开关反复拨动）不能叠出第二份扫描
        outboxRetryJob?.cancel()
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
     *
     * 另一个职责：**网络切换时驱逐 OkHttp 连接池**。
     * WiFi ↔ 移动数据切换、或者 NAT 静默超时后，连接池里那些看似健康的连接已经是尸体；
     * 不清掉的话下一次发消息会复用它、请求掉进黑洞、卡满 readTimeout。
     * 这等于把“手动断网重连才能回复”这个动作自动化了。
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                evictStaleConnections("network available")
                if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
                appScope.launch {
                    runCatching {
                        // 只清退避，不动隔离区：隔离是"数据有毒"的判定，与联网无关
                        database.syncOutboxDao().clearBackoff()
                        engine.pushOnly()
                    }.onFailure { Log.w(TAG, "network-restore push failed", it) }
                }
            }

            override fun onLost(network: Network) {
                evictStaleConnections("network lost")
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    /**
     * 把连接池里的空闲连接全部踢掉。
     *
     * evictAll() 只影响**空闲**连接，正在跑的流式应答不会被截断，所以安全。
     * 放到 IO 线程：NetworkCallback 跑在主线程，不该在那儿碰 socket 关闭。
     */
    private fun evictStaleConnections(reason: String) {
        val client = okHttpClient ?: return
        if (!settingsStore.settingsFlow.value.networkSettings.evictOnNetworkChange) return
        appScope.launch(Dispatchers.IO) {
            runCatching { client.connectionPool.evictAll() }
                .onSuccess { Log.i(TAG, "evicted idle connections ($reason)") }
                .onFailure { Log.w(TAG, "evictAll failed ($reason)", it) }
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { cb ->
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
    }

    override fun onStop(owner: LifecycleOwner) {
        configWatchJob?.cancel()
        configWatchJob = null
        stopAutoSyncJobs()
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
        AutoSyncWorker.enqueue(context)
        // 交棒给后台保活：WorkManager 在 Doze / EMUI 冻结下不可靠，
        // 而 Schedule Agent 靠闹钟照跑 —— 有 agent 在跑就把同步顶着。
        appScope.launch { BackgroundSyncKeepAlive.setForeground(false) }
        appScope.launch { engine.onBackground() }
    }

    private companion object {
        private const val TAG = "SyncLifecycleObserver"
        private const val DISABLED_POLL_CHECK_INTERVAL_MS = 60_000L
        /** 退避唤醒周期：比最短退避（1s）宽松，够密到用户察觉不到延迟 */
        private const val OUTBOX_RETRY_SWEEP_INTERVAL_MS = 30_000L
    }
}
