package me.rerere.rikkahub.data.screentime

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ScreenTimeDayEntity
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.data.sync.core.SyncLocalPrefs
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val TAG = "ScreenTimeSyncClient"

/** push 单日负载：直接搬运 Room 里的原始列，不做重序列化（避免 hydrate/offload 表示漂移） */
@Serializable
private data class PushDay(
    val date: String,
    val totalMs: Long,
    val appsJson: String = "",
    val hourlyJson: String = "",
    val updatedAt: Long = 0,
)

@Serializable
private data class PushBody(
    val deviceId: String,
    val deviceLabel: String,
    val days: List<PushDay>,
)

@Serializable
private data class PushResult(
    val success: Boolean = false,
    val days: Int = 0,
)

@Serializable
private data class PullDay(
    val date: String = "",
    val totalMs: Long = 0,
    val appsJson: String = "",
    val hourlyJson: String = "",
    val updatedAt: Long = 0,
)

@Serializable
private data class PullDevice(
    val deviceId: String = "",
    val deviceLabel: String = "",
    val updatedAt: Long = 0,
    val days: Map<String, PullDay> = emptyMap(),
)

@Serializable
private data class PullResult(
    val success: Boolean = false,
    val devices: Int = 0,
    val records: List<PullDevice> = emptyList(),
)

/**
 * 屏幕时间跨设备同步客户端（2026-09-19）。
 *
 * ## 它替代了什么
 *
 * 原先屏幕时间走 D1 bundle（`screen_time:<deviceId>`），一轮 pull 串行几十条 SQL，
 * 实测几十秒 —— 查岗 Agent 要的是「另一台设备此刻在干嘛」，这个延迟不可接受。
 * 现在改走独立 Worker + R2：强一致、写后读立即可见。
 *
 * ## 契约
 *
 * - [pushOwn]：把本机最近 N 天的日聚合整包 POST 上去（Worker 侧按 date merge）
 * - [pullAndMerge]：拉别的设备的最近 N 天，按 `updatedAt` 做 LWW 后写回本地 Room
 *
 * 两边都只搬运 Room 的原始列（appsJson/hourlyJson 原样传），不做结构转换 ——
 * 这样不会出现「同一份数据两种序列化表示」的 sha 漂移问题。
 */
class ScreenTimeSyncClient(
    private val httpClient: HttpClient,
    private val configStore: SyncAdvancedConfigStore,
    private val database: AppDatabase,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 是否具备发起同步的条件（开关 + 地址 + token 都齐） */
    fun isUsable(): Boolean {
        val c = configStore.current
        return c.quickSyncEnabled && c.quickSyncUrl.isNotBlank() && c.quickSyncSecret.isNotBlank()
    }

    private fun isoDaysAgo(days: Int): String =
        LocalDate.now().minusDays((days - 1).coerceAtLeast(0).toLong())
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

    /**
     * 推本机最近 [SyncAdvancedConfigStore] 配置的天数到 Worker。
     *
     * @return Worker 侧确认落库的天数；未配置或无可推数据时返回 0。
     */
    suspend fun pushOwn(context: Context): Int {
        val c = configStore.current
        if (!isUsable()) return 0

        val deviceId = SyncLocalPrefs.deviceId(context)
        val deviceLabel = SyncLocalPrefs.deviceLabel(context)
        val cutoff = isoDaysAgo(c.quickSyncPushLookbackDays)

        val days = database.screenTimeDayDao().getByDevice(deviceId)
            .filter { it.date >= cutoff }
            .map {
                PushDay(
                    date = it.date,
                    totalMs = it.totalMs,
                    appsJson = it.appsJson,
                    hourlyJson = it.hourlyJson,
                    updatedAt = it.updatedAt,
                )
            }
        if (days.isEmpty()) return 0

        val body = json.encodeToString(
            PushBody.serializer(),
            PushBody(deviceId = deviceId, deviceLabel = deviceLabel, days = days),
        )
        val response = httpClient.post("${c.quickSyncUrl}/push") {
            header(HttpHeaders.Authorization, "Bearer ${c.quickSyncSecret}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(PushResult.serializer(), text) }.getOrNull()
        if (parsed?.success != true) {
            throw IllegalStateException("screen time push failed: HTTP ${response.status.value} ${text.take(300)}")
        }
        return parsed.days
    }

    /**
     * 拉别的设备的最近 [SyncAdvancedConfigStore] 配置的天数，LWW 合并进本地 Room。
     *
     * 本机 deviceId 会被 `exclude` 排除（防回环）；同 (deviceId, date) 上
     * 只有当远端 `updatedAt` 严格更新时才覆盖本地。
     *
     * @return 实际写入/更新的行数。
     */
    suspend fun pullAndMerge(context: Context): Int {
        val c = configStore.current
        if (!isUsable()) return 0

        val selfId = SyncLocalPrefs.deviceId(context)
        val cutoff = isoDaysAgo(c.quickSyncPullLookbackDays)

        val response = httpClient.get("${c.quickSyncUrl}/pull") {
            header(HttpHeaders.Authorization, "Bearer ${c.quickSyncSecret}")
            parameter("exclude", selfId)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(PullResult.serializer(), text) }.getOrNull()
            ?: run {
                Log.w(TAG, "pull: unparseable response HTTP ${response.status.value}: ${text.take(200)}")
                return 0
            }
        if (!parsed.success) return 0

        val dao = database.screenTimeDayDao()
        var written = 0
        for (device in parsed.records) {
            if (device.deviceId.isBlank() || device.deviceId == selfId) continue
            for ((date, day) in device.days) {
                if (date < cutoff) continue
                val local = dao.get(device.deviceId, date)
                if (local != null && local.updatedAt >= day.updatedAt) continue
                dao.upsert(
                    ScreenTimeDayEntity(
                        deviceId = device.deviceId,
                        deviceLabel = device.deviceLabel.ifBlank { local?.deviceLabel ?: device.deviceId },
                        date = date,
                        totalMs = day.totalMs,
                        appsJson = day.appsJson,
                        hourlyJson = day.hourlyJson,
                        updatedAt = day.updatedAt,
                    )
                )
                written++
            }
        }
        return written
    }
}
