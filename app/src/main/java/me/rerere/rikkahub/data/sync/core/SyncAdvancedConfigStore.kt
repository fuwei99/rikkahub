package me.rerere.rikkahub.data.sync.core

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

@Serializable
data class SyncAdvancedConfig(
    /**
     * 自动同步总开关。关闭后仅手动按钮会触发同步，
     * 发消息/退后台/WorkManager 全不碰网络。
     */
    val autoSyncEnabled: Boolean = true,
    val foregroundPullIntervalMs: Long = 60_000L,
    val outboxFlushDebounceMs: Long = 3_000L,
    val circuitBreakerFailureThreshold: Int = 10,
    val circuitBreakerCooldownMs: Long = 3_600_000L,
    /**
     * 单轮 outbox flush 处理项数上限（写量护栏，2026-09-17）。
     *
     * 触顶即中止本轮并写审计 `flush-round-cap`。它防的不是「一直报错」，
     * 而是「一轮把积压几百项全推出去」这种写入风暴 —— 后者会在写入仍然
     * 成功的情况下把 D1 日写入额度直接顶穿，完全绕过失败熔断。
     */
    val writeGuardMaxItemsPerRound: Int = 200,
    /**
     * 配额退避：识别到 D1「写入额度耗尽」后，暂停推送一小段再自动探测。
     *
     * ## 为什么不是「锁到次日 UTC 零点」
     *
     * D1 文档写「次日 UTC 零点重置」，但 2026-09-18 实测同日即可写回
     * （20:27 报耗尽、23:50 写成功）——恢复时刻不可预知，硬锁等于白白
     * 浪费恢复后的那段窗口。
     *
     * 现改为指数退避：5min → 15min → 30min → 60min 封顶，成功一次清零
     * （见 SyncEngine.quotaBackoffMs / recordSuccess）。
     *
     * 退避窗口内**手动同步也不得复位**（见 SyncEngine.guardEntry）：
     * 窗口内「再来一次」改变不了服务端状态，只会白烧请求；窗口过后
     * 手动同步可正常触发重试。
     */
    val quotaBreakerEnabled: Boolean = true,
    val mediaUploadBatchLimit: Int = 8,
    val mediaUploadMaxRetries: Int = 8,
    val mediaUploadMaxBackoffMinutes: Int = 60,
    /**
     * P3 node 级增量上传（S5 关双写开关）：
     * - false（默认）：双写 —— node diff 写 conv_nodes + 整包写 conversations.data，
     *   老客户端兼容，但上行仍是整包量级
     * - true：仅 node —— push 只写 conv_nodes 增量 + conversations 元数据列，
     *   上行降到「一条消息」量级；要求两端都已升级到支持 conv_nodes 的版本，
     *   否则另一端 pull 会读不到（它只认 conversations.data）
     */
    val nodeOnlyPush: Boolean = true,

    // ---- T7 跨端即时信令（CF Worker 广播）----

    /**
     * 信令总开关。关闭后完全退回轮询行为（本功能是加速通道，非数据通道）。
     */
    val notifyEnabled: Boolean = true,

    /**
     * 信令 Worker 根地址。Worker 只转发 room 哈希 + 变更引用，不接触 D1 凭证。
     * 留空等同于关闭。
     */
    val notifyWorkerUrl: String = DEFAULT_NOTIFY_WORKER_URL,

    // ---- Sync Proxy Worker（D1 批量 SQL 代理）----

    /**
     * 代理总开关。
     *
     * 关掉即回到「客户端直连 Cloudflare REST API」的原始链路 —— 只是慢，功能无差别。
     * 与 [notifyEnabled] 一样，这是加速通道而非数据通道。
     */
    val syncProxyEnabled: Boolean = true,

    /**
     * 代理 Worker 根地址。留空等同于关闭。
     *
     * Worker 用 D1 binding 访问数据库（同机房，~1ms/条），因此它**不需要也拿不到**
     * 你的 D1 API Token；泄露该地址最坏只能让人拿着 secret 读写这一个库。
     */
    val syncProxyUrl: String = DEFAULT_SYNC_PROXY_URL,

    /** 访问代理的 Bearer token，需与 Worker 侧 `SYNC_SECRET` 一致 */
    val syncProxySecret: String = "",

    /**
     * 代理不可用时是否自动回落 REST 直连。
     *
     * 默认开启（可用性优先）。关掉它意味着「宁可这轮同步失败也不静默走慢链路」——
     * 排查代理问题时把它关掉，否则 Worker 挂了只表现为「同步又变慢了」，
     * 你根本不知道它已经没在工作。
     */
    val syncProxyFallbackToRest: Boolean = true,

    /** 单批语句上限，超出自动分块；需 ≤ Worker 侧 MAX_STATEMENTS（200） */
    val syncProxyMaxBatchSize: Int = 100,

    /** 代理请求超时（毫秒） */
    val syncProxyTimeoutMs: Long = 20_000L,

    // ---- 快速同步（2026-09-19）----
    //
    // 设备本地的高频小数据同步通道。当前只支持屏幕时间。
    // 走独立 Worker + R2，与 D1 云同步彻底解耦 —— 不占 D1 写入额度，
    // 也不受 D1 配额熔断影响。
    //
    // 全部参数由「偏好设置 → 快速同步」页填写，代码里不预置任何端点或密钥。

    /**
     * 快速同步总开关。
     *
     * 关掉后采集链照跑（本地 Room 仍有本机数据），但不再推/拉 Worker。
     */
    val quickSyncEnabled: Boolean = false,

    /**
     * Worker 根地址，形如 `https://screentime.example.com`。**留空即关闭。**
     *
     * 故意不给默认值：端点属于部署细节，不该编死在 APK 里。
     * Worker 只做「设备 → R2 → 设备」的中继，不碰 D1。
     */
    val quickSyncUrl: String = "",

    /** 访问 Worker 的 Bearer token。**留空即关闭。** 同样不预置默认值。 */
    val quickSyncSecret: String = "",

    /** 单次推送回溯天数（历史日聚合结算后冻结，推多了纯属浪费上行） */
    val quickSyncPushLookbackDays: Int = 3,

    /** 单次拉取回溯天数（历史数据本地已有，只需保证对端「此刻」是最新的） */
    val quickSyncPullLookbackDays: Int = 7,

    // ---- 快速同步 · 调度 ----

    /**
     * 调度模式：
     * - [QUICK_SYNC_MODE_WINDOW]：在 [quickSyncWindowStart] ~ [quickSyncWindowEnd]
     *   时间窗内，从起点起每 [quickSyncIntervalMinutes] 分钟跑一次
     * - [QUICK_SYNC_MODE_FIXED]：每天按 [quickSyncFixedTimes] 列出的时刻跑
     */
    val quickSyncScheduleMode: String = QUICK_SYNC_MODE_WINDOW,

    /** 时间窗起点（HH:mm），仅 window 模式生效 */
    val quickSyncWindowStart: String = "08:00",

    /** 时间窗终点（HH:mm），仅 window 模式生效 */
    val quickSyncWindowEnd: String = "22:40",

    /** 时间窗内间隔分钟数，仅 window 模式生效 */
    val quickSyncIntervalMinutes: Int = 10,

    /**
     * 每天固定时刻表，逗号分隔的 HH:mm，仅 fixed 模式生效。
     * 例：`09:00,12:00,18:00,22:00`
     */
    val quickSyncFixedTimes: String = "09:00,12:00,18:00,22:00",

    /**
     * 配置文件迁移版本号。
     *
     * 为什么必须有它：`SyncAdvancedConfig` 的默认值只对**没有配置文件**的全新安装生效。
     * 老设备磁盘上已经躺着一份 json，改 Kotlin 默认值对它零影响 —— 0904 那轮
     * 「调低轮询间隔」之所以在两台老设备上完全没生效，就是踩了这个坑。
     * 因此凡是需要作用于存量设备的参数变更，都要在 [migrate] 里显式改写并抬版本号。
     */
    val configVersion: Int = CURRENT_CONFIG_VERSION,
) {
    /**
     * 快速同步是否具备发起条件：开关打开 + 地址与密钥都已填。
     * 三者缺一即视为未配置 —— 采集链照跑，但不发任何网络请求。
     */
    val isQuickSyncUsable: Boolean
        get() = quickSyncEnabled && quickSyncUrl.isNotBlank() && quickSyncSecret.isNotBlank()

    fun sanitized(): SyncAdvancedConfig = copy(
        foregroundPullIntervalMs = foregroundPullIntervalMs.takeIf { it >= 0L } ?: 60_000L,
        outboxFlushDebounceMs = outboxFlushDebounceMs.coerceIn(0L, 60_000L),
        circuitBreakerFailureThreshold = circuitBreakerFailureThreshold.coerceIn(1, 100),
        circuitBreakerCooldownMs = circuitBreakerCooldownMs.coerceIn(60_000L, 24L * 60L * 60L * 1000L),
        writeGuardMaxItemsPerRound = writeGuardMaxItemsPerRound.coerceIn(10, 10_000),
        mediaUploadBatchLimit = mediaUploadBatchLimit.coerceIn(1, 64),
        mediaUploadMaxRetries = mediaUploadMaxRetries.coerceIn(1, 50),
        mediaUploadMaxBackoffMinutes = mediaUploadMaxBackoffMinutes.coerceIn(1, 24 * 60),
        syncProxyUrl = syncProxyUrl.trim().trimEnd('/'),
        syncProxySecret = syncProxySecret.trim(),
        // 上限 200 对齐 Worker 的 MAX_STATEMENTS：填更大只会被服务端 413 拒掉
        syncProxyMaxBatchSize = syncProxyMaxBatchSize.coerceIn(1, 200),
        syncProxyTimeoutMs = syncProxyTimeoutMs.coerceIn(3_000L, 120_000L),
        quickSyncUrl = quickSyncUrl.trim().trimEnd('/'),
        quickSyncSecret = quickSyncSecret.trim(),
        quickSyncPushLookbackDays = quickSyncPushLookbackDays.coerceIn(1, 90),
        quickSyncPullLookbackDays = quickSyncPullLookbackDays.coerceIn(1, 90),
        quickSyncScheduleMode =
            if (quickSyncScheduleMode == QUICK_SYNC_MODE_FIXED) QUICK_SYNC_MODE_FIXED else QUICK_SYNC_MODE_WINDOW,
        quickSyncIntervalMinutes = quickSyncIntervalMinutes.coerceIn(1, 240),
    )

    /**
     * 存量配置迁移。只改「明确需要对老设备生效」的字段，用户自定义的其余字段一律保留。
     */
    fun migrate(): SyncAdvancedConfig {
        if (configVersion >= CURRENT_CONFIG_VERSION) return this
        var next = this
        if (configVersion < 1) {
            // v1：接入 T7 信令。老配置文件里根本没有这两个字段，反序列化拿到的是
            // Kotlin 默认值，本身就是对的；这里显式写回一次，保证磁盘上有据可查。
            next = next.copy(
                notifyEnabled = true,
                notifyWorkerUrl = next.notifyWorkerUrl.ifBlank { DEFAULT_NOTIFY_WORKER_URL },
            )
        }
        if (configVersion < 2) {
            // v2：接入 Sync Proxy。开关默认开、地址补默认值，
            // 但 **secret 一律留空** —— 没有 secret 时 `usable` 为 false，
            // 客户端照旧走直连。这样升级本身零行为变化，用户填了 token 才生效。
            next = next.copy(
                syncProxyEnabled = true,
                syncProxyUrl = next.syncProxyUrl.ifBlank { DEFAULT_SYNC_PROXY_URL },
            )
        }
        if (configVersion < 3) {
            // v3：引入「快速同步」（屏幕时间改走独立 Worker + R2，替代 D1 bundle）。
            // 开关默认关、地址与密钥默认空 —— 端点/密钥一律由 UI 填，代码不预置，
            // 因此升级本身零行为变化。
            next = next.copy(
                quickSyncEnabled = false,
                quickSyncUrl = "",
                quickSyncSecret = "",
            )
        }
        return next.copy(configVersion = CURRENT_CONFIG_VERSION)
    }

    companion object {
        /** 当前迁移版本；新增需作用于存量设备的变更时 +1 并在 [migrate] 补分支 */
        const val CURRENT_CONFIG_VERSION = 3

        const val DEFAULT_NOTIFY_WORKER_URL = "https://sync-notify.maltose99.xyz"
        const val DEFAULT_SYNC_PROXY_URL = "https://sync-proxy.maltose99.xyz"

        /** 快速同步调度模式：时间窗内每 N 分钟 */
        const val QUICK_SYNC_MODE_WINDOW = "window"

        /** 快速同步调度模式：每天固定时刻表 */
        const val QUICK_SYNC_MODE_FIXED = "fixed"
    }
}

class SyncAdvancedConfigStore(
    private val context: Context,
) {
    private val file: File = File(AppPaths.filesDir(context), "config/sync_advanced.json")
    private val _config = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<SyncAdvancedConfig> = _config.asStateFlow()

    val current: SyncAdvancedConfig
        get() = _config.value

    suspend fun update(transform: (SyncAdvancedConfig) -> SyncAdvancedConfig) {
        val next = transform(_config.value).sanitized()
        _config.value = next
        saveConfig(next)
    }

    suspend fun reset() {
        val next = SyncAdvancedConfig()
        _config.value = next
        saveConfig(next)
    }

    private fun loadConfig(): SyncAdvancedConfig {
        val loaded = runCatching {
            if (!file.isFile) return@runCatching SyncAdvancedConfig()
            JsonInstant.decodeFromString<SyncAdvancedConfig>(file.readText()).sanitized()
        }.onFailure {
            Log.w(TAG, "load sync advanced config failed", it)
        }.getOrDefault(SyncAdvancedConfig())

        val migrated = loaded.migrate()
        if (migrated != loaded) {
            // 迁移结果立刻落盘，避免每次启动重复迁移；写失败不影响本次运行时取值
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(JsonInstant.encodeToString(migrated))
                Log.i(TAG, "sync advanced config migrated to v${migrated.configVersion}")
            }.onFailure { Log.w(TAG, "persist migrated config failed", it) }
        }
        return migrated
    }

    private suspend fun saveConfig(config: SyncAdvancedConfig) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JsonInstant.encodeToString(config))
        }.onFailure {
            Log.w(TAG, "save sync advanced config failed", it)
        }
    }

    companion object {
        private const val TAG = "SyncAdvancedConfigStore"
        const val RELATIVE_PATH = "config/sync_advanced.json"
    }
}
