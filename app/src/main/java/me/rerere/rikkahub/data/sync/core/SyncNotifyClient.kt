package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.screentime.SCREEN_TIME_BUNDLE_PREFIX
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * T7 跨端即时信令客户端（CF Worker + Durable Object 广播）。
 *
 * ## 它解决什么
 *
 * D1 没有推送能力，此前唯一的跨端感知手段是轮询：前台 30s 全量 pull + 会话内
 * 3~60s 心跳。于是「A 端发完消息，B 端要几十秒才看见」，屏幕时间更是以分钟计。
 * 加大轮询频率能压延迟，但直接烧 D1 行读配额和电量，两头不讨好。
 *
 * 本客户端把「谁写了什么」做成一条**旁路信令**：push 成功后向 Worker 发一个
 * 几十字节的 POST，Worker 把它广播给同房间的其他设备，对端收到后**立刻定向拉取**。
 * 端到端实测 ~0.5~1.5s，且信令本身不携带任何业务数据。
 *
 * ## 安全边界
 *
 * - room = `sha256(D1 databaseId)` 的前 32 位十六进制。同一个 D1 库的设备天然同房间，
 *   不同用户永不串台；Worker 只看到一串哈希，**推不出 databaseId，更拿不到 apiToken**。
 * - 广播内容只有 `kind`（conv/bundle）+ `ref`（会话 id 或 bundle key）+ 发送方设备 id。
 *   收到信令的一端仍然要走自己的 D1 凭证去拉数据，Worker 无法伪造内容，最坏情况
 *   只能骗对端多拉一次（自损配额，无数据风险）。
 *
 * ## 降级策略
 *
 * Worker 挂掉 / 断网 / 用户关闭 → 一切退回原有轮询行为，**没有任何功能依赖它**。
 * 这是加速通道，不是数据通道。
 */
class SyncNotifyClient(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val syncAdvancedConfigStore: SyncAdvancedConfigStore,
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
    okHttpClient: OkHttpClient,
) {
    /**
     * 独立 client：复用全局那个 readTimeout=10min 的 LLM client 会让 WebSocket
     * 断线检测形同虚设。这里用 pingInterval 主动保活，readTimeout=0（长连接不设读超时）。
     */
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var configWatchJob: Job? = null

    @Volatile
    private var wantConnected = false

    /** 收到 bundle 类信令时的合流锁：多条 bundle 变更只触发一次 pullOnly */
    private val bundlePullMutex = Mutex()

    @Volatile
    private var lastBundlePullAt = 0L

    // ---------------- 生命周期 ----------------

    /** 前台启动：建立长连接并自动重连；重复调用幂等 */
    fun start() {
        if (wantConnected) return
        wantConnected = true
        startConfigWatcher()
        connectJob?.cancel()
        connectJob = scope.launch {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive && wantConnected) {
                val url = wsUrl()
                if (url == null) {
                    // 未配置 D1 / 未开启信令：不空转，隔一分钟再看一眼
                    delay(DISABLED_RECHECK_MS)
                    continue
                }
                val connected = runCatching { openSocket(url) }.getOrElse {
                    Log.w(TAG, "notify socket open failed", it)
                    false
                }
                backoff = if (connected) INITIAL_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                delay(backoff)
            }
        }
    }

    /** 退后台：主动断开，绝不在后台持有长连接（省电 + 不占 DO 连接数） */
    fun stop() {
        wantConnected = false
        configWatchJob?.cancel()
        configWatchJob = null
        connectJob?.cancel()
        connectJob = null
        runCatching { webSocket?.close(1000, "background") }
        webSocket = null
    }

    /**
     * 监听设置页改动，地址/开关一变就把当前连接踢掉重连。
     *
     * 没这个的话，用户在设置里改完 Worker 地址得手动切一次前后台才生效，
     * 这种「改了看着没反应」的体验比硬编码还恼人。
     *
     * 只盯这两个字段（distinctUntilChanged），避免其他无关配置变动踩到重连。
     * 首次 emit 是当前值，跳过（drop(1)），不然 start() 会自己把自己断一次。
     */
    private fun startConfigWatcher() {
        configWatchJob?.cancel()
        configWatchJob = scope.launch {
            syncAdvancedConfigStore.configFlow
                .map { it.notifyEnabled to it.notifyWorkerUrl }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    Log.i(TAG, "notify config changed, reconnecting")
                    runCatching { webSocket?.close(1000, "config changed") }
                    webSocket = null
                }
        }
    }

    // ---------------- 出站：push 完成后通知对端 ----------------

    /**
     * 广播一条变更信令。**永远不抛异常、永远不阻塞调用方**：
     * 信令失败对数据一致性零影响，绝不能让它拖垮 push 主链路。
     *
     * @param kind [KIND_CONV] 或 [KIND_BUNDLE]
     * @param ref  会话 id / bundle key
     */
    fun notifyPeers(kind: String, ref: String) {
        val cfg = syncAdvancedConfigStore.current
        if (!cfg.notifyEnabled) return
        val base = cfg.notifyWorkerUrl.trimEnd('/').ifBlank { return }
        val room = roomId() ?: return
        val device = SyncLocalPrefs.deviceId(context)
        scope.launch {
            runCatching {
                val body = buildString {
                    append("{\"room\":\"").append(room)
                    append("\",\"device\":\"").append(device)
                    append("\",\"kind\":\"").append(kind)
                    append("\",\"ref\":\"").append(ref.replace("\"", ""))
                    append("\"}")
                }.toRequestBody(JSON_MEDIA)
                val req = Request.Builder()
                    .url("$base/notify")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { it.code }
            }.onFailure { Log.d(TAG, "notifyPeers ignored failure: ${it.message}") }
        }
    }

    // ---------------- 入站 ----------------

    private suspend fun openSocket(url: String): Boolean {
        val request = Request.Builder().url(url).build()
        var opened = false
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                opened = true
                webSocket = ws
                Log.i(TAG, "notify socket connected")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleSignal(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                if (!done.isCompleted) done.complete(Unit)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                Log.d(TAG, "notify socket failure: ${t.message}")
                if (!done.isCompleted) done.complete(Unit)
            }
        }

        val ws = client.newWebSocket(request, listener)
        try {
            done.await()
        } finally {
            runCatching { ws.cancel() }
        }
        return opened
    }

    private fun handleSignal(text: String) {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return
        val ref = obj["ref"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return

        scope.launch {
            runCatching {
                when {
                    kind == KIND_CONV && ref.isNotBlank() -> engine.pullConversationFast(ref)

                    kind == KIND_BUNDLE && ref.startsWith(SCREEN_TIME_BUNDLE_PREFIX) ->
                        engine.pullScreenTimeNow()

                    kind == KIND_BUNDLE -> pullBundlesCoalesced()

                    else -> Unit
                }
            }.onFailure { Log.w(TAG, "handleSignal($kind/$ref) failed", it) }
        }
    }

    /**
     * bundle 信令合流：对端一次 push 可能连发 settings / folders / memory 多条信令，
     * 逐条 pullOnly 就是几十次行读。这里做最小间隔节流，把一串信令压成一次全量拉取。
     */
    private suspend fun pullBundlesCoalesced() {
        bundlePullMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastBundlePullAt < BUNDLE_PULL_MIN_INTERVAL_MS) return
            lastBundlePullAt = now
        }
        delay(BUNDLE_PULL_COALESCE_MS)
        engine.pullOnly()
    }

    // ---------------- 房间与 URL ----------------

    private fun wsUrl(): String? {
        val cfg = syncAdvancedConfigStore.current
        if (!cfg.notifyEnabled || !cfg.autoSyncEnabled) return null
        val base = cfg.notifyWorkerUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val room = roomId() ?: return null
        val device = SyncLocalPrefs.deviceId(context)
        val wsBase = base
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$wsBase/ws?room=$room&device=$device"
    }

    /**
     * room = sha256(databaseId) 前 32 位。
     *
     * 用哈希而不是 databaseId 原文：Worker 是第三方基础设施，即使日志泄露也
     * 只暴露一串与账号无关的哈希。
     */
    private fun roomId(): String? {
        val dbId = settingsStore.settingsFlow.value.d1Config.databaseId
        if (dbId.isBlank()) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(dbId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    companion object {
        private const val TAG = "SyncNotifyClient"
        const val KIND_CONV = "conv"
        const val KIND_BUNDLE = "bundle"

        private const val PING_INTERVAL_SEC = 30L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val DISABLED_RECHECK_MS = 60_000L

        /** 两次 bundle 全量拉取的最小间隔，防信令风暴烧配额 */
        private const val BUNDLE_PULL_MIN_INTERVAL_MS = 5_000L

        /** 收到首条 bundle 信令后稍等，让同批次的其他信令一起被这次 pull 覆盖 */
        private const val BUNDLE_PULL_COALESCE_MS = 300L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
