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
    fun sanitized(): SyncAdvancedConfig = copy(
        foregroundPullIntervalMs = foregroundPullIntervalMs.takeIf { it >= 0L } ?: 60_000L,
        outboxFlushDebounceMs = outboxFlushDebounceMs.coerceIn(0L, 60_000L),
        circuitBreakerFailureThreshold = circuitBreakerFailureThreshold.coerceIn(1, 100),
        circuitBreakerCooldownMs = circuitBreakerCooldownMs.coerceIn(60_000L, 24L * 60L * 60L * 1000L),
        mediaUploadBatchLimit = mediaUploadBatchLimit.coerceIn(1, 64),
        mediaUploadMaxRetries = mediaUploadMaxRetries.coerceIn(1, 50),
        mediaUploadMaxBackoffMinutes = mediaUploadMaxBackoffMinutes.coerceIn(1, 24 * 60),
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
        return next.copy(configVersion = CURRENT_CONFIG_VERSION)
    }

    companion object {
        /** 当前迁移版本；新增需作用于存量设备的变更时 +1 并在 [migrate] 补分支 */
        const val CURRENT_CONFIG_VERSION = 1

        const val DEFAULT_NOTIFY_WORKER_URL = "https://sync-notify.maltose99.xyz"
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
