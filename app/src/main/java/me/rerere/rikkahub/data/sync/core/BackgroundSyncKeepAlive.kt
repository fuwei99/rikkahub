package me.rerere.rikkahub.data.sync.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * 后台跑 Schedule Agent 期间的同步保活（2026-09-11）。
 *
 * ## 为什么需要
 *
 * Schedule Agent 走 AlarmManager + setExactAndAllowWhileIdle + RTC_WAKEUP，
 * 能捅穿 Doze 把 CPU 叫醒，息屏关机照跑；而同步在 [SyncLifecycleObserver.onStop]
 * 里被主动全砍了（拉取轮询取消、T7 信令断开、网络回调注销），只剩一个 WorkManager
 * 兜底 —— 而 WorkManager 是「尽力而为」，进 Doze 后可能几十分钟才轮一次，
 * 华为 EMUI 上后台冻结的 App 更是能冻到你重新打开为止。
 *
 * 结果就是：**闹钟醒着，同步睡着**。Agent 在后台生成的会话全堆在 sync_outbox 里，
 * 等用户下次点开 App 一次性推上云 —— 而这正是制造冲突和 Fork 的高危时刻。
 *
 * ## 做法
 *
 * 引用计数保活。Agent 派活时 [acquire]，那一轮生成结束时 [release]，
 * 计数 > 0 期间起一个循环持续 syncOnce。反正 CPU 已经被闹钟唤醒了，
 * 这时候联网基本是白蹭的，不额外耗电。
 *
 * 计数归零自动停，不留常驻循环。
 *
 * ## 边界
 *
 * - 只在后台生效：前台有 onStart 的轮询 + T7 信令，不需要它掺和，
 *   否则两条腿一起拉会撞在一起。由 [SyncLifecycleObserver] 设置 [foreground]。
 * - 进程被杀时计数直接随进程没了，不需要持久化：下次冷启动本来就会全量同步一次。
 * - 同一会话重复 acquire 只算一次（用 Set 而非计数器），防止 deliverWhenBusy
 *   反复投递把计数刷上去后再也降不回零。
 */
object BackgroundSyncKeepAlive {
    private const val TAG = "BgSyncKeepAlive"

    /** 保活期间的同步间隔。比前台轮询松，够把积压推出去就行，不跟前台抢。 */
    private const val SYNC_INTERVAL_MS = 20_000L

    /**
     * 单次保活的兜底上限。防止某个会话卡在 running 永远不 release
     * （生成挂死 / onGenerationDone 没被触发）导致循环变常驻耗电。
     */
    private const val MAX_KEEPALIVE_MS = 15 * 60 * 1000L

    private val mutex = Mutex()

    /** 当前正在后台跑的定时任务会话；用 Set 保证重复投递不会把计数刷爆 */
    private val activeSessions = mutableSetOf<Uuid>()

    private var loopJob: Job? = null

    @Volatile
    private var foreground: Boolean = false

    private var engineProvider: (() -> SyncEngine?)? = null
    private var scope: CoroutineScope? = null

    /** 由 DI 在 App 启动时接线；拿不到 engine 时保活自动降级为空操作 */
    fun attach(scope: CoroutineScope, engineProvider: () -> SyncEngine?) {
        this.scope = scope
        this.engineProvider = engineProvider
    }

    /** 前台由 SyncLifecycleObserver 的轮询 + T7 信令负责，保活让位 */
    suspend fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            mutex.withLock { stopLoopLocked("entered foreground") }
        } else {
            mutex.withLock { startLoopIfNeededLocked() }
        }
    }

    /** Schedule Agent 派活时调用：这一轮结束前把同步顶着 */
    suspend fun acquire(sessionId: Uuid) {
        mutex.withLock {
            if (!activeSessions.add(sessionId)) return
            Log.i(TAG, "acquire $sessionId (active=${activeSessions.size})")
            startLoopIfNeededLocked()
        }
    }

    /** 那一轮生成结束时调用（onGenerationDone）；非定时任务会话调进来是无害的空操作 */
    suspend fun release(sessionId: Uuid) {
        mutex.withLock {
            if (!activeSessions.remove(sessionId)) return
            Log.i(TAG, "release $sessionId (active=${activeSessions.size})")
            if (activeSessions.isEmpty()) {
                // 收尾再推一次：这一轮刚写完的东西要立刻出门，
                // 否则就得等下次开 App，又回到「积压爆发」的老路上
                flushOnce("last session finished")
                stopLoopLocked("no active sessions")
            }
        }
    }

    private fun startLoopIfNeededLocked() {
        if (foreground) return
        if (activeSessions.isEmpty()) return
        if (loopJob?.isActive == true) return
        val scope = scope ?: return

        loopJob = scope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            Log.i(TAG, "keep-alive loop started")
            while (isActive) {
                if (System.currentTimeMillis() - startedAt > MAX_KEEPALIVE_MS) {
                    Log.w(TAG, "keep-alive hit max duration, stopping")
                    break
                }
                delay(SYNC_INTERVAL_MS)
                if (foreground) break
                syncQuietly("keep-alive tick")
            }
            Log.i(TAG, "keep-alive loop ended")
        }
    }

    private fun stopLoopLocked(reason: String) {
        val job = loopJob ?: return
        loopJob = null
        if (job.isActive) {
            Log.i(TAG, "stopping keep-alive loop: $reason")
            job.cancel()
        }
    }

    private fun flushOnce(reason: String) {
        val scope = scope ?: return
        scope.launch(Dispatchers.IO) { syncQuietly(reason) }
    }

    private suspend fun syncQuietly(reason: String) {
        val engine = engineProvider?.invoke() ?: return
        if (!engine.isConfigured()) return
        runCatching { engine.syncOnce() }
            .onSuccess { Log.d(TAG, "sync ok ($reason)") }
            .onFailure { Log.w(TAG, "sync failed ($reason)", it) }
    }
}
