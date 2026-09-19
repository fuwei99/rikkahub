package me.rerere.rikkahub.data.sync.core

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
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SupervisionEvent
import me.rerere.rikkahub.data.model.SupervisionEventLog

private const val TAG = "SupervisionSyncClient"

@Serializable
private data class SupPushBody(
    val deviceId: String,
    val deviceLabel: String,
    val events: List<SupervisionEvent>,
)

@Serializable
private data class SupPushResult(
    val success: Boolean = false,
    val events: Int = 0,
)

@Serializable
private data class SupPullDevice(
    val deviceId: String = "",
    val deviceLabel: String = "",
    val updatedAt: Long = 0,
    val events: List<SupervisionEvent> = emptyList(),
)

@Serializable
private data class SupPullResult(
    val success: Boolean = false,
    val devices: Int = 0,
    val records: List<SupPullDevice> = emptyList(),
)

/**
 * 监督锁事件日志的跨设备同步客户端（2026-09-19）。
 *
 * ## 它替代了什么
 *
 * 监督锁原先只随 `settings.supervision` 分片走 D1 bundle，一轮 pull 串行几十条
 * SQL，实测几十秒。而锁要的是「一端锁上，另一端**立刻**看见」—— 这个延迟不可接受。
 * 现在复用屏幕时间那个 Worker（`rikkahub-screentime-sync`）+ R2：强一致、写后读立即可见。
 *
 * ## 为什么不需要冲突裁决
 *
 * 云端存的是**事件集合**，不是锁状态。事件是 OR-Set（按 [SupervisionEvent.id] 去重），
 * 合并就是**取并集** —— 只增不减，因此天然满足「宁可多锁，不能少锁」。
 *
 * 「解锁」在数学上不是「一个更弱的状态」，而是**一个 hlc 更大的事件**；
 * 谁新谁说话，由 `SupervisionEventLog.fold` 按 hlc 全序重放决定。
 * 所以这里根本不需要「以哪台设备为准」这类规则 —— 并集之后两端必然收敛到同一结果。
 *
 * ## 契约
 *
 * - [pushOwn]：把**本机全量**事件整包 POST 上去（Worker 侧覆盖 `sup/<deviceId>.json`）
 * - [pullAndMerge]：拉别的设备的事件，`eventLog.merge` 并集后写回本地
 *
 * ⚠️ 绝不在此处调用 [SettingsStore.appendSupervisionEvent]（§3.4）：云端来的事件是
 * **已存在的事实**，只能 merge；重新产生会打上本机新 hlc，两端互相「重新产生」
 * 同一个事件永不收敛。
 */
class SupervisionSyncClient(
    private val httpClient: HttpClient,
    private val configStore: SyncAdvancedConfigStore,
    private val settingsStore: SettingsStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 是否具备发起同步的条件（开关 + 地址 + token 都齐）。复用 quick sync 的配置。 */
    fun isUsable(): Boolean = configStore.current.isQuickSyncUsable

    /**
     * 推本机**全量**监督事件到 Worker。
     *
     * 推全量而不是增量：事件是 OR-Set，整包覆盖是幂等的；而增量要维护水位，
     * 水位一错就是永久丢事件。事件很小（一条几百字节），全量更省心。
     *
     * @return Worker 侧确认落库的事件条数；未配置时返回 0。
     */
    suspend fun pushOwn(context: Context): Int {
        val c = configStore.current
        if (!isUsable()) return 0

        val events = settingsStore.settingsFlow.value.supervision.eventLog.events
        val deviceId = SyncLocalPrefs.deviceId(context)
        val deviceLabel = SyncLocalPrefs.deviceLabel(context)

        val body = json.encodeToString(
            SupPushBody.serializer(),
            SupPushBody(deviceId = deviceId, deviceLabel = deviceLabel, events = events),
        )
        val response = httpClient.post("${c.quickSyncUrl}/sup/push") {
            header(HttpHeaders.Authorization, "Bearer ${c.quickSyncSecret}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(SupPushResult.serializer(), text) }.getOrNull()
        if (parsed?.success != true) {
            throw IllegalStateException("supervision push failed: HTTP ${response.status.value} ${text.take(300)}")
        }
        return parsed.events
    }

    /**
     * 拉别的设备的事件，按 [SupervisionEventLog.merge]（OR-Set 并集）写回本地。
     *
     * 本机 deviceId 会被 `exclude` 排除（防回环），客户端还会再判一次。
     *
     * @return 实际新增的事件条数（0 = 没有新事实，没写库）。
     */
    suspend fun pullAndMerge(context: Context): Int {
        val c = configStore.current
        if (!isUsable()) return 0

        val selfId = SyncLocalPrefs.deviceId(context)
        val response = httpClient.get("${c.quickSyncUrl}/sup/pull") {
            header(HttpHeaders.Authorization, "Bearer ${c.quickSyncSecret}")
            parameter("exclude", selfId)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(SupPullResult.serializer(), text) }.getOrNull()
            ?: run {
                Log.w(TAG, "pull: unparseable response HTTP ${response.status.value}: ${text.take(200)}")
                return 0
            }
        if (!parsed.success) return 0

        val local = settingsStore.settingsFlow.value.supervision
        var mergedLog = local.eventLog
        var added = 0
        for (device in parsed.records) {
            if (device.deviceId.isBlank() || device.deviceId == selfId) continue
            if (device.events.isEmpty()) continue
            val before = mergedLog.events.size
            mergedLog = mergedLog.merge(SupervisionEventLog(device.events))
            added += mergedLog.events.size - before
        }
        if (added <= 0) return 0

        // 与 appendSupervisionEvent 同一条写入路径：事件并集是单调增加，
        // 必须 bypassGate —— 否则 Gate 的「只许加严」规则会把它回滚。
        settingsStore.updateSupervisionByAdmin(
            local.copy(eventLog = mergedLog),
            bypassGate = true,
        )
        return added
    }
}
