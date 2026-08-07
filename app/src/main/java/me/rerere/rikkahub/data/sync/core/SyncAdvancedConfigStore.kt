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
    val foregroundPullIntervalMs: Long = 300_000L,
    val outboxFlushDebounceMs: Long = 3_000L,
    val circuitBreakerFailureThreshold: Int = 10,
    val circuitBreakerCooldownMs: Long = 3_600_000L,
    val mediaUploadBatchLimit: Int = 8,
    val mediaUploadMaxRetries: Int = 8,
    val mediaUploadMaxBackoffMinutes: Int = 60,
) {
    fun sanitized(): SyncAdvancedConfig = copy(
        foregroundPullIntervalMs = foregroundPullIntervalMs.takeIf { it >= 0L } ?: 300_000L,
        outboxFlushDebounceMs = outboxFlushDebounceMs.coerceIn(0L, 60_000L),
        circuitBreakerFailureThreshold = circuitBreakerFailureThreshold.coerceIn(1, 100),
        circuitBreakerCooldownMs = circuitBreakerCooldownMs.coerceIn(60_000L, 24L * 60L * 60L * 1000L),
        mediaUploadBatchLimit = mediaUploadBatchLimit.coerceIn(1, 64),
        mediaUploadMaxRetries = mediaUploadMaxRetries.coerceIn(1, 50),
        mediaUploadMaxBackoffMinutes = mediaUploadMaxBackoffMinutes.coerceIn(1, 24 * 60),
    )
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
        return runCatching {
            if (!file.isFile) return@runCatching SyncAdvancedConfig()
            JsonInstant.decodeFromString<SyncAdvancedConfig>(file.readText()).sanitized()
        }.onFailure {
            Log.w(TAG, "load sync advanced config failed", it)
        }.getOrDefault(SyncAdvancedConfig())
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
