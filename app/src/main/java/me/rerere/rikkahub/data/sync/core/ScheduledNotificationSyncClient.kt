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
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationItem
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager

private const val TAG = "ScheduledNotifSync"

@Serializable
private data class SchedPushBody(
    val deviceId: String,
    val deviceLabel: String,
    val items: List<ScheduledNotificationItem>,
)

@Serializable
private data class SchedPushResult(
    val success: Boolean = false,
    val items: Int = 0,
)

@Serializable
private data class SchedPullDevice(
    val deviceId: String = "",
    val deviceLabel: String = "",
    val updatedAt: Long = 0,
    val items: List<ScheduledNotificationItem> = emptyList(),
)

@Serializable
private data class SchedPullResult(
    val success: Boolean = false,
    val devices: Int = 0,
    val records: List<SchedPullDevice> = emptyList(),
)

/**
 * 定时通知的跨设备同步客户端（2026-09-19）。
 *
 * ## 它替代了什么
 *
 * 定时通知原先随 `BUNDLE_SCHEDULED_NOTIFICATIONS` 走 D1 bundle —— 每次增删改都
 * 往 D1 写一整包 JSON。D1 写额度本来就被对话同步吃满，再挂一个只会雪上加霜。
 * 现在改走屏幕时间那个 Worker + R2，对象前缀 `sched/`。
 *
 * ## 合并语义
 *
 * 条目是「按 [ScheduledNotificationItem.id] 的 LWW + 墓碑」集合：
 * 同 id 比 `updatedAt`，`deleted = true` 是墓碑（删了也要同步，否则对端会复活）。
 * 这套合并逻辑 D1 路径已经在用（[ScheduledNotificationManager.replaceFromSync]），
 * 这里直接复用，不重新发明 —— 两条路合并规则不一致才是真正的 bug 温床。
 *
 * ## 契约
 *
 * - [pushOwn]：把本机**全量**条目（含墓碑）整包 POST 上去
 * - [pullAndMerge]：拉别的设备的条目，走 `replaceFromSync` 合并落盘并重排 alarm
 */
class ScheduledNotificationSyncClient(
    private val httpClient: HttpClient,
    private val configStore: SyncAdvancedConfigStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun isUsable(): Boolean = configStore.current.isQuickSyncUsable

    /**
     * 推本机全量条目（含墓碑）到 Worker。
     *
     * 推全量而不是增量：条目数量级很小（几十条），整包覆盖幂等；
     * 增量要维护水位，水位一错就永久丢条，不划算。
     *
     * @return Worker 侧确认落库的条数；未配置时返回 0。
     */
    suspend fun pushOwn(context: Context): Int {
        val c = configStore.current
        if (!isUsable()) return 0

        val items = ScheduledNotificationManager.getAllItems(context)
        val deviceId = SyncLocalPrefs.deviceId(context)
        val deviceLabel = SyncLocalPrefs.deviceLabel(context)

        val body = json.encodeToString(
            SchedPushBody.serializer(),
            SchedPushBody(deviceId = deviceId, deviceLabel = deviceLabel, items = items),
        )
        val response = httpClient.post("${c.quickSyncUrl}/sched/push") {
            header(HttpHeaders.Authorization, "Bearer ${c.quickSyncSecret}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(SchedPushResult.serializer(), text) }.getOrNull()
        if (parsed?.success != true) {
            throw IllegalStateException("scheduled notification push failed: HTTP ${response.status.value} ${text.take(300)}")
        }
        return parsed.items
    }

    /**
     * 拉别的设备的条目并合并。
     *
     * 合并后调用 [ScheduledNotificationManager.replaceFromSync]：它内部做
     * merge + 落盘 + 重排 alarm，且 `enqueueSync = false`，**不会**把 D1 额度再点着。
     *
     * @return 本次纳入合并的对端条目数（0 = 没有对端数据，没动本地）。
     */
    suspend fun pullAndMerge(context: Context): Int {
        val c = configStore.current
        if (!isUsable()) return 0

        val selfId = SyncLocalPrefs.deviceId(context)
        val response = httpClient.get("${c.quickSyncUrl}/sched/pull") {
            header(HttpHeaders.Authorization, "Bearer ${c.quickSyncSecret}")
            parameter("exclude", selfId)
        }
        val text = response.bodyAsText()
        val parsed = runCatching { json.decodeFromString(SchedPullResult.serializer(), text) }.getOrNull()
            ?: run {
                Log.w(TAG, "pull: unparseable response HTTP ${response.status.value}: ${text.take(200)}")
                return 0
            }
        if (!parsed.success) return 0

        val remote = parsed.records
            .filter { it.deviceId.isNotBlank() && it.deviceId != selfId }
            .flatMap { it.items }
        if (remote.isEmpty()) return 0

        ScheduledNotificationManager.replaceFromSync(context, remote)
        return remote.size
    }
}
