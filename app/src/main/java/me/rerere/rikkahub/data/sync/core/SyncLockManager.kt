package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.d1.D1Client
import me.rerere.rikkahub.data.sync.d1.D1Exception
import me.rerere.rikkahub.data.sync.d1.D1Schema

private const val TAG = "SyncLockManager"

/**
 * 会话级互斥锁（P2）：D1 locks 表 + 单语句 CAS，防双设备同时改写同一会话。
 *
 * 语义（plan §3.5 / §5.1 #4）：
 * - acquire：INSERT .. ON CONFLICT DO UPDATE .. WHERE 锁过期或本就姓我 →
 *   受影响行数 >0 即拿到锁，否则被 [SyncLock] 持有者占用
 * - renew：会话生成期间每 30s 续期（TTL 90s），返回 false 表示锁被"强制接管"偷走
 * - release：本机释放；绝不删别人的锁
 * - 时钟假设：两台设备走 NTP，允许秒级漂移，TTL 90s 留有充足余量
 */
class SyncLockManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val httpClient: HttpClient,
) {
    data class SyncLock(
        val deviceId: String,
        val deviceName: String,
        val op: String,
        val expiresAt: Long,
    ) {
        fun remainingSec(now: Long = System.currentTimeMillis()): Int =
            ((expiresAt - now) / 1000L).toInt().coerceAtLeast(0)
    }

    sealed interface AcquireResult {
        data object Acquired : AcquireResult
        data class Blocked(val lock: SyncLock) : AcquireResult
    }

    fun isEnabled(): Boolean = settingsStore.settingsFlow.value.d1Config.isConfigured

    private val myDeviceId: String get() = SyncLocalPrefs.deviceId(context)
    private val myDeviceName: String get() = SyncLocalPrefs.deviceName()

    private fun requireClient(): D1Client? {
        val cfg = settingsStore.settingsFlow.value.d1Config
        if (!cfg.isConfigured) return null
        return D1Client(cfg, httpClient)
    }

    suspend fun acquire(convId: String, op: String, force: Boolean = false): AcquireResult {
        val client = requireClient() ?: return AcquireResult.Acquired
        val myId = myDeviceId
        return runCatching {
            D1Schema.ensure(client)
            val now = System.currentTimeMillis()
            val expiry = now + LOCK_TTL_MS
            val sql = if (force) {
                "INSERT INTO locks(conv_id, device_id, device_name, op, acquired_at, expires_at) VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(conv_id) DO UPDATE SET device_id=excluded.device_id, device_name=excluded.device_name, " +
                    "op=excluded.op, acquired_at=excluded.acquired_at, expires_at=excluded.expires_at"
            } else {
                "INSERT INTO locks(conv_id, device_id, device_name, op, acquired_at, expires_at) VALUES(?,?,?,?,?,?) " +
                    "ON CONFLICT(conv_id) DO UPDATE SET device_id=excluded.device_id, device_name=excluded.device_name, " +
                    "op=excluded.op, acquired_at=excluded.acquired_at, expires_at=excluded.expires_at " +
                    "WHERE locks.expires_at < ? OR locks.device_id = ?"
            }
            val params = buildList<Any?> {
                add(convId); add(myId); add(myDeviceName); add(op); add(now); add(expiry)
                if (!force) { add(now); add(myId) }
            }
            val result = client.query(sql, params)
            if (result.changes > 0) {
                AcquireResult.Acquired
            } else {
                AcquireResult.Blocked(currentLock(client, convId) ?: SyncLock("", "", op, expiry))
            }
        }.getOrElse { e ->
            // 锁服务不可达时放行本地操作：同步本来就允许离线之后 LWW 收敛
            Log.w(TAG, "acquire failed, degrade to local-only", D1Exception("lock acquire $convId", e))
            AcquireResult.Acquired
        }
    }

    /** 续期；true=仍姓我，false=被偷/丢了 */
    suspend fun renew(convId: String): Boolean {
        val client = requireClient() ?: return true
        val expiry = System.currentTimeMillis() + LOCK_TTL_MS
        return runCatching {
            client.query(
                "UPDATE locks SET expires_at = ? WHERE conv_id = ? AND device_id = ?",
                listOf(expiry, convId, myDeviceId)
            ).changes > 0
        }.getOrElse {
            // 网络抖动不当偷锁处理：下一次心跳再判
            Log.w(TAG, "renew failed for $convId", it)
            true
        }
    }

    suspend fun release(convId: String) {
        val client = requireClient() ?: return
        runCatching {
            client.query(
                "DELETE FROM locks WHERE conv_id = ? AND device_id = ?",
                listOf(convId, myDeviceId)
            )
        }.onFailure { Log.w(TAG, "release failed for $convId", it) }
    }

    /** 当前锁（含本机持有的）；过期视为无锁返回 null */
    suspend fun currentLock(convId: String): SyncLock? {
        val client = requireClient() ?: return null
        return runCatching { currentLock(client, convId) }.getOrNull()
    }

    private suspend fun currentLock(client: D1Client, convId: String): SyncLock? {
        val row = client.query(
            "SELECT device_id, device_name, op, expires_at FROM locks WHERE conv_id = ?",
            listOf(convId)
        ).results.firstOrNull() ?: return null
        val expiresAt = row.col("expires_at")?.toLongOrNull() ?: return null
        if (expiresAt < System.currentTimeMillis()) return null
        return SyncLock(
            deviceId = row.col("device_id") ?: "",
            deviceName = row.col("device_name") ?: "",
            op = row.col("op") ?: "",
            expiresAt = expiresAt,
        )
    }

    private fun JsonObject.col(name: String): String? =
        this[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    companion object {
        /** 锁 TTL：心跳 30s 的 3 倍 */
        const val LOCK_TTL_MS = 90_000L
        const val HEARTBEAT_MS = 30_000L
    }
}
