package me.rerere.rikkahub.data.sync.core

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.ai.util.stripLoneSurrogates
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphLinkEntity
import me.rerere.rikkahub.data.db.entity.MemoryGraphNodeEntity
import me.rerere.rikkahub.data.db.entity.MemoryLinkEntity
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationItem
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import me.rerere.rikkahub.data.db.entity.ScreenTimeDayEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.common.android.SyncPerfLog
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.data.screentime.CLOUD_RETENTION_DAYS
import me.rerere.rikkahub.data.screentime.SCREEN_TIME_BUNDLE_PREFIX
import me.rerere.rikkahub.data.screentime.SCREEN_TIME_HOUR_BUCKETS
import me.rerere.rikkahub.data.screentime.SyncScreenTimeAppItem
import me.rerere.rikkahub.data.screentime.SyncScreenTimeDayItem
import me.rerere.rikkahub.data.sync.d1.D1Client
import me.rerere.rikkahub.data.sync.d1.D1ProxyConfig
import me.rerere.rikkahub.data.sync.d1.D1Schema
import me.rerere.rikkahub.data.sync.backend.StorageBackend
import me.rerere.rikkahub.data.sync.backend.StorageBackendConfig
import me.rerere.rikkahub.data.sync.backend.StorageBackendFactory
import me.rerere.rikkahub.data.sync.backend.StorageBackendRouter
import me.rerere.rikkahub.data.sync.backend.NodeManifestRow
import me.rerere.rikkahub.data.sync.backend.BackendInfo
import me.rerere.rikkahub.data.sync.backend.D1Backend
import me.rerere.rikkahub.data.sync.backend.BundlePushRow
import me.rerere.rikkahub.data.sync.backend.ConversationMetaRow
import me.rerere.rikkahub.data.sync.backend.ConversationPushRow
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import me.rerere.rikkahub.data.vector.GraphVectorStore
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "SyncEngine"

/**
 * 旧 `d1Config` 回落路径的后端 id（多后端抽象 · Step G 过渡期）。
 *
 * 设置页切到 `backends` 列表（Step H）之后，这条回落连同 `requireClient()` 一起拆。
 * 在那之前它的作用是：**升级上来的设备新列表还是空的，但同步照跑**。
 */
private const val LEGACY_D1_BACKEND_ID = StorageBackendConfig.LEGACY_D1_BACKEND_ID

/** bundles 表中的持久 key */
const val BUNDLE_SETTINGS = "settings"
const val BUNDLE_SETTINGS_DISPLAY = "settings.display"
const val BUNDLE_MEMORY = "memory"
const val BUNDLE_MEMORY_LINKS = "memory_links"
const val BUNDLE_MEMORY_GRAPH_NODES = "memory_graph_nodes"
const val BUNDLE_MEMORY_GRAPH_LINKS = "memory_graph_links"
/** 记忆图注册表（方案 2026-08-07 多图体系）：与 nodes/links 同构的整表快照 */
const val BUNDLE_MEMORY_GRAPHS = "memory_graphs"
const val BUNDLE_FAVORITES = "favorites"
const val BUNDLE_FOLDERS = "folders"
const val BUNDLE_GENMEDIA = "genmedia"
const val BUNDLE_MANAGED_FILES = "managed_files"
const val BUNDLE_ASSET_LABELS = "asset_labels"
const val BUNDLE_SUBAGENT_TEMPLATES = "subagent_templates"
const val BUNDLE_SKILLS = "skills"
const val BUNDLE_SCHEDULED_NOTIFICATIONS = "scheduled_notifications"

/**
 * pullAll 每轮需要拉取的固定 bundle 集合，供批量 prefetch 使用。
 *
 * ⚠️ 这里只决定「一次取回哪些」，**不决定应用顺序**。顺序约束（注册表先于节点、
 * 节点先于边校验）仍由 pullAll 里的调用次序保证，改这个列表不会影响它。
 * 新增 bundle 时记得同步加进来，否则它会退回单独一次查询（能用，只是慢一点）。
 */
private val PULL_BUNDLE_KEYS = listOf(
    BUNDLE_SETTINGS,
    BUNDLE_SETTINGS_DISPLAY,
    BUNDLE_MEMORY,
    BUNDLE_MEMORY_LINKS,
    BUNDLE_MEMORY_GRAPHS,
    BUNDLE_MEMORY_GRAPH_LINKS,
    BUNDLE_MEMORY_GRAPH_NODES,
    BUNDLE_FAVORITES,
    BUNDLE_FOLDERS,
    BUNDLE_GENMEDIA,
    BUNDLE_MANAGED_FILES,
    BUNDLE_ASSET_LABELS,
    BUNDLE_SUBAGENT_TEMPLATES,
    BUNDLE_SKILLS,
    // 2026-09-19: BUNDLE_SCHEDULED_NOTIFICATIONS 移出 D1 分片列表 ——
    // 定时通知已改走 Worker + R2（ScheduledNotificationSyncClient），
    // 不再占用 D1 写额度。
)

/**
 * 跨设备屏幕时间（方案 2026-08-09）：key = screen_time:<deviceId>，按设备隔离，
 * 前缀常量与 payload 模型定义在 data.screentime（采集器/工具共用）。
 */
const val BUNDLE_SCREEN_TIME_PREFIX = SCREEN_TIME_BUNDLE_PREFIX

/** conversations 增量拉取水位（sync_state 本地键，非云端 bundle） */
private const val STATE_CONV_WATERMARK = "sync:conv_watermark"

/**
 * 全量对账周期。
 *
 * 水位增量拉取对「迟到写入」零容忍：设备 A 关同步一段时间，期间设备 B 的水位
 * 涨过了 A 的写入时间戳；A 重开同步把旧时间戳的行推上去，B 查 `updated_at > 水位`
 * 直接把它漏掉，而且**永久漏掉** —— 水位只涨不落，再也不会回头。
 *
 * 2026-09-11 现场：MatePad 水位 21:17，k70 在 20:46 写的 1adda01c 永远拉不到；
 * 全库对账发现 52 个会话在云端存在、MatePad 本地缺失，最老的到 08-24。
 *
 * 因此每 [CONV_RECONCILE_INTERVAL_MS] 做一次全量清单对账：不看水位，扫全表清单，
 * 把「本地缺失」或「sha 不一致」的会话补回来。清单只有 id/updated_at/sha/deleted，
 * 两千行也就百来 KB，远小于漏一整个会话历史的代价。
 */
private const val CONV_RECONCILE_INTERVAL_MS = 10 * 60 * 1000L

/** 上次全量对账时间戳（ms）。与水位分开记，互不干扰。 */
private const val STATE_CONV_RECONCILE_AT = "sync:conv_reconcile_at"

/**
 * 水位 / 对账时间戳的**按后端分键**（多后端 · Step I-5）。
 *
 * 多后端之后水位必须一后端一份：每个库各有一张 conversations 表、各有一个
 * `updated_at` 涨势。共用一根水位就会出现「A 库涨到 9 点，于是 B 库 8 点的行
 * 被判成已处理」→ **跨库永久漏拉**（形态与 2026-09-11 那次 52 会话漏拉同源）。
 *
 * 旧配置（legacy d1Config）**沿用无后缀的老键**，让升级上来的设备水位不丢；
 * 新后端从 0 起（首轮全量清单，反正库是空的，代价为零）。
 */
private fun convWatermarkKey(backendId: String): String =
    if (backendId == StorageBackendConfig.LEGACY_D1_BACKEND_ID) STATE_CONV_WATERMARK
    else "$STATE_CONV_WATERMARK:$backendId"

private fun convReconcileKey(backendId: String): String =
    if (backendId == StorageBackendConfig.LEGACY_D1_BACKEND_ID) STATE_CONV_RECONCILE_AT
    else "$STATE_CONV_RECONCILE_AT:$backendId"

/** P3 node 级本地状态前缀：sync_state 键 = 该前缀 + convId，value = {"nodes":{nodeId:sha}} */
private const val STATE_CONV_NODES_PREFIX = "sync:convnodes:"

/** 批量取 conversation data 的单批上限（D1 位置参数有上限，留足余量） */
private const val CONV_DATA_FETCH_CHUNK = 20

/**
 * 批量预取 node 清单的单批会话数上限。
 *
 * 清单行很小（只有元数据，无 data），一次多带些会话反而划算：
 * 关键成本是往返次数而非单次体积。60 个会话一次查完，
 * 对应 48 个变动会话的典型场景只需 1 次往返（原先要 48 次）。
 */
private const val CONV_MANIFEST_PREFETCH_CHUNK = 60

/** T7 信令类型：与 [SyncNotifyClient.KIND_CONV] / [SyncNotifyClient.KIND_BUNDLE] 一致 */
private const val SIGNAL_KIND_CONV = "conv"
private const val SIGNAL_KIND_BUNDLE = "bundle"

@Serializable
private data class SyncSubagentTemplateItem(
    val filename: String,
    val content: String,
)

@Serializable
private data class SyncSkillFileItem(
    val relativePath: String,
    val updatedAt: Long,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val r2Ref: String? = null,
    val bytesBase64: String? = null,
)

@Serializable
private data class SyncManagedFileItem(
    val id: String,
    val folder: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val r2Key: String? = null,
    val r2Acct: String? = null,
    val externalUrl: String? = null,
    val sha256: String? = null,
    val contentSha256: String? = null,
    val nameZh: String? = null,
    val nameEn: String? = null,
    val prompt: String? = null,
    val description: String? = null,
    val ocrText: String? = null,
    val deleted: Boolean = false,
)

@Serializable
private data class SyncAssetLabelItem(
    val assetId: String,
    val kind: String,
    val value: String,
    val createdAt: Long,
)

@Serializable
private data class SyncMemoryItem(
    // id 自 P1 起导出：链接表以 memoryentity.id 为引用，跨端必须稳定。
    // 老客户端(ignoreUnknownKeys)会忽略该字段；老 payload 缺 id 时回落自动自增。
    val id: Int = 0,
    val assistantId: String,
    val content: String,
)

@Serializable
private data class SyncMemoryLinkItem(
    val id: Long,
    val sourceId: Int,
    val targetId: Int,
    val type: String = "related",
    val weight: Float = 0.7f,
    val description: String = "",
    val scope: String,
    val createdAt: Long,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val supersededById: Long? = null,
)

@Serializable
private data class SyncMemoryGraphNodeItem(
    val id: Long,
    val scope: String,
    val title: String,
    val content: String,
    val importance: Float = 0.5f,
    /** 匹配资格分层：0=常驻池 always，1=门控池 gated（关联节点激活后才解锁）。老 payload 缺省按常驻池。 */
    val matchEligibility: Int = 0,
    val folderPath: String? = null,
    val sourceConversationId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 图注册表整表快照项（与 nodes/links 同构；不做 tombstone，见 review2 §三） */
@Serializable
private data class SyncMemoryGraphItem(
    val id: String,
    val slug: String,
    val name: String,
    val description: String = "",
    val kind: String = "CUSTOM",
    val boundAssistantId: String? = null,
    val emoji: String? = null,
    val builtin: Boolean = false,
    val createdBy: String = "USER",
    val sortOrder: Int = 0,
    val autoExtractTarget: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
private data class SyncMemoryGraphLinkItem(
    val id: Long,
    val scope: String,
    val sourceId: Long,
    val targetId: Long,
    val type: String = "related",
    val weight: Float = 0.7f,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private data class SyncFavoriteItem(
    val id: String,
    val type: String,
    val refKey: String,
    val refJson: String,
    val snapshotJson: String,
    val metaJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private data class SyncFolderItem(
    val id: String,
    val assistantId: String,
    val name: String,
    val sortIndex: Int = 0,
    val createAt: Long,
)

@Serializable
private data class SyncGenMediaItem(
    val path: String,
    val modelId: String,
    val prompt: String,
    val createAt: Long,
    val type: String,
    val sourcePaths: String? = null,
    // P3 云资产列（可空向后兼容：旧包没有这三个字段）
    val r2Key: String? = null,
    val r2Acct: String? = null,
    val originalUrl: String? = null,
    val originalAssetId: String? = null,
    val previewAssetId: String? = null,
)

/**
 * 云锚点同步引擎（P1）。
 *
 * 模型：D1 为唯一文本事实源；本地 Room = 可重建缓存 + outbox 写缓冲。
 * - 写：Repository 写钩 → sync_outbox → flush（乐观锁 UPDATE..WHERE updated_at=base，
 *   0 行命中 → INSERT OR IGNORE → 仍失败按 LWW 冲突处理）
 * - 读：启动/回前台拉 manifest 与 sync_state 比对，仅拉差异，DAO upsert，
 *   Room Flow 自动刷新 UI；全程 [SyncApplyGate] 抑制回环
 */
class SyncEngine(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    private val httpClient: HttpClient,
    private val json: Json,
    private val r2MediaStore: R2MediaStore,
    private val syncAdvancedConfigStore: SyncAdvancedConfigStore,
    private val graphVectorStore: GraphVectorStore,
    private val memoryGraphRegistry: MemoryGraphRegistry,
    /**
     * HLC 时钟。**必须由 DI 注入同一个单例**：它靠实例内 `last` + `synchronized`
     * 维持单调，两个实例会在同一毫秒各自从 counter=0 发号 → 重复 hlc → 因果失真。
     */
    private val syncClock: SyncClock,
) {
    private val pushMutex = Mutex()
    private val pullMutex = Mutex()
    private var schemaEnsured = false

    /**
     * settings 分片双写器（v2 §2.6，阶段 A 第 6 项）。
     *
     * 懒初始化：`SyncEngine` 在 DI 图里构造得比较早，而这里要用到
     * `SyncClock`（单例）与 Room 的 `sync_field_version` DAO。
     * 懒到第一次真正推送时再建，避开初始化顺序问题。
     *
     * 用 `by lazy` 而不是每次 new：`SyncFieldStamper` 本身无状态，但
     * `SyncClock` **必须是全进程同一个实例**（它靠实例内 last + synchronized
     * 维持单调，两个实例会在同一毫秒各自从 counter=0 发号 → 重复 hlc）。
     */
    private val settingsShardPusher: SettingsShardPusher by lazy {
        SettingsShardPusher(
            clock = syncClock,
            bootstrapGuard = SyncBootstrapGuard(database.syncStateDao()),
            stamper = SyncFieldStamper(database.syncFieldVersionDao(), syncClock),
            deviceId = SyncLocalPrefs.deviceId(context),
        )
    }

    /** 最后一次成功同步时间（仅内存，UI 展示用） */
    private val _lastSyncedAt = MutableStateFlow(0L)
    val lastSyncedAt: StateFlow<Long> = _lastSyncedAt.asStateFlow()

    /**
     * 分叉另存回调（conversationId, 分支标题）。
     * ChatService 在初始化时注入，避免 SyncEngine 直接依赖 ChatService 造成 Koin 循环。
     */
    @Volatile
    var onConversationForked: ((String, String) -> Unit)? = null

    /**
     * T7 信令广播钩子。由 [SyncNotifyClient] 在初始化时注入（而非构造注入）：
     * SyncNotifyClient 本身依赖 SyncEngine 去发起 pull，直接构造依赖会形成 Koin 环。
     *
     * 签名：(kind, ref) -> Unit。实现必须自己吃掉所有异常，信令失败绝不能影响 push。
     */
    @Volatile
    var onPushed: ((String, String) -> Unit)? = null

    /** pull 内存下需要在 ApplyGate 关闭后重推的 bundle key */
    private val pendingRepush = mutableSetOf<String>()

    /** pull 重建后本地含云端缺失节点、需要回推的会话 id（P3） */
    private val pendingRepushConversations = mutableSetOf<String>()

    private var consecutiveFailures = 0
    private var circuitBreakerOpenTime: Long = 0L

    /**
     * 配额退避截止时间（epoch ms）。> now 表示因 D1 写入额度耗尽而暂停推送。
     *
     * ## 为什么不是「锁到次日 UTC 零点」
     *
     * D1 文档写的是「次日 UTC 零点重置」，但 2026-09-18 实测打脸：
     * 20:27 报 7500 耗尽，23:50 **同一个 UTC 日内**真写测试却成功了 ——
     * 配额判定并非死板的自然日硬切（存在延迟回收 / 波动）。
     *
     * 硬锁到次日零点会让「额度提前恢复」这段窗口白白浪费。改用**指数退避探测**：
     * 撞墙后退避 5 分钟，到期自动重试；再撞就 15 → 30 → 60 分钟递增，
     * 成功一次立即清零阶梯。
     *
     * 独立于普通失败熔断：手动同步的 [resetCircuitBreaker] 刻意不清它 ——
     * 退避窗口内「再来一次」没有意义，但窗口过后手动同步可正常重试。
     */
    @Volatile
    private var quotaExhaustedUntil: Long = 0L

    /** 连续撞配额墙的次数，驱动退避阶梯；任意一次成功推送后清零 */
    @Volatile
    private var quotaHitStreak: Int = 0

    private val _isCircuitBreakerOpen = MutableStateFlow(false)
    val isCircuitBreakerOpen: StateFlow<Boolean> = _isCircuitBreakerOpen.asStateFlow()

    /** 配额熔断是否打开（UI / 诊断用） */
    val isQuotaBreakerOpen: Boolean get() = quotaExhaustedUntil > System.currentTimeMillis()

    fun resetCircuitBreaker() {
        consecutiveFailures = 0
        circuitBreakerOpenTime = 0L
        _isCircuitBreakerOpen.value = false
        // 刻意不动 quotaExhaustedUntil：见字段注释。
    }

    private fun checkCircuitBreaker(): Boolean {
        val now = System.currentTimeMillis()
        // 配额熔断优先级最高：额度没恢复前推什么都是白推。
        if (quotaExhaustedUntil > now) return true
        if (!_isCircuitBreakerOpen.value) return false
        if (now - circuitBreakerOpenTime > syncAdvancedConfigStore.current.circuitBreakerCooldownMs) {
            resetCircuitBreaker()
            return false
        }
        return true
    }

    /**
     * 打开配额退避：撞到 D1「写入额度耗尽」后暂停推送一小段，到期自动重试。
     *
     * ## 退避阶梯
     *
     * 5min → 15min → 30min → 60min（封顶），任意一次成功推送后清零。
     *
     * 为什么不锁到次日 UTC 零点：2026-09-18 实测证明配额恢复不是硬切日界
     * （20:27 报耗尽、同日 23:50 可写）。硬锁 8 小时会让「额度提前恢复」
     * 这段窗口白白浪费；短退避 + 自动探测能在恢复的第一时间续上。
     *
     * 为什么不干脆一直重试：撞墙时的重试是纯浪费（还烧电），必须至少退避，
     * 且阶梯递增 —— 这是 2026-09-17「一点推送就把日额度写穿」事故的教训。
     */
    private fun openQuotaBreaker(reason: String?) {
        if (!syncAdvancedConfigStore.current.quotaBreakerEnabled) return
        quotaHitStreak += 1
        val backoff = quotaBackoffMs(quotaHitStreak)
        val until = System.currentTimeMillis() + backoff
        if (until > quotaExhaustedUntil) quotaExhaustedUntil = until
        val resumeCn = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(until))
        syncAuditLog(
            "quota-backoff-open",
            "push backed off ${backoff / 1000}s (streak=$quotaHitStreak, retry at $resumeCn): ${reason?.take(200)}"
        )
        Log.w(TAG, "Quota backoff ${backoff}ms (streak=$quotaHitStreak, retry at $resumeCn): $reason")
    }

    /**
     * 配额退避阶梯：5min → 15min → 30min → 60min 封顶。
     *
     * 封顶 60 分钟而不是「到次日零点」：留出恢复探测机会。
     * 真到日界重置，最多多试几次，代价只是几次被拒的写请求（不消耗成功额度）。
     */
    private fun quotaBackoffMs(streak: Int): Long = when {
        streak <= 1 -> 5 * 60 * 1000L
        streak == 2 -> 15 * 60 * 1000L
        streak == 3 -> 30 * 60 * 1000L
        else -> 60 * 60 * 1000L
    }

    private fun recordSuccess() {
        consecutiveFailures = 0
        if (_isCircuitBreakerOpen.value) {
            _isCircuitBreakerOpen.value = false
        }
        // 本轮没撞配额墙 → 额度已恢复（或从未耗尽），清零退避阶梯，
        // 下次真撞墙时从 5 分钟重新起步。
        if (quotaHitStreak != 0) {
            Log.i(TAG, "quota backoff reset (previous streak=$quotaHitStreak)")
            quotaHitStreak = 0
        }
    }

    private fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= syncAdvancedConfigStore.current.circuitBreakerFailureThreshold) {
            circuitBreakerOpenTime = System.currentTimeMillis()
            _isCircuitBreakerOpen.value = true
            Log.w(TAG, "Circuit breaker OPEN: paused auto sync after $consecutiveFailures consecutive errors")
        }
    }

    fun isConfigured(): Boolean = settingsStore.settingsFlow.value.d1Config.isConfigured

    /**
     * Manual connectivity test. Deliberately does not require d1Config.enabled: the
     * first-run flow should be “fill credentials → test → enable”, not the reverse.
     * Throws the real D1/HTTP/parse exception so UI can show actionable details.
     */
    suspend fun testConnection() = pushMutex.withLock {
        resetCircuitBreaker()
        val client = requireClient(requireEnabled = false)
            ?: throw IllegalStateException("D1 config incomplete: Account ID, Database ID and API Token are required")
        try {
            D1Schema.ensure(client)
            schemaEnsured = true
            Log.i(TAG, "testConnection: ok")
        } catch (t: Throwable) {
            Log.e(TAG, "testConnection: failed", t)
            throw t
        }
    }

    /** 进程前台：推积压 + 拉差异（仅当自动同步开启时） */
    suspend fun onForeground() = syncCycle()

    /**
     * 探测 Sync Proxy Worker 是否真的可用，返回往返耗时描述。
     *
     * **刻意绕开自动降级**：构造一个 `fallbackToRest = false` 的临时 client，
     * 代理不通就直接抛错。否则测试会因为悄悄走了直连而显示"成功"，
     * 这种测试比没有还糟。
     */
    suspend fun testSyncProxy(): String {
        val cfg = settingsStore.settingsFlow.value.d1Config
        if (!cfg.hasRequiredFields) throw IllegalStateException("D1 config incomplete")
        val proxy = currentProxyConfig()
        if (!proxy.enabled) throw IllegalStateException("Sync proxy is disabled")
        if (proxy.baseUrl.isBlank()) throw IllegalStateException("Sync proxy URL is empty")
        if (proxy.secret.isBlank()) throw IllegalStateException("Sync proxy secret is empty")
        return D1Client(cfg, httpClient, proxy.copy(fallbackToRest = false)).probeProxy()
    }

    /** 进程退后台：尽快推积压，拉取交给 Worker */
    suspend fun onBackground() {
        if (!isConfigured() || checkCircuitBreaker()) return
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
        pushMutex.withLock {
            runCatching { flushOutbox() }
                .onFailure { Log.e(TAG, "onBackground flush failed", it) }
        }
    }

    /** WorkManager 路径 */
    suspend fun syncOnce() = syncCycle()

    /**
     * 只上传本地改动，不拉云端。
     *
     * 推与拉各自一把锁：旧版 syncCycle 把两事焊在同一个 mutex 上，
     * 前台定时 pull 一卡，用户的 push 就在后面排队。
     */
    suspend fun pushOnly(force: Boolean = false) {
        if (!guardEntry(force, "pushOnly")) return
        pushMutex.withLock {
            runCatching { flushOutbox(reportQuarantined = force) }
                .onSuccess { recordSuccess(); markSynced() }
                .onFailure {
                    recordFailure()
                    Log.e(TAG, "pushOnly failed", it)
                    if (force) throw it
                }
        }
    }

    /**
     * 单会话定向拉取：进入聊天页面时调用。
     *
     * 只比对 conv_nodes 的水位，有更新才拉差量，无更新立即返回。
     * 不持 pullMutex、不阻塞 syncCycle / pullAll，最大限度减少进入会话的延迟。
     * 整个流程在 SyncApplyGate 保护下执行，确保远端变更不触发 outbox 回环。
     */
    suspend fun pullConversationFast(conversationId: String) {
        if (!isConfigured() || checkCircuitBreaker()) return
        if (!syncAdvancedConfigStore.current.autoSyncEnabled) return
        // 读侧走语义接口，且**不再要求存在 d1Config**：生效后端是 Supabase 时
        // requireClient() 会返回 null，旧写法会让「打开会话立刻拉」整条路径静默失效。
        //
        // 后端按该会话的**建立时间**选：时间分片之后「当前生效后端」未必是这个会话的家
        // （09-16 之前的会话住在老库），拿错库会直接判定「云端无此会话」而静默跳过。
        val convCreatedAt = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?.let { conversationRepository.getConversationById(it)?.createAt?.toEpochMilli() }
        val backend = convCreatedAt?.let { readBackendForCreateAt(it) }
            ?: requireBackend()
            ?: return
        requireClient()?.let { c -> runCatching { ensureSchema(c) }.onFailure { return } }

        val localNodeState = readLocalNodeState(conversationId)

        // 方案 B 收口：node 通道的排序基准已从 idx 换成跨端恒等的 seq_key
        // （见 ConversationNodeDiff.seqKeyOf / NodePullReconciler），两端重建的
        // 拓扑必然一致，「重复 idx → 序列分歧 → 幽灵分支」的根因已消除。
        //
        // 因此 0911 那一刀「双写模式强制走整包」的止血限制在此解除：
        // 只要本端有 node 基准就走增量通道，进入会话的流量从「整包会话 JSON」
        // 降回「变化的那几条消息」，这才是 Pull-on-Open 本来该有的成本。
        val useNodeChannel = localNodeState != null

        if (useNodeChannel) {
            val stateUpdatedAt = readStateUpdatedAt(stateKeyConv(conversationId)) ?: 0L
            SyncApplyGate.applyingRemote = true
            try {
                pullNodeIncremental(backend, conversationId, stateUpdatedAt, "")
            } finally {
                SyncApplyGate.applyingRemote = false
            }
        } else {
            // 整包探测
            val state = readState(stateKeyConv(conversationId))
            // 一次拿全行。原来是「先探 sha 再拉 data」两跳 —— 直连 REST 下就是两次
            // 公网往返，而「打开会话立刻拉」恰恰是最吃延迟的那条路。
            val remote = backend.pullConversationRows(listOf(conversationId)).firstOrNull() ?: return
            val remoteUpdatedAt = remote.updatedAt
            val remoteSha = remote.sha
            if (state != null && state.sha == remoteSha) return // 无更新
            val data = remote.data ?: return
            if (data.isBlank()) {
                // node-only 对端可能只写了 conv_nodes → 回落 node 通道
                if (localNodeState != null) {
                    val stateUpdatedAt = readStateUpdatedAt(stateKeyConv(conversationId)) ?: 0L
                    SyncApplyGate.applyingRemote = true
                    try {
                        pullNodeIncremental(backend, conversationId, stateUpdatedAt, "")
                    } finally {
                        SyncApplyGate.applyingRemote = false
                    }
                }
                return
            }
            SyncApplyGate.applyingRemote = true
            try {
                applyRemoteConversation(conversationId, data, remoteUpdatedAt, remoteSha)
            } finally {
                SyncApplyGate.applyingRemote = false
            }
        }
        // 回推需求（node reconcile 可能产生 needRepush）
        if (pendingRepushConversations.isNotEmpty()) {
            val outbox = database.syncOutboxDao()
            pendingRepushConversations.toList().forEach { convId ->
                outbox.deleteByRef(SyncOutboxEntity.KIND_CONVERSATION, convId)
                outbox.insert(
                    SyncOutboxEntity(
                        kind = SyncOutboxEntity.KIND_CONVERSATION,
                        refKey = convId,
                        op = SyncOutboxEntity.OP_UPSERT,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            pendingRepushConversations.clear()
        }
    }

    /** 只拉云端变更，不推本地。 */
    suspend fun pullOnly(force: Boolean = false) {
        if (!guardEntry(force, "pullOnly")) return
        pullMutex.withLock {
            runCatching { pullAll() }
                .onSuccess { recordSuccess(); markSynced() }
                .onFailure {
                    recordFailure()
                    Log.e(TAG, "pullOnly failed", it)
                    if (force) throw it
                }
        }
    }

    /**
     * 手动触发同步时，把隔离区与退避一并复活。
     *
     * 「用户主动点同步」的语义就是 “我知道之前失败了，再来一次”。缺这条路径时，
     * 隔离项唯一的生路是用户再改一次那条会话（触发 deleteByRef 重新入队），
     * 这正是 2026-08-08「连上网也有两条永远同步不了」的直接原因。
     */
    private suspend fun reviveOutboxForManualSync() {
        runCatching { database.syncOutboxDao().reviveAll() }
            .onFailure { Log.e(TAG, "reviveOutboxForManualSync failed", it) }
    }

    private suspend fun guardEntry(force: Boolean, tag: String): Boolean {
        if (!isConfigured()) {
            if (force) throw IllegalStateException("Cloud sync is disabled or D1 config is incomplete")
            return false
        }
        if (force) {
            resetCircuitBreaker()
            reviveOutboxForManualSync()
        }
        if (checkCircuitBreaker()) {
            Log.w(TAG, "$tag skipped: circuit breaker is OPEN (quota=${isQuotaBreakerOpen})")
            if (force) {
                throw IllegalStateException(
                    if (isQuotaBreakerOpen)
                        "D1 写入配额已耗尽，已进入退避重试（约 " +
                            "${((quotaExhaustedUntil - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)} " +
                            "分钟后自动重试）"
                    else "Cloud sync is paused after repeated errors; retry later or test the connection"
                )
            }
            return false
        }
        return true
    }

    suspend fun syncCycle(force: Boolean = false) {
        if (!guardEntry(force, "syncCycle")) return
        SyncPerfLog.round(if (force) "manual" else "auto") {
        var failure: Throwable? = null
        pushMutex.withLock {
            runCatching { SyncPerfLog.phase("push:flushOutbox") { flushOutbox(reportQuarantined = force) } }
                .onFailure {
                    failure = it
                    Log.e(TAG, "syncCycle push failed; pull will still run", it)
                }
        }
        pullMutex.withLock {
            runCatching { SyncPerfLog.phase("pull:all") { pullAll() } }
                .onFailure {
                    if (failure == null) failure = it
                    Log.e(TAG, "syncCycle pull failed", it)
                }
        }
        if (failure == null) {
            recordSuccess()
            markSynced()
        } else {
            recordFailure()
            if (force) throw failure!!
        }
        }
    }

    private fun markSynced() {
        _lastSyncedAt.value = System.currentTimeMillis()
    }

    // ---------------- Push ----------------

    /**
     * 推送待发队列。
     *
     * 失败处理按 [SyncFailureClassifier] 分三类，这是 2026-08-08 故障的修复核心：
     * - CANCELLED（切后台/杀进程）→ 不记账、不计数，原样 rethrow 交还协程框架
     * - TRANSIENT（没网/DNS/超时/5xx）→ 只写退避时间，**不动 retry_count**，网络恢复自愈
     * - PERMANENT（D1 4xx / 语句被拒）→ 累加 retry_count，达上限进隔离区
     *
     * 另一处关键修正：单条失败不再 `break` 掉整轮。以前一条卡住会顺带堵死
     * 后面所有排队项（你会看到「前几个同步了，剩下的永远不动」）。
     */
    private suspend fun flushOutbox(reportQuarantined: Boolean = false) {
        // 多后端过渡期（Step I-5）：**会话写路径已经切到语义接口**，
        // node-only 模式下任意后端都能上行（Supabase 直连也算数）。
        // 多后端（Step I-5）：目标后端**按 item 解析**，见 [processOutboxItem]。
        // 仍是 D1 SQL 三步 CAS 的只剩一类：**整包会话路径**（nodeOnlyPush = false）——
        // 它把「CAS 未命中就拉回远端做前缀快进合并」做在客户端，语义接口表达不了
        // （后端 UPSERT 是朴素 LWW，直接换会降级成「新的赢、旧的整行被盖」）。
        // 方案里它本来就要退役（node-only 才是目标形态）。
        // 生效后端不是 D1 时这类 item 会返回 false，先攒在 outbox 里 —— 宁可攒着，
        // 也不能写进与读路径不同的库造成静默分裂。
        val client = requireClient()
        if (client != null) ensureSchema(client)
        val outbox = database.syncOutboxDao()
        val failures = mutableListOf<String>()
        val attempted = mutableSetOf<Long>()
        var skippedNotWritable = 0
        // ◆ 写量护栏（2026-09-17）：单轮处理项数上限，防「一轮推爆日额度」。
        val maxPerRound = syncAdvancedConfigStore.current.writeGuardMaxItemsPerRound
        var processed = 0
        round@ while (true) {
            val pending = outbox.pending(now = System.currentTimeMillis(), limit = 50)
                .filter { it.id !in attempted }
            if (pending.isEmpty()) break
            for (item in pending) {
                if (processed >= maxPerRound) {
                    syncAuditLog("flush-round-cap", "processed=$processed cap=$maxPerRound — aborting round")
                    Log.w(TAG, "flushOutbox: write guard hit ($maxPerRound items), stopping this round")
                    break@round
                }
                attempted += item.id
                processed++
                try {
                    // false = 没有可写的后端（后端还没配好）。**不删 outbox**，
                    // 留到下一轮；绝不静默丢弃，也不算失败、不推进退避。
                    if (!processOutboxItem(client, item)) {
                        skippedNotWritable++
                        continue
                    }
                    outbox.deleteByIds(listOf(item.id))
                } catch (e: Throwable) {
                    val verdict = SyncFailureClassifier.classify(e)
                    // 协程取消是正常生命周期事件，不是数据问题：不记账、不判刑，直接上抛。
                    if (verdict == SyncFailureClassifier.Verdict.CANCELLED) throw e
                    val msg = (e.message ?: e.toString()).take(200)
                    // ◆ 配额耗尽：进入退避（5min 起、递增、封顶 60min）并中止本轮。
                    // 继续推只会继续撞墙、白烧电量，还会把退避计数搅乱。
                    if (SyncFailureClassifier.isQuotaExhausted(e)) {
                        outbox.markTransientFailure(
                            id = item.id,
                            error = msg,
                            nextAttemptAt = System.currentTimeMillis() +
                                SyncFailureClassifier.backoffMs(item.transientAttempt),
                        )
                        openQuotaBreaker(msg)
                        throw e
                    }
                    when (verdict) {
                        SyncFailureClassifier.Verdict.TRANSIENT -> {
                            val backoff = SyncFailureClassifier.backoffMs(item.transientAttempt)
                            outbox.markTransientFailure(
                                id = item.id,
                                error = msg,
                                nextAttemptAt = System.currentTimeMillis() + backoff,
                            )
                            Log.w(TAG, "flushOutbox: ${item.kind}/${item.refKey} transient, retry in ${backoff}ms: $msg")
                        }
                        else -> {
                            outbox.markPermanentFailure(
                                id = item.id,
                                error = msg,
                                nextAttemptAt = System.currentTimeMillis() +
                                    SyncFailureClassifier.backoffMs(item.retryCount),
                            )
                            Log.e(TAG, "flushOutbox: ${item.kind}/${item.refKey} permanent failure", e)
                        }
                    }
                    failures += "${item.kind}/${item.refKey}: $msg"
                }
            }
        }
        if (skippedNotWritable > 0) {
            SyncPerfLog.log(
                SyncPerfLog.CHANNEL_PHASE, "backend:flush",
                "deferred=$skippedNotWritable (no writable backend for these items)",
            )
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("${failures.size} sync upload(s) failed: ${failures.joinToString("; ").take(500)}")
        }
        if (reportQuarantined) {
            val quarantined = outbox.failedItems(limit = 5)
            if (quarantined.isNotEmpty()) {
                val detail = quarantined.joinToString("; ") { "${it.kind}/${it.refKey}: ${it.lastError}" }
                throw IllegalStateException("${quarantined.size} sync upload(s) are quarantined after repeated failures: ${detail.take(500)}")
            }
        }
    }

    /**
     * 处理一条 outbox。
     *
     * ## 目标后端在这里解析，而不是在调用方
     *
     * 多后端之后「本轮用哪个后端」不再是全局常量：会话按 `createAt` 路由、
     * bundles 走最新后端、删除要扇出。所以解析下沉到 item 级别。
     *
     * @return `false` = **没有可写的后端**（后端还没配好）。这不是失败，调用方必须
     *   保留这条记录、不计失败、不推进退避 —— 配置好了下一轮自然就推上去了。
     */
    private suspend fun processOutboxItem(client: D1Client?, item: SyncOutboxEntity): Boolean {
        when (item.kind) {
            SyncOutboxEntity.KIND_CONVERSATION -> {
                val uuid = runCatching { Uuid.parse(item.refKey) }.getOrNull()
                val conv = uuid?.let { conversationRepository.getConversationById(it) }
                if (conv == null || item.op == SyncOutboxEntity.OP_DELETE) {
                    // 删除（或会话已不在本地）：拿不到 createAt 就判不了归属。
                    // 直接**扇出到所有可写后端** —— 墓碑走无守卫整行覆盖，对不存在的行
                    // 也只是建一条墓碑（幂等；拉侧最多把已删的再删一次）。
                    // 反面做法「只写当前生效后端」会让老库里的老会话永远删不掉。
                    val targets = writableBackends()
                    if (targets.isEmpty()) return false
                    targets.forEach { tombstoneRemoteConversation(it, item.refKey) }
                } else {
                    // ★ 时间分片路由：按会话**建立时间**归属，纯函数、零迁移、随时可重算
                    val target = backendForCreateAt(conv.createAt.toEpochMilli()) ?: return false
                    pushConversation(target, client, item.refKey)
                }
                notifyPushed(SIGNAL_KIND_CONV, item.refKey)
            }

            SyncOutboxEntity.KIND_BUNDLE -> {
                // settings 是「当前状态」不是历史：永远走最新那个后端
                val target = bundleWriteBackend() ?: return false
                pushBundle(target, item.refKey)
                // 阶段 A 双写（v2 §2.6）：legacy 整包推完后，额外把 settings 拆成
                // 13 个分片行写一份。读侧仍只读 legacy，分片行此刻纯粹是「攒历史数据」。
                // 放在 legacy 之后：legacy 是当前唯一被读的真相，必须先保证它落地成功。
                if (item.refKey == BUNDLE_SETTINGS) {
                    runCatching { pushSettingsShards(target) }
                        .onFailure {
                            // 分片写失败绝不能影响 legacy 同步（读侧还靠它）。
                            // 吞掉异常 + 留审计，符合「双写期可零副作用回滚」的验收标准。
                            Log.w(TAG, "settings shard double-write failed", it)
                            SyncAuditLog.write(
                                context, "shard-push",
                                "double-write failed: ${it.message}"
                            )
                        }
                }
                notifyPushed(SIGNAL_KIND_BUNDLE, item.refKey)
            }
        }
        return true
    }

    /**
     * 推送成功后广播信令（T7）。
     *
     * 信令只是「去拉一下」的提示：丢了最多退化成轮询，重了最多多一次空拉。
     * 包一层 runCatching：回调实现再怎么炸也不能把同步主链路拖下水。
     */
    private fun notifyPushed(kind: String, ref: String) {
        val hook = onPushed ?: return
        runCatching { hook(kind, ref) }
            .onFailure { Log.d(TAG, "notifyPushed($kind/$ref) ignored: ${it.message}") }
    }

    private suspend fun pushConversation(
        backend: StorageBackend,
        client: D1Client?,
        refKey: String,
    ) {
        val uuid = runCatching { Uuid.parse(refKey) }.getOrElse { return }
        val conv = conversationRepository.getConversationById(uuid)
        if (conv == null) {
            tombstoneRemoteConversation(backend, refKey)
            return
        }
        val syncConv = conv.copy(workspaceCwd = null)

        // ◆ 源头断供：整个会话只剩空占位节点时，不上云。
        //
        // 生成被中断会留下 text="" 的 assistant 残骸。这种会话推上去之后，
        // 对端拉到会发现「云端有个我没有的节点」→ 判分叉 → Fork 另存 →
        // 新会话又上云 → 无限增殖（2026-09-11 现场：6 个副本全是空壳）。
        //
        // 拦在推送口是最省事的一刀：空壳既不占 D1 配额，也不会污染任何对端。
        // 等用户真在这个会话里说了话，它自然就有内容、自然就会同步。
        if (syncConv.messageNodes.isNotEmpty() && syncConv.messageNodes.all { isEmptyPlaceholder(it) }) {
            Log.d(TAG, "pushConversation skipped: $refKey contains only empty placeholders")
            return
        }

        val slimConv = ConversationPartsOffloader.offloadIfNeeded(syncConv, r2MediaStore)
        val updatedAt = conv.updateAt.toEpochMilli()
        val myDevice = SyncLocalPrefs.tieBreakKey(context)

        // ---- P3 S2：node 级增量（双写期也维护 conv_nodes，为 S5 铺路）----
        pushConversationNodes(backend, refKey, slimConv.messageNodes, myDevice)

        if (syncAdvancedConfigStore.current.nodeOnlyPush) {
            // S5：上行只走 node 通道；conversations 行仅维护水位与标题，不写 data/sha
            pushConversationMetaOnly(backend, refKey, conv, updatedAt, myDevice)
            return
        }

        // ---- 整包双写（原有路径；乐观写 + 前缀快进合并）----
        //
        // ★ Step I-5 的显式范围边界：这条路**仍是 D1 的 SQL 三步 CAS**。
        // 它把「CAS 未命中 → 拉回远端 → 前缀快进合并」做在客户端，而
        // [StorageBackend.pushConversations] 只是朴素 LWW —— 直接换过去会把
        // 「两端各新增几条 → 快进合并，一条不丢」降级成「新的赢、旧的整行被盖」，
        // 那正是 2026-09-11 数据丢失事故的语义。方案里这条路本来就要退役
        // （node-only 才是目标形态），所以不在本轮迁移范围内。
        // ★ 整包路径只能落在 **D1 形态**的后端上（它跑的是 D1 SQL 三步 CAS）。
        // 目标不是 D1 时留空返回：不许「半写」—— node 行进了新库、整包行留在旧库，
        // 读侧立刻分裂。Supabase 的整包写路径属于正在退役的那条路，不做迁移。
        if (backend !is D1Backend) return
        val d1 = client ?: return
        // 整包上行同样要消毒孤立 UTF-16 代理（见 ai/util/SurrogateSafe.kt）
        val data = json.encodeToString(slimConv).stripLoneSurrogates()
        val sha = sha256Hex(data)
        val base = readStateUpdatedAt(stateKeyConv(refKey)) ?: 0L

        // 乐观写：基线命中则直推。锁已取消，这里不再有任何额外往返。
        val updated = d1.query(
            """
                UPDATE conversations SET title = ?, updated_at = ?, deleted = 0, sha = ?, data = ?, last_device = ?
                WHERE id = ? AND updated_at = ?
            """.trimIndent(),
            listOf(conv.title, updatedAt, sha, data, myDevice, refKey, base)
        )
        if (updated.changes > 0) {
            saveState(stateKeyConv(refKey), updatedAt, sha)
            return
        }

        if (base == 0L) {
            val inserted = d1.query(
                "INSERT OR IGNORE INTO conversations(id, title, updated_at, deleted, sha, data, last_device) VALUES(?,?,?,0,?,?,?)",
                listOf(refKey, conv.title, updatedAt, sha, data, myDevice)
            )
            if (inserted.changes > 0) {
                saveState(stateKeyConv(refKey), updatedAt, sha)
                return
            }
        }

        resolveConversationConflict(backend, refKey, conv, data, sha, updatedAt, myDevice)
    }

    /**
     * P3 S2：把本会话当前的 node 序列 diff 到云端 conv_nodes。
     *
     * 本地状态（sync_state 的 `sync:convnodes:<convId>`，nodeId -> sha）是 diff 基准：
     * - 新增 / 变化 → UPSERT（batch 一次，长会话追加一条消息只有 1~2 条语句）
     * - 本地消失 → tombstone
     * - 无变化 → 不产生语句，也不重复写本地状态
     */
    private suspend fun pushConversationNodes(
        backend: StorageBackend,
        refKey: String,
        nodes: List<MessageNode>,
        myDevice: String,
    ) {
        val now = System.currentTimeMillis()
        val oldState = readLocalNodeState(refKey) ?: emptyMap()
        val result = ConversationNodeDiff.compute(
            convId = refKey,
            nodes = nodes,
            oldState = oldState,
            myDevice = myDevice,
            now = now,
            json = json,
        )
        // ★ 批量删除被安全阀拦下（2026-09-11 数据丢失事故）：这是「本地基准疑似被
        // 残缺数据污染」的强信号，必须显式留痕，否则只表现为「同步好像少了点东西」。
        result.suppressedDeletion?.let { reason ->
            Log.e(TAG, "pushConversationNodes: BULK DELETE SUPPRESSED $reason")
            syncAuditLog("bulk-delete-suppressed", reason)
        }
        // 上行分两步，顺序不能反：先 upsert 变化节点，再打墓碑。
        // 墓碑带 `updated_at = now`，先打的话新行会被自己刚写下的水位挡住
        // （后端墓碑的守卫是 `updated_at < ?`，新行不满足）。
        if (result.rows.isNotEmpty()) {
            backend.pushNodes(result.rows)
        }
        if (result.tombstones.isNotEmpty()) {
            backend.tombstoneNodes(refKey, result.tombstones, now)
        }
        // 两步都成功后才推进基准；失败（异常抛出）则保留旧 state，下次重试全量重 diff
        if (result.newState != oldState) {
            saveLocalNodeState(refKey, result.newState)
        }
    }

    /**
     * P3 S5（node-only）：conversations 行只维护水位 + 标题 + 写入者，
     * data/sha 固定为空，上行不再出现整包。pull 端凭本地 node state 存在
     * 判定该会话走 node 通道读取，不依赖这里的 data。
     */
    private suspend fun pushConversationMetaOnly(
        backend: StorageBackend,
        refKey: String,
        conv: Conversation,
        updatedAt: Long,
        myDevice: String,
    ) {
        val bumped = maxOf(updatedAt, (readStateUpdatedAt(stateKeyConv(refKey)) ?: 0L) + 1)
        // 后端一条语句完成「UPDATE 不中就 INSERT」，且 UPDATE 分支**绝不碰 sha / data**
        // —— node-only 模式下这两列必须保持空串，误写一次读侧就会误判走整包通道。
        backend.bumpConversationMeta(
            listOf(
                ConversationMetaRow(
                    id = refKey,
                    title = conv.title,
                    updatedAt = bumped,
                    lastDevice = myDevice,
                )
            )
        )
        saveState(stateKeyConv(refKey), bumped, "")
    }

    /**
     * 乐观写未命中：拉下远端做前缀快进合并（参见 [ConversationMerger]）。
     *
     * 旧实现是拿两台设备的墙钟比大小做 LWW，输的一方整个会话被覆盖；
     * 现在只有真分叉才产生分支，"另一台设备多发了几条" 直接快进，一条不丢。
     */
    private suspend fun resolveConversationConflict(
        backend: StorageBackend,
        refKey: String,
        local: Conversation,
        data: String,
        sha: String,
        updatedAt: Long,
        myDevice: String,
    ) {
        // 一次拿全行（水位 / sha / data / 写入者）。原来是「manifest + data」两跳，
        // 在裁决路径上会被放大成上百次公网往返，而且两次之间远端还可能变。
        val remote = backend.pullConversationRows(listOf(refKey)).firstOrNull()

        if (remote == null) {
            backend.forceOverwriteConversations(
                listOf(
                    ConversationPushRow(
                        id = refKey,
                        title = local.title,
                        updatedAt = updatedAt,
                        deleted = 0,
                        sha = sha,
                        data = data,
                        lastDevice = myDevice,
                    )
                )
            )
            saveState(stateKeyConv(refKey), updatedAt, sha)
            return
        }

        val remoteUpdatedAt = remote.updatedAt
        val remoteDevice = remote.lastDevice
        val remoteData = remote.data

        // 上一次写入者就是本机：自己覆盖自己不算冲禁，直接快进，省下一次解析。
        if (!remoteDevice.isNullOrBlank() && remoteDevice == myDevice) {
            forcePushConversation(backend, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)
            return
        }

        val remoteConv = remoteData?.let {
            runCatching { json.decodeFromString<Conversation>(it) }.getOrNull()
        }
        if (remoteConv == null) {
            // 远端不可解析（旧格式/损坏）：保守回退到本地优先强推，不丢本机数据
            Log.w(TAG, "resolveConversationConflict: remote data unreadable for $refKey, force pushing local")
            forcePushConversation(backend, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)
            return
        }

        val resolution = ConversationMerger.resolve(
            local = local,
            remote = remoteConv,
            localTieBreak = myDevice,
            remoteTieBreak = remoteDevice,
        )
        Log.i(TAG, "resolveConversationConflict: $refKey -> $resolution")

        when (resolution) {
            is ConversationMerger.Resolution.Identical -> {
                // 内容等价，只对齐基线，不写云端
                saveState(stateKeyConv(refKey), remoteUpdatedAt, remote.sha)
            }

            is ConversationMerger.Resolution.KeepLocal ->
                forcePushConversation(backend, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)

            is ConversationMerger.Resolution.TakeRemote -> {
                SyncApplyGate.applyingRemote = true
                try {
                    applyRemoteConversation(refKey, remoteData, remoteUpdatedAt, remote.sha)
                } finally {
                    SyncApplyGate.applyingRemote = false
                }
            }

            is ConversationMerger.Resolution.AppendMerge -> {
                // 并发追加合并：两端新增节点 ID 无交集，安全拼接不 Fork
                val mergedNodes = ConversationMerger.applyAppendMerge(
                    localNodes = local.messageNodes,
                    remoteNodes = remoteConv.messageNodes,
                    resolution = resolution,
                )
                val mergedConv = local.copy(messageNodes = mergedNodes)
                SyncApplyGate.applyingRemote = true
                try {
                    conversationRepository.updateConversation(mergedConv)
                } finally {
                    SyncApplyGate.applyingRemote = false
                }
                // 合并结果需要回推云端，让对端也拿到完整序列
                val mergedData = json.encodeToString(
                    mergedConv.copy(workspaceCwd = null)
                ).stripLoneSurrogates()
                val mergedSha = sha256Hex(mergedData)
                forcePushConversation(backend, refKey, mergedConv.title, mergedData, mergedSha, updatedAt, remoteUpdatedAt, myDevice)
                syncAuditLog("append-merge",
                    "conv=$refKey prefix=${resolution.commonPrefixLength} " +
                        "local+=${local.messageNodes.size - resolution.commonPrefixLength} " +
                        "remote+=${remoteConv.messageNodes.size - resolution.commonPrefixLength}")
            }

            is ConversationMerger.Resolution.Fork -> {
                // ◆ Fork 熔断：同一源会话短时间内反复分叉 = 自激环路，不是用户真在两端编辑。
                //
                // Fork 会「造出一个新会话 → 新会话上云 → 对端拉到 → 又判分叉」。
                // 只要判据有任何抖动（空节点、时区漂移、元数据竞争），这个环就自我维持，
                // 而 T7 信令把轮询周期从 30s 压到 1s，等于给增殖踩了三十倍油门。
                //
                // ★ 2026-09-11 修正：熔断动作从 TakeRemote 改为「原地停手」。
                //
                // 旧实现的取舍写的是「宁可丢掉一点本地未合并的编辑」—— 这是想当然。
                // node-only 会话的云端整包是空的，TakeRemote 丢的根本不是「一点编辑」，
                // 而是**整段历史**（现场：一次吃掉 51 条 + 112 条）。
                //
                // 熔断器的职责是「止住增殖」，不是「裁决谁对」。它触发时恰恰说明判据
                // 本身不可信 —— 一个不可信的判据没有资格执行「用一方覆盖另一方」这种
                // 不可逆操作。正确动作是：不分叉、不覆盖、不推进基准，保持现状等人工。
                //
                // 代价是这个会话在冷却期内暂停同步（10 分钟），比丢历史便宜得多。
                if (!ForkCircuitBreaker.allow(refKey)) {
                    Log.e(
                        TAG,
                        "fork circuit breaker OPEN for $refKey; freezing sync for this " +
                            "conversation (no overwrite, no fork) until window expires"
                    )
                    SyncAuditLog.write(
                        context, "fork-breaker",
                        "conv=$refKey forked too many times; sync frozen (local preserved)"
                    )
                    return
                }
                if (resolution.localKeepsId) {
                    // 用户拍板：本机保留原 id，云端版本另存为 xxx-<对端 label>
                    //
                    // ★ 空壳分支拒绝另存（2026-09-11 自激增殖根因）。
                    // 有内容的那端留下当主会话，空壳直接丢，绝不允许它跑出来
                    // 占一个会话位、再推上云、再触发对端又一轮分叉。
                    val remoteHasContent = remoteConv.messageNodes.any { !isEmptyPlaceholder(it) }
                    if (!remoteHasContent) {
                        Log.i(TAG, "fork suppressed: remote side is empty placeholder (conv=$refKey)")
                    } else {
                        forkRemoteCopy(remoteConv, remoteDevice)
                    }
                    forcePushConversation(backend, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)
                } else {
                    // 对端裁决胜出（它也会把我的版本另存）：本机自己另存后快进远端
                    //
                    // 同样：本地若只剩空壳，不另存，直接让给远端。
                    val localHasContent = local.messageNodes.any { !isEmptyPlaceholder(it) }
                    if (!localHasContent) {
                        Log.i(TAG, "fork suppressed: local side is empty placeholder (conv=$refKey); will take remote")
                    } else {
                        forkLocalCopy(local)
                    }
                    SyncApplyGate.applyingRemote = true
                    try {
                        applyRemoteConversation(refKey, remoteData, remoteUpdatedAt, remote.sha)
                    } finally {
                        SyncApplyGate.applyingRemote = false
                    }
                }
            }
        }
    }

    /**
     * 空壳占位节点：所有消息都没有任何实质内容。
     *
     * 典型来源是流式生成刚建好占位就被中断 / 报错，留下一个 text="" 的 assistant。
     * 判定故意保守：只要带了工具调用、图片、文件等任何非文本 part，就**不算**空壳，
     * 宁可漏判也不能误删用户真实数据。
     *
     * [ConversationMerger.isEmptyPlaceholder] 也有一份，逻辑保持一致。
     * 两份而不是共享：避免在 core 层搞循环依赖；函数极小，复制成本可接受。
     */
    private fun isEmptyPlaceholder(node: MessageNode): Boolean {
        if (node.messages.isEmpty()) return true
        return node.messages.all { msg ->
            // offload 引用（大 part 在 R2）不算空：内容在，只是不在这儿。
            // 与 ConversationMerger.isEmptyPlaceholder 保持一致。
            val t = msg.parts.singleOrNull() as? UIMessagePart.Text
            val isRef = t != null && (
                !t.metadata?.get("r2_parts_ref")?.jsonPrimitive?.contentOrNull.isNullOrBlank() ||
                    t.text.startsWith("r2_parts:")
                )
            if (isRef) return@all false
            msg.parts.all { part ->
                part is UIMessagePart.Text && part.text.isBlank()
            }
        }
    }

    /** 放弃基线强推；updated_at 严格递增，避免写入比云端还小的值导致下次又被判输 */
    private suspend fun forcePushConversation(
        backend: StorageBackend,
        refKey: String,
        title: String,
        data: String,
        sha: String,
        updatedAt: Long,
        remoteUpdatedAt: Long,
        myDevice: String,
    ) {
        val bumped = maxOf(updatedAt, remoteUpdatedAt + 1)
        backend.forceOverwriteConversations(
            listOf(
                ConversationPushRow(
                    id = refKey,
                    title = title,
                    updatedAt = bumped,
                    deleted = 0,
                    sha = sha,
                    data = data,
                    lastDevice = myDevice,
                )
            )
        )
        saveState(stateKeyConv(refKey), bumped, sha)
    }

    /** 真分叉时把远端版本另存为本地新会话（本机保留原 id） */
    private suspend fun forkRemoteCopy(remote: Conversation, remoteDevice: String?) {
        val label = remoteDevice?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: "remote"
        runCatching {
            val hydrated = ConversationPartsOffloader.hydrateIfNeeded(remote, r2MediaStore)
            val title = ConversationMerger.forkTitle(hydrated.title, label)
            conversationRepository.insertConversation(
                hydrated.copy(
                    id = Uuid.random(),
                    title = title,
                    workspaceCwd = null,
                )
            )
            Log.w(TAG, "forkRemoteCopy: remote version of ${remote.id} saved as a local branch ($label)")
            onConversationForked?.invoke(remote.id.toString(), title)
        }.onFailure { Log.e(TAG, "forkRemoteCopy failed for ${remote.id}", it) }
    }

    /** 裁决输给对端时把本地版本另存，再释放原 id 给云端 */
    private suspend fun forkLocalCopy(local: Conversation) {
        val label = SyncLocalPrefs.deviceLabel(context)
        runCatching {
            val title = ConversationMerger.forkTitle(local.title, label)
            conversationRepository.insertConversation(
                local.copy(
                    id = Uuid.random(),
                    title = title,
                )
            )
            Log.w(TAG, "forkLocalCopy: local version of ${local.id} saved as a branch ($label)")
            onConversationForked?.invoke(local.id.toString(), title)
        }.onFailure { Log.e(TAG, "forkLocalCopy failed for ${local.id}", it) }
    }

    private suspend fun tombstoneRemoteConversation(backend: StorageBackend, refKey: String) {
        val now = System.currentTimeMillis()
        // Keep sha/data in sync with the tombstone. If sha remains the old conversation sha,
        // other devices can skip the row before seeing deleted=1 and never delete locally.
        //
        // 刻意用**无守卫**的 forceOverwrite：删除是本地刚发生的动作，必须落地。
        // 换成有 LWW 守卫的 pushConversations，就会出现「对端时钟快几秒 → 本机删了
        // 但云端不收」这种最恼人的情形：本地没了、对端还在。原来的 UPDATE 同样无守卫。
        backend.forceOverwriteConversations(
            listOf(
                ConversationPushRow(
                    id = refKey,
                    title = "",
                    updatedAt = now,
                    deleted = 1,
                    sha = "tombstone",
                    data = "",
                )
            )
        )
        // P3：顺带 tombstone 该会话的全部 node 行，并清本地 node 基准。
        // nodeIds 传 null = 整会话，不必为了拿 id 先拉一遍清单。
        runCatching { backend.tombstoneNodes(refKey, null, now) }
            .onFailure { Log.w(TAG, "tombstone conv_nodes failed for $refKey", it) }
        clearLocalNodeState(refKey)
        saveState(stateKeyConv(refKey), now, "tombstone")
    }

    private suspend fun pushBundle(backend: StorageBackend, key: String) {
        val payload = when (key) {
            BUNDLE_SETTINGS -> json.encodeToString(
                SyncSettingsFilter.forUpload(settingsStore.settingsFlow.value)
            )

            BUNDLE_SETTINGS_DISPLAY -> {
                if (!SyncLocalPrefs.isDisplaySyncEnabled(context)) return
                json.encodeToString(SyncSettingsFilter.displayForUpload(settingsStore.settingsFlow.value.displaySetting))
            }

            BUNDLE_MEMORY -> exportMemory()

            BUNDLE_MEMORY_LINKS -> exportMemoryLinks()

            BUNDLE_MEMORY_GRAPH_NODES -> exportMemoryGraphNodes()

            BUNDLE_MEMORY_GRAPH_LINKS -> exportMemoryGraphLinks()

            BUNDLE_MEMORY_GRAPHS -> exportMemoryGraphs()

            BUNDLE_FAVORITES -> exportFavorites()

            BUNDLE_FOLDERS -> exportFolders()

            BUNDLE_GENMEDIA -> exportGenMedia()

            BUNDLE_MANAGED_FILES -> exportManagedFiles()
            BUNDLE_ASSET_LABELS -> exportAssetLabels()

            BUNDLE_SUBAGENT_TEMPLATES -> exportSubagentTemplates()

            BUNDLE_SKILLS -> exportSkills()

            BUNDLE_SCHEDULED_NOTIFICATIONS -> json.encodeToString(ScheduledNotificationManager.getAllItems(context))

            // 2026-09-19：屏幕时间已改走独立 Worker（ScreenTimeSyncClient），
            // 不再是 D1 bundle。未知 key 一律放行。
            else -> return
        }
        val sha = sha256Hex(payload)
        val now = System.currentTimeMillis()
        val state = readState(stateKeyBundle(key))
        if (state?.sha == sha) return
        val base = state?.updatedAt ?: 0L

        // ① 直接推（后端带 LWW 守卫：sha 未变跳过 + 新旧比较）。
        // 原来的「CAS on base → 首插 → 读回」三段，前两段合并成这一推 ——
        // 语义等价（远端更旧则必中），往返从 1~3 次降到恒定 1 次。
        if (backend.pushBundles(listOf(bundleRow(key, payload, sha, now, hlc = 0L))) > 0) {
            saveState(stateKeyBundle(key), now, sha)
            return
        }

        // ② 没落地 → 读回远端裁决（后端全行读，一次往返拿齐水位 / sha / data）
        val remote = backend.pullBundleRows(listOf(key)).firstOrNull()
        if (remote == null) {
            // 行不存在却没推上去：极端竞争（对端刚删）。保守强推，保本机数据。
            backend.forceOverwriteBundles(listOf(bundleRow(key, payload, sha, now, hlc = 0L)))
            saveState(stateKeyBundle(key), now, sha)
            return
        }
        val remoteUp = remote.updatedAt
        // 不能拿两台设备的墙钟比大小：对端时钟快几秒就会把本机刚改的整包设置判输。
        // 只看云端是否已经走在本机基线之前：真有新版本才采纳云端。
        if (remoteUp > base) {
            // 采纳云端时必须走 ApplyGate，否则本地写钩会把刚应用的变更再次入队造成推送回环
            SyncApplyGate.applyingRemote = true
            try {
                applyRemoteBundle(key, remote.data ?: return, remoteUp, remote.sha)
            } finally {
                SyncApplyGate.applyingRemote = false
            }
            // 云端赢了不等于本地改动该死：mergeRemote 已做逐项 LWW，
            // 合并结果可能与云端不同，重新入队把合并后的真相推上去。
            if (key == BUNDLE_SETTINGS || key == BUNDLE_SETTINGS_DISPLAY) {
                SyncBundleEnqueuer.enqueue(key)
            }
        } else {
            // 云端没比基线新，却没被守卫放行（常见于对端时钟回拨或初始化竞争）：
            // 用严格递增的版本号强推，避免写入一个比云端还小的 updated_at 导致下次又被判输。
            val bumped = maxOf(now, remoteUp + 1)
            backend.forceOverwriteBundles(listOf(bundleRow(key, payload, sha, bumped, hlc = 0L)))
            saveState(stateKeyBundle(key), bumped, sha)
        }
    }

    /** bundles 行的统一构造。`hlc` / `kind` 只有 settings 分片才非默认。 */
    private fun bundleRow(
        key: String,
        payload: String,
        sha: String,
        updatedAt: Long,
        hlc: Long,
        kind: String = "legacy",
    ) = BundlePushRow(
        k = key,
        updatedAt = updatedAt,
        deleted = 0,
        sha = sha,
        data = payload,
        hlc = hlc,
        kind = kind,
    )

    /**
     * settings 分片双写（v2 §2.6，阶段 A 第 6 项）。
     *
     * **只写不读**：读侧仍走 `BUNDLE_SETTINGS` 整包。分片行此刻的唯一作用是
     * 让云端积累「字段级 hlc」这种历史数据 —— 没有历史数据就切读侧的话，
     * 首轮 pull 全字段 unknown，谁也不赢谁，白折腾。
     *
     * 失败不影响 legacy（调用点已 runCatching），符合「零副作用可回滚」。
     */
    private suspend fun pushSettingsShards(backend: StorageBackend) {
        val outcome = settingsShardPusher.push(
            settings = settingsStore.settingsFlow.value,
            shaOfPushed = { shardKey -> readState(stateKeyBundle(shardKey))?.sha },
            write = { shardKey, payload, hlc ->
                writeShardRow(backend, shardKey, payload, hlc)
            },
        )
        if (outcome.isBlocked) {
            SyncAuditLog.write(context, "shard-push", "blocked: ${outcome.blockedReason}")
        } else if (outcome.pushed.isNotEmpty()) {
            SyncAuditLog.write(
                context, "shard-push",
                "pushed=${outcome.pushed.joinToString()} skipped=${outcome.skipped.size}"
            )
        }
    }

    /**
     * 写一行分片 envelope，沿用 legacy 的乐观锁三段式（§2.6 明确要求「原样保留」）。
     *
     * 三段：CAS 更新 → 首次 INSERT OR IGNORE → 读回按水位裁决。
     * 与 [pushBundle] 的差别只有两处：
     * 1. 多写 `hlc` / `kind` 两列
     * 2. **冲突时不做本地合并**：双写期读侧不读分片，云端那份分片对本机毫无影响，
     *    因此「云端更新」时直接跳过本轮即可，不需要 applyRemoteBundle。
     *    等阶段 B 切读侧时才需要在这里接 SyncCrdt 逐字段合并。
     *
     * @return 是否写成功（写成功才更新本地 sha 账簿）
     */
    private suspend fun writeShardRow(
        backend: StorageBackend,
        shardKey: String,
        payload: String,
        hlc: Long,
    ): Boolean {
        val sha = SyncFieldDigest.shaOf(
            SyncFieldDigest.json().parseToJsonElement(payload)
        )
        val now = System.currentTimeMillis()
        val state = readState(stateKeyBundle(shardKey))
        val base = state?.updatedAt ?: 0L

        // ① 直接推（后端 LWW：sha 未变跳过 + hlc 优先、updated_at 兜底）
        if (backend.pushBundles(listOf(bundleRow(shardKey, payload, sha, now, hlc, kind = "shard"))) > 0) {
            saveState(stateKeyBundle(shardKey), now, sha)
            return true
        }

        // ② 读回看云端水位
        val remote = backend.pullBundleRows(listOf(shardKey)).firstOrNull() ?: return false
        val remoteUp = remote.updatedAt
        val remoteHlc = remote.hlc

        // 让本机时钟知道云端已经走到哪了。即使读侧还没切分片也必须做：
        // 否则阶段 B 切读侧那天，本机新戳可能小于云端已有的戳，破坏 happens-before。
        settingsShardPusher.observeRemote(remoteHlc)

        if (remoteUp > base) {
            // 云端有更新的分片。双写期读侧不读分片 → 对本机无影响 → 本轮跳过。
            // 不在这里合并是刻意的：合并逻辑属于阶段 B 切读侧时的工作，
            // 现在写一半的合并代码只会在没有测试覆盖的路径上埋雷。
            saveState(stateKeyBundle(shardKey), remoteUp, remote.sha)
            return false
        }

        // 云端不比基线新却没命中 CAS（对端时钟回拨 / 初始化竞争）：
        // 用严格递增的 updated_at 强推，避免写入比云端更小的水位导致下轮又判输。
        // 注意只 bump 传输水位 updated_at，**不动 hlc** —— hlc 是因果戳，
        // 凭空调大它等于伪造「本机改得更晚」，会让本机默认值压掉对端真实配置。
        val bumped = maxOf(now, remoteUp + 1)
        backend.forceOverwriteBundles(
            listOf(bundleRow(shardKey, payload, sha, bumped, hlc, kind = "shard"))
        )
        saveState(stateKeyBundle(shardKey), bumped, sha)
        return true
    }

    private suspend fun exportMemory(): String {
        val items = database.memoryDao().getAllMemories()
            .map { SyncMemoryItem(id = it.id, assistantId = it.assistantId, content = it.content) }
        return json.encodeToString(items)
    }

    private suspend fun exportMemoryLinks(): String {
        val items = database.memoryLinkDao().getAll().map {
            SyncMemoryLinkItem(
                id = it.id,
                sourceId = it.sourceId,
                targetId = it.targetId,
                type = it.type,
                weight = it.weight,
                description = it.description,
                scope = it.scope,
                createdAt = it.createdAt,
                validFrom = it.validFrom,
                validUntil = it.validUntil,
                supersededById = it.supersededById,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportMemoryGraphNodes(): String {
        return exportMemoryGraphNodesInternal()
    }

    /** 图注册表整表快照（与 nodes/links 同款写法） */
    private suspend fun exportMemoryGraphs(): String {
        val items = database.memoryGraphDao().getAll().map {
            SyncMemoryGraphItem(
                id = it.id,
                slug = it.slug,
                name = it.name,
                description = it.description,
                kind = it.kind,
                boundAssistantId = it.boundAssistantId,
                emoji = it.emoji,
                builtin = it.builtin,
                createdBy = it.createdBy,
                sortOrder = it.sortOrder,
                autoExtractTarget = it.autoExtractTarget,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportMemoryGraphNodesInternal(): String {
        val items = database.memoryGraphNodeDao().getAll().map {
            SyncMemoryGraphNodeItem(
                id = it.id,
                scope = it.scope,
                title = it.title,
                content = it.content,
                importance = it.importance,
                matchEligibility = it.matchEligibility,
                folderPath = it.folderPath,
                sourceConversationId = it.sourceConversationId,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportMemoryGraphLinks(): String {
        val items = database.memoryGraphLinkDao().getAll().map {
            SyncMemoryGraphLinkItem(
                id = it.id,
                scope = it.scope,
                sourceId = it.sourceId,
                targetId = it.targetId,
                type = it.type,
                weight = it.weight,
                description = it.description,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportFavorites(): String {
        val items = database.favoriteDao().getAllList().map {
            SyncFavoriteItem(
                id = it.id,
                type = it.type,
                refKey = it.refKey,
                refJson = it.refJson,
                snapshotJson = it.snapshotJson,
                metaJson = it.metaJson,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportFolders(): String {
        val items = database.folderDao().getAllList().map {
            SyncFolderItem(
                id = it.id,
                assistantId = it.assistantId,
                name = it.name,
                sortIndex = it.sortIndex,
                createAt = it.createAt,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportGenMedia(): String {
        val items = database.genMediaDao().getAllMedia().map {
            SyncGenMediaItem(
                path = it.path,
                modelId = it.modelId,
                prompt = it.prompt,
                createAt = it.createAt,
                type = it.type,
                sourcePaths = it.sourcePaths,
                r2Key = it.r2Key,
                r2Acct = it.r2Acct,
                originalUrl = it.originalUrl,
                originalAssetId = it.originalAssetId,
                previewAssetId = it.previewAssetId,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportManagedFiles(): String {
        val items = database.managedFileDao().getAllFiles().map {
            SyncManagedFileItem(
                id = it.id,
                folder = it.folder,
                relativePath = it.relativePath,
                displayName = it.displayName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                r2Key = it.r2Key,
                r2Acct = it.r2Acct,
                externalUrl = it.externalUrl,
                sha256 = it.sha256,
                contentSha256 = it.contentSha256,
                nameZh = it.nameZh,
                nameEn = it.nameEn,
                prompt = it.prompt,
                description = it.description,
                ocrText = it.ocrText,
                deleted = it.deleted,
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportAssetLabels(): String {
        val items = database.assetLabelDao().getAll().map {
            SyncAssetLabelItem(
                assetId = it.assetId,
                kind = it.kind,
                value = it.value,
                createdAt = it.createdAt,
            )
        }
        return json.encodeToString(items)
    }

    private fun exportSubagentTemplates(): String {
        val dir = File(AppPaths.filesDir(context), "subagents")
        if (!dir.exists()) return "[]"
        val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return "[]"
        val items = files.map { file ->
            SyncSubagentTemplateItem(
                filename = file.name,
                content = file.readText(),
            )
        }
        return json.encodeToString(items)
    }

    private fun importSubagentTemplates(data: String) {
        val items = runCatching { json.decodeFromString<List<SyncSubagentTemplateItem>>(data) }
            .getOrElse { return }
        val dir = File(AppPaths.filesDir(context), "subagents")
        if (!dir.exists()) dir.mkdirs()
        items.forEach { item ->
            val target = File(dir, item.filename)
            if (!target.exists()) {
                runCatching { target.writeText(item.content) }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun exportSkills(): String {
        val dir = File(AppPaths.filesDir(context), FileFolders.SKILLS)
        if (!dir.exists()) return "[]"
        val root = dir.canonicalFile
        val items = mutableListOf<SyncSkillFileItem>()
        root.walkTopDown()
            .filter { it.isFile }
            .filterNot { file -> file.relativeTo(root).path.split(File.separatorChar).any { part -> part.startsWith(".") } }
            .forEach { file ->
                val relativePath = runCatching { file.relativeTo(root).invariantSeparatorsPath }.getOrNull()
                    ?: return@forEach
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                val sha = sha256Hex(bytes)
                val r2Ref = r2MediaStore.uploadWithKey(
                    key = "skills/$sha/$relativePath",
                    bytes = bytes,
                    mimeType = "application/octet-stream",
                ).getOrNull()?.toString()
                items += SyncSkillFileItem(
                    relativePath = relativePath,
                    updatedAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha,
                    r2Ref = r2Ref,
                    bytesBase64 = if (r2Ref == null) Base64.encode(bytes) else null,
                )
            }
        return json.encodeToString(items)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun importSkills(data: String) {
        val items = runCatching { json.decodeFromString<List<SyncSkillFileItem>>(data) }
            .getOrElse { return }
        if (items.isEmpty()) return
        val root = File(AppPaths.filesDir(context), FileFolders.SKILLS).canonicalFile
        root.mkdirs()
        items.forEach { item ->
            val target = safeSkillTarget(root, item.relativePath) ?: return@forEach
            val bytes = item.r2Ref
                ?.let { R2Ref.parse(it) }
                ?.let { ref -> r2MediaStore.downloadBytes(ref).getOrNull() }
                ?: item.bytesBase64?.let { Base64.decode(it) }
                ?: return@forEach
            runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                target.setLastModified(item.updatedAt)
            }.onFailure {
                Log.e(TAG, "importSkills: failed to write ${item.relativePath}", it)
            }
        }
    }

    private fun safeSkillTarget(root: File, relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.startsWith("/") || relativePath.startsWith("\\")) return null
        val parts = relativePath.split('/', '\\')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        val target = File(root, parts.joinToString(File.separator)).canonicalFile
        return target.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    // ---------------- Pull ----------------

    private suspend fun pullAll() {
        // 多后端（Step I-5）：把所有「配齐」的后端都拉一遍。
        //
        // 不能只拉路由命中的那一个 —— 清单查询是按 updated_at 的，而我们并不知道
        // 每一行的 createAt；只有全拉回来，本地判据（sha / 节点基准）才有机会决定
        // 采纳谁。每个后端各带一份水位，互不干扰。
        val backends = readableBackends()
        if (backends.isEmpty()) return
        requireClient()?.let { ensureSchema(it) }
        pendingRepush.clear()
        SyncApplyGate.applyingRemote = true
        try {
            backends.forEach { backend ->
                SyncPerfLog.phase("pull:conversations:${backend.backendId}") {
                    pullConversations(backend)
                }
            }
            // bundles 必须**按「老的先、新的后」逐个后端跑完整序列**（readableBackends
            // 已按 rangeStart 升序排好）。settings 是「当前状态」不是历史，读侧要看
            // 最新那一份；某个后端没有这一行时 pullBundleKey 直接返回，不会误清本地。
            backends.forEach { backend -> pullBundlesFrom(backend) }
        } finally {
            SyncApplyGate.applyingRemote = false
        }
        // ApplyGate 释放后再入队：门开着时 enqueue 会被直接丢弃
        if (pendingRepush.isNotEmpty()) {
            pendingRepush.toList().forEach { SyncBundleEnqueuer.enqueue(it) }
            pendingRepush.clear()
        }
        if (pendingRepushConversations.isNotEmpty()) {
            val outbox = database.syncOutboxDao()
            pendingRepushConversations.toList().forEach { convId ->
                outbox.deleteByRef(SyncOutboxEntity.KIND_CONVERSATION, convId)
                outbox.insert(
                    SyncOutboxEntity(
                        kind = SyncOutboxEntity.KIND_CONVERSATION,
                        refKey = convId,
                        op = SyncOutboxEntity.OP_UPSERT,
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }
            pendingRepushConversations.clear()
        }
    }

    /** 从**单个**后端拉全套 bundles 并应用。 */
    private suspend fun pullBundlesFrom(backend: StorageBackend) {
        /*
         * 先用两条 SQL 把所有 bundle 抓齐，再逐个应用。
         *
         * 原来这里是 15 次 `pullBundleKey`，每次一条 SELECT —— 直连 REST 时
         * 就是 15 次公网往返（~15s），而它们之间**没有任何数据依赖**，纯粹
         * 是写法造成的串行。
         *
         * 改成 prefetch 后：一条 manifest 查询（k/updated_at/sha，几百字节）
         * + 一条只针对「sha 变了的那几个 key」的 data 查询。既省往返，也省流量
         * （以前每轮都把 15 个 bundle 的 data 全量拖下来，哪怕一个字节没变）。
         *
         * **下面的调用顺序不能动**：bundle 之间存在应用顺序约束（见各行注释）。
         * prefetch 只是提前取数，不改变应用次序。
         */
        val bundlePrefetch = SyncPerfLog.phase("pull:bundlePrefetch") {
            prefetchBundles(backend, PULL_BUNDLE_KEYS)
        }

        pullBundleKey(backend, BUNDLE_SETTINGS, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_SETTINGS_DISPLAY, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_MEMORY, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_MEMORY_LINKS, bundlePrefetch)
        // 注册表必须先于节点 / 边落地，避免远端多图短暂进入孤儿态。
        pullBundleKey(backend, BUNDLE_MEMORY_GRAPHS, bundlePrefetch)
        // 先应用边但暂不清理，再应用节点并在节点完成后校验边，避免边 bundle 先到时丢失。
        pullBundleKey(backend, BUNDLE_MEMORY_GRAPH_LINKS, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_MEMORY_GRAPH_NODES, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_FAVORITES, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_FOLDERS, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_GENMEDIA, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_MANAGED_FILES, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_ASSET_LABELS, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_SUBAGENT_TEMPLATES, bundlePrefetch)
        pullBundleKey(backend, BUNDLE_SKILLS, bundlePrefetch)
        // 2026-09-19: 定时通知不再走 D1 拉取（已改 Worker + R2 快通道）

        // 阶段 A 双写期（v2 §2.6）：观测分片行的 hlc，推进本机时钟。
        //
        // **只观测，不合并**（读侧仍走 BUNDLE_SETTINGS 整包）。
        // 为什么即使不读也要观测：本机时钟必须知道「云端已经走到哪了」，
        // 否则等阶段 C 真的切读侧时，本机新产生的戳可能小于云端已有的戳，
        // 「本机刚改了一个设置但因为 hlc 更小被判输」这种灾难就出现了。
        //
        // ⚠️ 只查 hlc 列（一条 SQL，不拉 data），不增加流量。
        runCatching { observeShardClocks(backend) }
            .onFailure { Log.w(TAG, "observeShardClocks failed (non-fatal)", it) }
    }

    private suspend fun pullConversations(backend: StorageBackend) {
        // 增量 manifest：只拉比本机水位新的行。以前是 SELECT 全表，
        // 会话一多每次同步都在白传几百行 manifest。
        // 水位按后端分键：每个库各有自己的 updated_at 涨势，共用一根就会跨库漏拉。
        val watermarkKey = convWatermarkKey(backend.backendId)
        val reconcileKey = convReconcileKey(backend.backendId)
        val watermark = readStateUpdatedAt(watermarkKey) ?: 0L

        // ◆ 周期全量对账：修复「水位跨过迟到写入导致永久漏拉」（见
        // [CONV_RECONCILE_INTERVAL_MS]）。对账轮不看水位、扫全表清单，
        // 把本地缺失/不一致的会话捞回来。
        val reconcileNow = System.currentTimeMillis()
        val lastReconcile = readStateUpdatedAt(reconcileKey) ?: 0L
        val fullReconcile = reconcileNow - lastReconcile > CONV_RECONCILE_INTERVAL_MS
        // ⚠️ 先落时间戳，再干活。旧写法只在函数末尾存，一旦中途异常/被取消就
        // 永远存不进去 → 下一轮又判定"该对账" → 每轮全量 apply → 风暴。
        if (fullReconcile) {
            saveState(reconcileKey, reconcileNow, "")
        }

        // 走语义接口拿清单（`since = null` 即全量对账轮）
        val rows = backend.pullConversationManifest(if (fullReconcile) null else watermark)
        if (rows.isEmpty()) return

        var maxUpdatedAt = watermark
        val needData = mutableListOf<Triple<String, Long, String>>()
        // N+1 计数：pullNodeIncremental 每次至少一轮往返，且在下面这个 for 里串行调用。
        // 「代理很快但整体还是慢」多半就是这个数字太大 —— 89 个会话 × 0.9s ≈ 80s。
        var nodeIncrementalCount = 0

        // ── 批量预取 node 清单（消 N+1） ──
        // 先扫一遍确定哪些会话要走 node 通道，一次性把它们的清单全查回来，
        // 后面循环里直接命中内存，不再逐个发请求。
        val nodeCandidates = mutableListOf<String>()
        for (row in rows) {
            val id = row.id
            if (row.deleted == 1) continue
            val rowSha = row.sha
            val st = readState(stateKeyConv(id))
            // 与下方主循环的判据保持一致：sha 未变 + 有本地 node 基准 → 走 node 增量
            if (st != null && st.sha == rowSha && readLocalNodeState(id) != null) {
                nodeCandidates += id
            }
        }
        val manifests = prefetchNodeManifests(backend, nodeCandidates)

        for (row in rows) {
            val id = row.id
            val updatedAt = row.updatedAt
            val sha = row.sha
            val deleted = row.deleted == 1
            if (updatedAt > maxUpdatedAt) maxUpdatedAt = updatedAt

            val uuid = runCatching { Uuid.parse(id) }.getOrElse { continue }
            if (deleted) {
                // Tombstone must win before sha short-circuiting; older code left sha
                // unchanged on delete, which made peers skip deletion forever.
                //
                // ⚠️ 但 schedule agent 的活跃常驻会话绝不能被远端 tombstone 杀掉！
                //
                // 场景：设备 B 不跑 schedule，那边这个对话只是同步过去的空壳，
                // 用户在 B 上随手删了 / B 上旧版本自动清理了 → tombstone 推上云
                // → 本机 pull → force=true 直接删 → Runner 下一轮找不到对话
                // → 新建空壳（model/workspace/folder/title 全归零）
                // → 全部历史永久丢失。
                //
                // 修复：对话在 agent_session 里且 status 是活跃态（idle/running/
                // waiting_*）→ 拒绝 tombstone，把本地对话回推覆盖 tombstone。
                // archived/done/error 的 session 可以正常删。
                if (isActiveAgentSession(id)) {
                    Log.w(TAG, "tombstone blocked: active schedule session $id, will repush")
                    syncAuditLog("tombstone-blocked", "active-schedule=$id")
                    pendingRepushConversations += id
                } else if (conversationRepository.existsConversationById(uuid)) {
                    // ⚠️ 包 ApplyGate：这是"应用远端 tombstone"，不是本地删除。
                    // deleteConversation 内部会 enqueueSyncOutbox(OP_DELETE)，
                    // 没门就会把刚应用掉的 tombstone 又回推上去 → 云端 updated_at
                    // 被刷新 → 对端再拉再删，形成跨设备删除回环。
                    SyncApplyGate.applyingRemote = true
                    try {
                        conversationRepository.getConversationById(uuid)
                            ?.let { conversationRepository.deleteConversation(it, force = true) }
                    } finally {
                        SyncApplyGate.applyingRemote = false
                    }
                }
                clearLocalNodeState(id)
                saveState(stateKeyConv(id), updatedAt, sha)
                continue
            }

            val state = readState(stateKeyConv(id))
            if (state != null && state.sha == sha) {
                // data 未变：本会话若已是 node 模式（本端曾推送过 node），
                // 对端可能只更新了 conv_nodes（node-only 通道）→ 走 node 增量读取。
                //
                // ⚠️ 全量对账轮**跳过**这一步：对账扫全表，若对每个 sha 一致的行
                // 都拉一次 node 清单，就是 2000+ 次串行往返的 N+1 灾难。node-only
                // 对端的更新会 bump 会话 updated_at，正常增量轮自会捕获。
                if (!fullReconcile && readLocalNodeState(id) != null) {
                    nodeIncrementalCount++
                    pullNodeIncremental(
                        backend, id, updatedAt, sha,
                        // 预取成功时一律传非 null（无行则传空列表），避免「云端该会话
                        // 确实没有节点」被误当成「没预取到」而退回单会话查询。
                        prefetchedManifest = manifests?.let { it[id] ?: emptyList() },
                    )
                }
                continue
            }
            needData += Triple(id, updatedAt, sha)
        }

        // 批量取 data：旧实现是每个会话一次 POST（N+1），拉 10 个会话 = 11 次串行往返。
        needData.chunked(CONV_DATA_FETCH_CHUNK).forEach { chunk ->
            val dataById = backend.pullConversationData(chunk.map { it.first })
            chunk.forEach { (id, updatedAt, sha) ->
                val data = dataById[id] ?: return@forEach
                if (data.isBlank()) {
                    // node-only 对端的行 data 为空：本端若对该会话有 node 基准，改走 node 通道读取
                    if (readLocalNodeState(id) != null) {
                        nodeIncrementalCount++
                        pullNodeIncremental(
                            backend, id, updatedAt, sha,
                            prefetchedManifest = manifests?.let { it[id] ?: emptyList() },
                        )
                    } else {
                        // 云端整包为空（node-only 维护中），本地又没有 node 基准：
                        // 没有可安全重建的数据源，跳过。绝不能拿空串去 apply ——
                        // 那会在本地建一个空壳会话，历史全丢。
                        Log.w(
                            TAG,
                            "pullConversations: blank data & no local node baseline for $id, skip"
                        )
                    }
                    return@forEach
                }
                // ⚠️ 必须包 ApplyGate（2026-09-12 推送风暴根因）。
                //
                // applyRemoteConversation 内部走 insert/updateConversation →
                // ConversationRepository.stampLocalWrite + enqueueSyncOutbox，
                // 这两处都以 SyncApplyGate.applyingRemote 为门：
                //   没门 → stampLocalWrite 把 updateAt 刷成 now（"全部记录变今天"）
                //        → enqueueSyncOutbox 把会话塞进 outbox（回推风暴）
                //
                // 另外三个调用点（563/997/1084）都包了，唯独全量对账这条裸调。
                // 增量时代一轮只 apply 几个，症状轻微；全量对账一轮扫几百个，
                // 直接把这颗暗雷踩爆。
                SyncApplyGate.applyingRemote = true
                try {
                    applyRemoteConversation(id, data, updatedAt, sha)
                } finally {
                    SyncApplyGate.applyingRemote = false
                }
            }
        }

        // 水位只在本轮全部应用完毕后推进；中途抛异常则下次重拉，宁可重复不可丢。
        if (maxUpdatedAt > watermark) {
            saveState(watermarkKey, maxUpdatedAt, "")
        }
        // 对账轮跑完才记时间戳：万一中途异常，下轮重做对账，不会漏。
        if (fullReconcile) {
            saveState(reconcileKey, reconcileNow, "")
        }
        SyncPerfLog.log(
            SyncPerfLog.CHANNEL_PHASE, "pull:conversations",
            "backend=${backend.backendId} rows=${rows.size} needData=${needData.size} " +
                "nodeIncremental=$nodeIncrementalCount watermark=$watermark fullReconcile=$fullReconcile"
        )
    }

    /**
     * 批量预取多个会话的 node 清单（一次 SQL 取代 N 次）。
     *
     * ## 为什么必须有这个
     *
     * [pullNodeIncremental] 每调一次至少 2 次串行往返（清单 + data）。
     * 一轮 pull 里有 48 个会话变动，就是 96 次串行 HTTP —— 即便 sync-proxy
     * 把单次压到 0.9s，累计仍是 86 秒。用户体感「拉取巨慢」的主因不是单次延迟，
     * 而是**往返次数**，这是典型 N+1。
     *
     * 与 [prefetchBundles] 同一套路：清单合并成一条 `IN (...)`，
     * 之后按会话分组在内存里查，不再逐个发请求。
     *
     * 注意只预取**清单**（node_id/sha/idx 等元数据，每行几十字节），不预取 data。
     * data 按需取，避免把没变化的节点正文也拖下来。
     */
    private suspend fun prefetchNodeManifests(
        backend: StorageBackend,
        convIds: List<String>,
    ): Map<String, List<NodeManifestRow>>? {
        if (convIds.isEmpty()) return emptyMap()
        return runCatching {
            // 分块与 SQL 都挪进 backend 了 —— 只有它自己知道本方言的参数上限
            // （D1 是 SQLite 999，PostgREST 靠 URL 长度）。
            val grouped = backend.pullNodeManifests(convIds)
            SyncPerfLog.log(
                SyncPerfLog.CHANNEL_PHASE, "pull:nodeManifestPrefetch",
                "backend=${backend.displayName} convs=${convIds.size} " +
                    "rows=${grouped.values.sumOf { it.size }}"
            )
            grouped
        }.getOrElse {
            // 预取失败不是致命错误：返回 null 让调用方回落到逐会话查询（只慢不错）
            Log.w(TAG, "prefetchNodeManifests failed, falling back to per-conversation query", it)
            null
        }
    }

    private suspend fun applyRemoteConversation(refKey: String, data: String, updatedAt: Long, sha: String) {
        val conv = runCatching { json.decodeFromString<Conversation>(data) }.getOrElse {
            Log.e(TAG, "applyRemoteConversation: decode failed for $refKey", it)
            return
        }
        val hydratedConv = ConversationPartsOffloader.hydrateIfNeeded(conv, r2MediaStore)
        val localConv = conversationRepository.getConversationById(hydratedConv.id)

        // ⚠️ 活跃 schedule session 保护（配套 tombstone 保护，见 pullConversations）。
        //
        // 场景：本机 spawnSchedule 刚建好对话（model/workspace/folder/systemPrompt 全正确），
        // 同一轮 pull 从云端拉回来一个旧版本（另一台设备推上去的空壳 / 不含这些字段的序列化），
        // 直接 updateConversation → 所有配置归零 → 查岗变废物（没模型、没工作区、没文件夹）。
        //
        // 修复：本机有这个对话 + 它是活跃 schedule session → 本地赢、云端的滚蛋、回推。
        // 不是活跃 session 的对话走原来的逻辑不动。
        if (localConv != null && isActiveAgentSession(refKey)) {
            Log.w(TAG, "applyRemoteConversation blocked: active schedule session $refKey, will repush")
            syncAuditLog("remote-overwrite-blocked", "active-schedule=$refKey")
            pendingRepushConversations += refKey
            saveState(stateKeyConv(refKey), updatedAt, sha)
            return
        }

        val localWorkspaceCwd = localConv?.workspaceCwd
        val deviceLocalConv = hydratedConv.copy(workspaceCwd = localWorkspaceCwd)
        if (localConv != null) {
            // ⚠️ 空壳覆盖防线（2026-09-11 数据丢失事故根因）。
            //
            // node-only 模式下 conversations.data 已**停止维护**（pushConversationMetaOnly
            // 把 data 写死空串）。而本函数的入参 data 正是那一列 —— 于是「拿云端整包
            // 覆盖本地」在 node-only 会话上等价于**把本地清空**。
            //
            // 现场：conv e3157067 云端 data=0 字节，本地 112 条被覆盖成空；紧接着
            // 下一轮 push 拿残缺基准做 diff，把云端 112 条全标了 tombstone。
            //
            // 因此：远端整包比本地明显更空时，一律不覆盖，改为回推本地版本。
            // 真实的「对端删了消息」走 node 通道的显式 tombstone，不依赖整包覆盖，
            // 所以这里拦住不会漏掉正常删除。
            val remoteNodes = deviceLocalConv.messageNodes.size
            val localNodes = localConv.messageNodes.size
            val remoteIsHollow = remoteNodes == 0 && localNodes > 0
            val remoteShrinksHard = localNodes >= 8 && remoteNodes < localNodes / 2
            if (remoteIsHollow || remoteShrinksHard) {
                Log.e(
                    TAG,
                    "applyRemoteConversation REFUSED for $refKey: remote would shrink " +
                        "$localNodes -> $remoteNodes nodes (hollow=$remoteIsHollow); repushing local"
                )
                syncAuditLog(
                    "hollow-overwrite-blocked",
                    "conv=$refKey local=$localNodes remote=$remoteNodes"
                )
                pendingRepushConversations += refKey
                // 基准**不推进**：让下一轮重新裁决，避免这个坏版本被当成已消费
                return
            }
            conversationRepository.updateConversation(deviceLocalConv)
        } else {
            conversationRepository.insertConversation(deviceLocalConv)
        }
        saveState(stateKeyConv(refKey), updatedAt, sha)
    }

    /**
     * P3 S3：pull 侧 node 增量读取。
     *
     * 前提：本端对该会话已有 node 基准（本地 push 过），此时云端 conversations.data
     * 可能已停止维护（node-only 对端只写 conv_nodes）。做法：
     * 1. 拉该会话 conv_nodes 清单，与本地 node 基准比对，只取 sha 变化的 data（批量 IN）
     * 2. 云端节点按 idx 重排重建；本地存在而云端缺失的节点保留在本地末尾并回推
     *    （云端缺失 = 对端旧版本只写过整包 data 的场景，避免丢失）
     * 3. 会话元数据（title/assistantId/文件夹等）沿用本地，room 不感知同步
     */
    private suspend fun pullNodeIncremental(
        backend: StorageBackend,
        convId: String,
        updatedAt: Long,
        sha: String,
        /**
         * 预取的 node 清单（[prefetchNodeManifests] 的结果）。
         * 传入则省掉本会话的清单查询；为 null 时回落到单会话查询。
         */
        prefetchedManifest: List<NodeManifestRow>? = null,
    ) {
        val uuid = runCatching { Uuid.parse(convId) }.getOrElse { return }
        // 方案 B：带上 seq_key（跨端确定性排序键）并在云端就排好序。
        // ORDER BY 放在 SQL 侧而不是拉回本地再排：seq_key 是定长零填充字符串，
        // 字典序 == 数值序，SQLite 直接算就是对的。
        //
        // 清单优先用批量预取的结果：一轮 pull 有几十个会话变动时，
        // 逐个查清单就是 N 次串行往返，这是 pull 慢的首要原因。
        val rows: List<NodeManifestRow> = prefetchedManifest ?: backend.pullNodeManifest(convId, null)
        if (rows.isEmpty()) return

        data class CloudNode(
            val nodeId: String,
            val idx: Int,
            val sha: String,
            val deleted: Boolean,
            val seqKey: String,
        )

        val cloud = rows.map { row ->
            CloudNode(
                nodeId = row.nodeId,
                idx = row.idx,
                sha = row.sha,
                deleted = row.deleted == 1,
                seqKey = row.seqKey,
            )
        }
        val alive = cloud.filter { !it.deleted }
        if (alive.isEmpty()) return

        val localState = readLocalNodeState(convId) ?: return
        val need = alive.filter { localState[it.nodeId] != it.sha }
        if (need.isEmpty()) return

        // 批量取需要更新的 node data
        val dataById = need.chunked(CONV_DATA_FETCH_CHUNK).flatMap { chunk ->
            backend.pullNodeData(convId, chunk.map { it.nodeId }).entries
        }.associate { it.key to it.value }
        // 注意：这里**不能**因 dataById 为空就 return —— 云端清单说某些节点变了却一条 data
        // 都没取到，本身就是可疑信号，必须走 reconcile 让安全阀与审计日志留痕。

        val localConv = conversationRepository.getConversationById(uuid) ?: return

        // 只把**成功解码**的节点交给重建器；解码失败的交由「本地补齐」兜住，
        // 绝不能像旧实现那样让 decode 失败等价于「该节点不存在」。
        val fetchedNodes = dataById.mapNotNull { (id, raw) ->
            runCatching { json.decodeFromString<MessageNode>(raw) }.getOrNull()?.let { id to it }
        }.toMap()

        val outcome = NodePullReconciler.reconcile<MessageNode>(
            cloud = cloud.map {
                NodePullReconciler.CloudNode(
                    nodeId = it.nodeId,
                    idx = it.idx,
                    sha = it.sha,
                    deleted = it.deleted,
                    seqKey = it.seqKey,
                )
            },
            localNodes = localConv.messageNodes,
            localState = localState,
            fetchedData = fetchedNodes,
        ) { it.id.toString() }

        when (outcome) {
            NodePullReconciler.Outcome.NoChange -> return

            is NodePullReconciler.Outcome.Abort -> {
                // ★ 安全阀命中：本轮不写库、不推进基准，避免把裁剪结果当成真相
                // 再由 pushConversationNodes 生成 tombstone 把云端历史也删掉。
                Log.e(TAG, "pullNodeIncremental ABORT $convId: ${outcome.reason}")
                syncAuditLog(
                    "node-pull-abort",
                    "conv=$convId local=${localConv.messageNodes.size} reason=${outcome.reason}"
                )
                return
            }

            is NodePullReconciler.Outcome.Merged -> {
                if (outcome.needRepush) {
                    Log.w(TAG, "pullNodeIncremental: $convId needs repush (local-only or filled nodes)")
                    pendingRepushConversations += convId
                }
                val merged = localConv.copy(messageNodes = outcome.nodes)
                // 云端 node 可能含 R2 引用（对端 offload 过大 part），重建后必须 hydrate
                val hydrated = ConversationPartsOffloader.hydrateIfNeeded(merged, r2MediaStore)
                // ⚠️ 包 ApplyGate（2026-09-12 推送风暴第二处根因）。
                //
                // 这是 pull 侧重建，绝不能当成本地编辑。没有门时：
                //   stampLocalWrite 把 updateAt 刷成 now → 全部记录变今天；
                //   enqueueSyncOutbox 把重建的会话塞进 outbox → 回推风暴。
                // node 增量/双写会话走的就是这条路径，命中面极大。
                SyncApplyGate.applyingRemote = true
                try {
                    conversationRepository.updateConversation(hydrated)
                } finally {
                    SyncApplyGate.applyingRemote = false
                }
                saveLocalNodeState(convId, outcome.nextState)
                saveState(stateKeyConv(convId), updatedAt, sha)
            }
        }
    }

    /**
     * 一次性把多个 bundle 取回本地，供 [pullBundleKey] 复用。
     *
     * 两步，共 2 条 SQL：
     * 1. manifest：`k, updated_at, sha`。不含 data，几百字节。
     * 2. 只对「本地 sha 与云端不一致」的 key 拉 data。**没变的一律不传**。
     *
     * 命中率高的时候（稳态下几乎所有 bundle 都没变）第二条查询会被整个跳过，
     * 于是这里的成本就是一条 manifest —— 从 15 次往返 + 全量 data 降到 1 次往返。
     *
     * 失败返回 null，调用方逐个回落到原来的单查询路径：prefetch 是优化，
     * 不能因为它出问题就让同步整个失败。
     */
    private suspend fun prefetchBundles(backend: StorageBackend, keys: List<String>): Map<String, BundleRow>? {
        if (keys.isEmpty()) return emptyMap()
        return runCatching {
            val manifest = backend.pullBundleMeta(keys)

            // 云端存在但本地 sha 已一致的行，data 不必再传
            val staleKeys = mutableListOf<String>()
            val heads = HashMap<String, Pair<Long, String>>(manifest.size)
            manifest.forEach { row ->
                val k = row.k
                val sha = row.sha
                val updatedAt = row.updatedAt
                heads[k] = updatedAt to sha
                val state = readState(stateKeyBundle(k))
                if (state == null || state.sha != sha) staleKeys += k
            }

            val dataByKey = if (staleKeys.isEmpty()) {
                emptyMap()
            } else {
                backend.pullBundleData(staleKeys)
            }

            keys.mapNotNull { k ->
                val head = heads[k] ?: return@mapNotNull null // 云端无此行 → 不放进 map
                k to BundleRow(updatedAt = head.first, sha = head.second, data = dataByKey[k])
            }.toMap()
        }.onFailure {
            Log.w(TAG, "prefetchBundles failed, falling back to per-key queries", it)
        }.getOrNull()
    }

    /**
     * @param prefetched [prefetchBundles] 的结果。非 null 时直接取用，不再发查询；
     *   传 null 表示走原来的「每 key 一条 SELECT」路径。
     */
    private suspend fun pullBundleKey(
        backend: StorageBackend,
        key: String,
        prefetched: Map<String, BundleRow>? = null,
    ) {
        if (key == BUNDLE_SETTINGS_DISPLAY && !SyncLocalPrefs.isDisplaySyncEnabled(context)) return

        val row: BundleRow? = if (prefetched != null) {
            // 注意：key 不在 map 里 == 云端确实没有这一行（prefetch 只收录查到的行），
            // 与「查询失败」不同 —— 后者 prefetched 整个为 null，走不到这个分支。
            prefetched[key]
        } else {
            backend.pullBundleMeta(listOf(key)).firstOrNull()?.let { m ->
                BundleRow(
                    updatedAt = m.updatedAt,
                    sha = m.sha,
                    data = backend.pullBundleData(listOf(key))[key],
                )
            }
        }

        if (row == null) {
            // 云端根本没有这一行。
            //
            // 对 settings 而言这是「云端确认为空」（首次启用同步 / 新账号），
            // 必须开 bootstrap 闸门，否则本机配置一辈子上不了云（§2.5 配套安全阀）。
            // ⚠️ 注意这与「网络失败」有本质区别：查询失败会抛异常，走不到这里；
            // 能拿到空结果集说明确实连上了云端且表里没这行。
            if (key == BUNDLE_SETTINGS) {
                markSettingsBootstrapped(SyncBootstrapGuard.REASON_EMPTY_CLOUD)
            }
            return
        }

        val sha = row.sha
        val state = readState(stateKeyBundle(key))
        if (state != null && state.sha == sha) {
            // 内容与本地账簿一致 = 本机已经拉过这份云端 settings。
            // 这同样算 bootstrap 完成：本机对云端现状是知情的，可以开始 push。
            if (key == BUNDLE_SETTINGS) {
                markSettingsBootstrapped(SyncBootstrapGuard.REASON_PULLED)
            }
            return
        }
        val updatedAt = row.updatedAt
        // prefetch 判定为「已是最新」时不会带 data。走到这里 data 却是 null，
        // 说明 sha 在两条查询之间被改过（对端并发写入）—— 下一轮自然会补上。
        val data = row.data ?: return

        if (key == BUNDLE_SETTINGS) {
            // ★ 只有**确实读懂并应用了**云端 settings 才开 push 闸门（§2.5）。
            // decode 失败返回 false → 不开闸 → 本轮只 pull，避免用本地默认值
            // 覆盖一份其实存在的云端真实配置。
            val applied = applyRemoteSettingsBundle(data, sha)
            if (applied) markSettingsBootstrapped(SyncBootstrapGuard.REASON_PULLED)
            if (!applied) return
        } else {
            applyRemoteBundle(key, data, updatedAt, sha)
        }
    }

    /** 开启 settings push 闸门（幂等，重复调用保留首次来源与时间） */
    private suspend fun markSettingsBootstrapped(reason: String) {
        runCatching {
            SyncBootstrapGuard(database.syncStateDao()).markBootstrapped(reason)
        }.onFailure { Log.w(TAG, "markSettingsBootstrapped failed", it) }
    }

    /**
     * 观测云端所有 shard 行的 hlc，推进本机时钟（v2 §2.6，阶段 A 双写期）。
     *
     * 一条 SQL 批量拉 13 片的 hlc，不拉 data 列，流量 < 1KB。
     * 遍历每个 hlc 调 `SyncClock.observe` ——
     * observe 是 compare-and-store，最多存一次（最大的那个）。
     */
    private suspend fun observeShardClocks(backend: StorageBackend) {
        // 只查 shard 类型的行。legacy 行 (kind='legacy') 没有 hlc 含义，
        // 观测它的 hlc=0 无意义且不会推进时钟。
        val startedAt = System.currentTimeMillis()
        val clocks = backend.observeShardClocks()
        clocks.values.forEach { hlc -> settingsShardPusher.observeRemote(hlc) }
        // 日志：后端名 + 命中行数 + 耗时。这一行是「读侧到底切没切过去」的证据
        SyncPerfLog.log(
            SyncPerfLog.CHANNEL_PHASE, "backend:observeShardClocks",
            "backend=${backend.displayName} rows=${clocks.size} ms=${System.currentTimeMillis() - startedAt}",
        )
    }

    /**
     * 应用云端 settings 整包（legacy 路径）。
     *
     * 单独抽出来只为一件事：**把「是否真的应用成功」告诉调用方**。
     * `applyRemoteBundle` 里十几个分支都用 `getOrElse { return }`，
     * 给它加返回值要改一圈无关分支；而 bootstrap 闸门只关心 settings 这一支。
     *
     * @return false = 没读懂云端 payload。此时**绝不可**开 push 闸门：
     *   那等于用「没拉到」冒充「拉到了」，下一轮就把本地默认值当真相推上云（§2.5）。
     */
    private suspend fun applyRemoteSettingsBundle(data: String, sha: String): Boolean {
        val remote = runCatching { json.decodeFromString<Settings>(data) }.getOrElse {
            Log.e(TAG, "applyRemoteBundle: settings decode failed", it)
            // 解不开云端 payload：这不是「云端是空的」，而是「本机没读懂」。
            // 开闸的话本机会拿默认值覆盖一份其实存在的真实配置。
            syncAuditLog("settings-decode-fail", "sha=$sha len=${data.length}")
            return false
        }
        val local = settingsStore.settingsFlow.value
        val merged = SyncSettingsFilter.mergeRemote(local, remote)
        settingsStore.update(merged)
        // 合并结果与云端 payload 不一致（本地有更新的渠道/助手赢了 LWW）时，
        // 必须把合并后的真相回推，否则本地改动永远到不了对端。
        if (SyncSettingsFilter.forUpload(merged) != SyncSettingsFilter.forUpload(remote)) {
            pendingRepush += BUNDLE_SETTINGS
        }
        return true
    }

    private suspend fun applyRemoteBundle(key: String, data: String, updatedAt: Long, sha: String) {
        when (key) {
            // settings 走专用分支（需要向调用方报告成败，见 applyRemoteSettingsBundle）
            BUNDLE_SETTINGS -> applyRemoteSettingsBundle(data, sha)

            BUNDLE_SETTINGS_DISPLAY -> {
                val display = runCatching { json.decodeFromString<DisplaySetting>(data) }
                    .getOrElse { return }
                val local = settingsStore.settingsFlow.value
                settingsStore.update(local.copy(displaySetting = SyncSettingsFilter.mergeRemoteDisplay(local.displaySetting, display)))
            }

            BUNDLE_MEMORY -> {
                val items = runCatching { json.decodeFromString<List<SyncMemoryItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    database.memoryDao().deleteAllMemories()
                    items.forEach {
                        database.memoryDao().insertMemory(
                            MemoryEntity(
                                id = it.id,
                                assistantId = it.assistantId,
                                content = it.content,
                            )
                        )
                    }
                    // 旧客户端 payload 不带 id 会引发本地 id 漂移；兜底清理悬挂链接
                    database.memoryLinkDao().deleteDanglingLinks()
                    // 记忆全文检索索引重建（Phase 2 关键词路）：云端全量应用后拉齐 FTS
                    runCatching {
                        database.openHelper.writableDatabase.execSQL(
                            "INSERT INTO memory_fts(memory_fts) VALUES('rebuild')"
                        )
                    }
                }
            }

            BUNDLE_MEMORY_LINKS -> {
                val items = runCatching { json.decodeFromString<List<SyncMemoryLinkItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.memoryLinkDao()
                    dao.deleteAll()
                    items.forEach {
                        dao.insert(
                            MemoryLinkEntity(
                                id = it.id,
                                sourceId = it.sourceId,
                                targetId = it.targetId,
                                type = it.type,
                                weight = it.weight,
                                description = it.description,
                                scope = it.scope,
                                createdAt = it.createdAt,
                                validFrom = it.validFrom,
                                validUntil = it.validUntil,
                                supersededById = it.supersededById,
                            )
                        )
                    }
                    // 链接 bundle 先于记忆 bundle 应用（拉取顺序不保证）时同样兜底
                    dao.deleteDanglingLinks()
                }
            }

            BUNDLE_MEMORY_GRAPH_NODES -> {
                val items = runCatching { json.decodeFromString<List<SyncMemoryGraphNodeItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.memoryGraphNodeDao()
                    dao.deleteAll()
                    items.forEach {
                        dao.insert(
                            MemoryGraphNodeEntity(
                                id = it.id,
                                scope = it.scope,
                                title = it.title,
                                content = it.content,
                                importance = it.importance,
                                matchEligibility = it.matchEligibility,
                                folderPath = it.folderPath,
                                sourceConversationId = it.sourceConversationId,
                                createdAt = it.createdAt,
                                updatedAt = it.updatedAt,
                            )
                        )
                    }
                    // 节点 bundle 到达后再清理：此时先到的 link bundle 可以安全校验。
                    database.memoryGraphLinkDao().deleteDangling()
                }
                // 图注册表按约定先于节点落地，因此孤儿检查还必须在 nodes 应用后再跑一次；
                // 这样远端新增图 / 老客户端没有 registry bundle 的组合也能自愈。
                runCatching { memoryGraphRegistry.healOrphanScopes() }
                graphVectorStore.markAllDirty()
            }

            BUNDLE_MEMORY_GRAPHS -> {
                val items = runCatching { json.decodeFromString<List<SyncMemoryGraphItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.memoryGraphDao()
                    dao.deleteAll()
                    dao.upsertAll(
                        items.map {
                            MemoryGraphEntity(
                                id = it.id,
                                slug = it.slug,
                                name = it.name,
                                description = it.description,
                                kind = it.kind,
                                boundAssistantId = it.boundAssistantId,
                                emoji = it.emoji,
                                builtin = it.builtin,
                                createdBy = it.createdBy,
                                sortOrder = it.sortOrder,
                                autoExtractTarget = it.autoExtractTarget,
                                createdAt = it.createdAt,
                                updatedAt = it.updatedAt,
                            )
                        }
                    )
                }
                // 孤儿自愈：注册表整表覆盖后，节点表里可能出现没有归属记录的 scope
                // （对端未升级 / 图 bundle 先到而后被覆盖）。补一条 CUSTOM 记录，
                // 杜绝「节点在但图不见了」的孤儿态 —— 这是本方案里最划算的一条防御。
                runCatching { memoryGraphRegistry.healOrphanScopes() }
            }

            BUNDLE_MEMORY_GRAPH_LINKS -> {
                val items = runCatching { json.decodeFromString<List<SyncMemoryGraphLinkItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.memoryGraphLinkDao()
                    dao.deleteAll()
                    items.forEach {
                        dao.insert(
                            MemoryGraphLinkEntity(
                                id = it.id,
                                scope = it.scope,
                                sourceId = it.sourceId,
                                targetId = it.targetId,
                                type = it.type,
                                weight = it.weight,
                                description = it.description,
                                createdAt = it.createdAt,
                                updatedAt = it.updatedAt,
                            )
                        )
                    }
                    // 不在此处清理：节点 bundle 可能尚未到达，避免永久丢边。
                    // 节点 bundle 应用后再统一清理悬空边。
                }
                graphVectorStore.markAllDirty()
            }

            BUNDLE_FAVORITES -> {
                val items = runCatching { json.decodeFromString<List<SyncFavoriteItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.favoriteDao()
                    // Current bundle shape is whole-table, not per-item tombstones: cloud payload is the source of truth.
                    dao.deleteAll()
                    items.forEach { item ->
                        dao.upsert(
                            FavoriteEntity(
                                id = item.id,
                                type = item.type,
                                refKey = item.refKey,
                                refJson = item.refJson,
                                snapshotJson = item.snapshotJson,
                                metaJson = item.metaJson,
                                createdAt = item.createdAt,
                                updatedAt = item.updatedAt,
                            )
                        )
                    }
                }
            }

            BUNDLE_FOLDERS -> {
                val items = runCatching { json.decodeFromString<List<SyncFolderItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.folderDao()
                    dao.deleteAll()
                    items.forEach { item ->
                        dao.insert(
                            FolderEntity(
                                id = item.id,
                                assistantId = item.assistantId,
                                name = item.name,
                                sortIndex = item.sortIndex,
                                createAt = item.createAt,
                            )
                        )
                    }
                }
            }

            BUNDLE_GENMEDIA -> {
                val items = runCatching { json.decodeFromString<List<SyncGenMediaItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.genMediaDao()
                    dao.deleteAll()
                    items.forEach { item ->
                        dao.insert(
                            GenMediaEntity(
                                path = item.path,
                                modelId = item.modelId,
                                prompt = item.prompt,
                                createAt = item.createAt,
                                type = item.type,
                                sourcePaths = item.sourcePaths,
                                r2Key = item.r2Key,
                                r2Acct = item.r2Acct,
                                originalUrl = item.originalUrl,
                                originalAssetId = item.originalAssetId,
                                previewAssetId = item.previewAssetId,
                            )
                        )
                    }
                }
            }

            BUNDLE_MANAGED_FILES -> {
                val items = runCatching { json.decodeFromString<List<SyncManagedFileItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val dao = database.managedFileDao()
                    items.forEach { item ->
                        val remote = me.rerere.rikkahub.data.db.entity.ManagedFileEntity(
                            id = item.id,
                            folder = item.folder,
                            relativePath = item.relativePath,
                            displayName = item.displayName,
                            mimeType = item.mimeType,
                            sizeBytes = item.sizeBytes,
                            createdAt = item.createdAt,
                            updatedAt = item.updatedAt,
                            r2Key = item.r2Key,
                            r2Acct = item.r2Acct,
                            externalUrl = item.externalUrl,
                            sha256 = item.sha256,
                            contentSha256 = item.contentSha256,
                            nameZh = item.nameZh,
                            nameEn = item.nameEn,
                            prompt = item.prompt,
                            description = item.description,
                            ocrText = item.ocrText,
                            deleted = item.deleted,
                        )
                        val local = dao.getById(remote.id)
                        if (remote.deleted) {
                            when {
                                local == null -> runCatching { dao.insert(remote) }
                                    .onFailure { e -> Log.w(TAG, "apply managed_files: skip deleted asset ${remote.id}", e) }

                                remote.updatedAt > local.updatedAt -> {
                                    deleteLocalManagedFile(local)
                                    dao.update(remote)
                                }
                            }
                        } else if (local == null || remote.updatedAt > local.updatedAt) {
                            runCatching { dao.insert(remote) }
                                .onFailure { e ->
                                    Log.w(TAG, "apply managed_files: skip conflicting asset ${remote.id}/${remote.relativePath}", e)
                                }
                        }
                    }
                }
            }

            BUNDLE_ASSET_LABELS -> {
                val items = runCatching { json.decodeFromString<List<SyncAssetLabelItem>>(data) }
                    .getOrElse { return }
                database.withTransaction {
                    val labelDao = database.assetLabelDao()
                    val fileDao = database.managedFileDao()
                    // 全量替换：标签是「小而全」的集合，逐行 diff 不划算，
                    // 而且删除标签本身要能同步过来 —— 增量 upsert 表达不了删除。
                    labelDao.deleteAll()
                    val rows = items.mapNotNull { item ->
                        // FK 约束：资产还没同步过来时先丢掉这条引用，
                        // 下一轮 managed_files 到位后 requeue 会重推一次完整标签集。
                        if (fileDao.getById(item.assetId) == null) return@mapNotNull null
                        me.rerere.rikkahub.data.db.entity.AssetLabelEntity(
                            assetId = item.assetId,
                            kind = item.kind,
                            value = item.value,
                            createdAt = item.createdAt,
                        )
                    }
                    if (rows.isNotEmpty()) labelDao.insertAll(rows)
                }
            }

            BUNDLE_SUBAGENT_TEMPLATES -> importSubagentTemplates(data)

            BUNDLE_SKILLS -> importSkills(data)

            BUNDLE_SCHEDULED_NOTIFICATIONS -> {
                val items = runCatching { json.decodeFromString<List<ScheduledNotificationItem>>(data) }
                    .getOrElse { return }
                ScheduledNotificationManager.replaceFromSync(context, items)
            }
        }
        saveState(stateKeyBundle(key), updatedAt, sha)
    }

    private fun deleteLocalManagedFile(entity: me.rerere.rikkahub.data.db.entity.ManagedFileEntity) {
        if (entity.relativePath.isBlank() || entity.relativePath.startsWith("remote/")) return
        val file = if (entity.folder == FileFolders.TTS_CACHE) {
            val relative = if (entity.relativePath.startsWith("${FileFolders.TTS_CACHE}/")) {
                entity.relativePath
            } else {
                "${FileFolders.TTS_CACHE}/${entity.relativePath}"
            }
            File(context.cacheDir, relative)
        } else {
            File(AppPaths.filesDir(context), entity.relativePath)
        }
        runCatching { if (file.isFile) file.delete() }
            .onFailure { Log.w(TAG, "delete local managed file failed: ${entity.relativePath}", it) }
    }

    // ---------------- Seeding（首次装机全量上推） ----------------

    /** 把本地会话/设置/记忆入队上推；force=false 时只入队本地时间戳与同步基线不一致的会话。 */
    suspend fun seedLocalData(force: Boolean = false): Int {
        val outbox = database.syncOutboxDao()
        val now = System.currentTimeMillis()
        val ids = conversationRepository.getAllConversationIds()
        var enqueuedConversations = 0
        ids.forEach { id ->
            if (!force) {
                val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@forEach
                val localUpdatedAt = conversationRepository.getConversationById(uuid)?.updateAt?.toEpochMilli()
                    ?: return@forEach
                val syncedUpdatedAt = readStateUpdatedAt(stateKeyConv(id))
                if (syncedUpdatedAt == localUpdatedAt) return@forEach
            }
            outbox.deleteByRef(SyncOutboxEntity.KIND_CONVERSATION, id)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_CONVERSATION,
                    refKey = id,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = now,
                )
            )
            enqueuedConversations += 1
        }
        listOf(
            BUNDLE_SETTINGS,
            BUNDLE_SETTINGS_DISPLAY,
            BUNDLE_MEMORY,
            BUNDLE_MEMORY_LINKS,
            BUNDLE_MEMORY_GRAPHS,
            BUNDLE_MEMORY_GRAPH_NODES,
            BUNDLE_MEMORY_GRAPH_LINKS,
            BUNDLE_FAVORITES,
            BUNDLE_FOLDERS,
            BUNDLE_GENMEDIA,
            BUNDLE_MANAGED_FILES,
            BUNDLE_ASSET_LABELS,
            BUNDLE_SUBAGENT_TEMPLATES,
            BUNDLE_SKILLS,
            // 2026-09-19: 不再预热 BUNDLE_SCHEDULED_NOTIFICATIONS（已走 R2）
        ).forEach {
            outbox.deleteByRef(SyncOutboxEntity.KIND_BUNDLE, it)
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = it,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = now,
                )
            )
        }
        return enqueuedConversations
    }

    // ---------------- 状态簿（sync_state） ----------------

    private data class State(val updatedAt: Long, val sha: String)

    /**
     * 预取回来的一行 bundle。
     *
     * `data` 可空是刻意的：prefetch 判定本地已是最新时不会去拉 data 列
     * （省流量），此时 null 表示「没必要传」而不是「云端为空」。
     */
    private data class BundleRow(val updatedAt: Long, val sha: String, val data: String?)

    private fun stateKeyConv(id: String) = "conv:$id"

    /** 同步审计（见 [SyncAuditLog]）：本地数据被改写 / 安全阀命中时留痕 */
    /**
     * 对话是否是本机活跃的 agent session（schedule / subagent 都算）。
     *
     * 「活跃」= status 不是 archived / done / error。
     * 活跃 session 的对话绝不能被远端同步操作（tombstone / 整体覆盖）杀掉，
     * 否则 Runner 下一轮找不到对话就会新建空壳，全部历史永久丢失。
     *
     * 这里只做判定不做修改，调用方自行决定是跳过还是回推。
     */
    private suspend fun isActiveAgentSession(conversationId: String): Boolean {
        val session = runCatching {
            database.agentSessionDao().getByChildId(conversationId)
        }.getOrNull() ?: return false
        return session.status !in AgentStatuses.TERMINAL
    }

    private fun syncAuditLog(category: String, message: String) {
        SyncAuditLog.write(context, category, message)
    }
    private fun stateKeyBundle(k: String) = "bundle:$k"

    private suspend fun readState(key: String): State? {
        val e = database.syncStateDao().get(key) ?: return null
        return runCatching {
            val o = json.parseToJsonElement(e.value).jsonObject
            State(
                updatedAt = o["updated_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sha = o["sha"]?.jsonPrimitive?.content ?: "",
            )
        }.getOrNull()
    }

    private suspend fun readStateUpdatedAt(key: String): Long? = readState(key)?.updatedAt

    private suspend fun saveState(key: String, updatedAt: Long, sha: String) {
        database.syncStateDao().put(
            SyncStateEntity(
                key = key,
                value = buildJsonObject {
                    put("updated_at", updatedAt)
                    put("sha", sha)
                }.toString(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    // ---------------- P3：node 级本地基准（sync_state，key = sync:convnodes:<convId>） ----------------

    private fun localNodeStateKey(convId: String) = "$STATE_CONV_NODES_PREFIX$convId"

    /** 该会话上次成功推送后的 nodeId -> sha；从未推送过（或已清空）返回 null */
    private suspend fun readLocalNodeState(convId: String): Map<String, String>? {
        val e = database.syncStateDao().get(localNodeStateKey(convId)) ?: return null
        return runCatching {
            val nodes = json.parseToJsonElement(e.value).jsonObject["nodes"] as? JsonObject ?: return null
            nodes.mapValues { it.value.jsonPrimitive.content }
        }.getOrNull()
    }

    private suspend fun saveLocalNodeState(convId: String, nodes: Map<String, String>) {
        database.syncStateDao().put(
            SyncStateEntity(
                key = localNodeStateKey(convId),
                value = buildJsonObject {
                    put("nodes", buildJsonObject { nodes.forEach { (k, v) -> put(k, v) } })
                }.toString(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun clearLocalNodeState(convId: String) {
        database.syncStateDao().delete(localNodeStateKey(convId))
    }

    // ---------------- 工具 ----------------

    private fun requireClient(requireEnabled: Boolean = true): D1Client? {
        val cfg = settingsStore.settingsFlow.value.d1Config
        if (requireEnabled) {
            if (!cfg.isConfigured) return null
        } else {
            if (!cfg.hasRequiredFields) return null
        }
        return D1Client(cfg, httpClient, currentProxyConfig())
    }

    /**
     * 解析当前生效的存储后端（多后端抽象 · Step G 过渡期）。
     *
     * 两条路，顺序即优先级：
     * 1. **新路径**：[Settings.backends] 里第一个「已开启且配齐」的后端；
     * 2. **回落**：老的 `d1Config`。升级上来的人不会因为新列表还空着就同步不了 ——
     *    这条是过渡期的安全网，等 UI 切完（Step H）再拆。
     *
     * 与 [requireClient] **并存是刻意的**：Step G 分批切，pull 侧先走语义接口、
     * push 侧仍走原 SQL 三段式。两边各自构造实例，只多一次对象分配，无网络开销。
     *
     * 日志：每次解析都打一行 `backend/resolve`，把「这一轮到底走的哪个后端」
     * 钉在性能日志里 —— 出问题时第一眼要看的就是这个。
     */
    private fun requireBackend(requireEnabled: Boolean = true): StorageBackend? {
        val settings = settingsStore.settingsFlow.value

        settings.backends.firstOrNull { it.enabled && it.isConfigured }?.let { cfg ->
            SyncPerfLog.log(
                SyncPerfLog.CHANNEL_PHASE, "backend:resolve",
                "via=backends id=${cfg.id} type=${cfg.typeName} alias=${cfg.alias}",
            )
            return StorageBackendFactory.create(cfg, httpClient)
        }

        return legacyBackend(requireEnabled)?.also {
            SyncPerfLog.log(SyncPerfLog.CHANNEL_PHASE, "backend:resolve", "via=legacy-d1Config")
        }
    }

    /** 老的 `d1Config` 包成后端实例。**读路径要求 requireEnabled=false**（关掉也得读得回来）。 */
    private fun legacyBackend(requireEnabled: Boolean = true): StorageBackend? {
        val d1 = settingsStore.settingsFlow.value.d1Config
        val ready = if (requireEnabled) d1.isConfigured else d1.hasRequiredFields
        if (!ready) return null

        val proxy = currentProxyConfig()
        val cfg = StorageBackendConfig.D1(
            id = LEGACY_D1_BACKEND_ID,
            alias = "D1（旧配置）",
            enabled = true,
            accountId = d1.accountId,
            databaseId = d1.databaseId,
            apiToken = d1.apiToken,
            proxyUrl = if (proxy.usable) proxy.baseUrl else "",
            proxySecret = if (proxy.usable) proxy.secret else "",
            proxyFallbackToRest = proxy.fallbackToRest,
            proxyMaxBatchSize = proxy.maxBatchSize,
            proxyTimeoutMs = proxy.timeoutMs,
        )
        return StorageBackendFactory.create(cfg, httpClient)
    }

    /**
     * 读路径要覆盖的后端集合（多后端 · Step I-5）。
     *
     * **不看 `enabled`**：关闭只表示「不再接收新推送」，历史数据还得读得回来 ——
     * 这是可回退切换的前提（同 [StorageBackendRouter.readTarget]）。
     *
     * 返回顺序 = `rangeStart` 升序（老的在前、新的在后）。读**会话**时顺序无所谓
     * （每个后端各一份水位），但 **bundles 是「当前状态」而不是历史**，
     * 必须让最新的那个赢 —— 顺序反了就会拿旧库的 settings 覆盖新库的。
     *
     * 只要旧 `d1Config` 还配着就一并纳入：它就是「09-16 之前那批会话」的家，
     * 新列表里没有它，漏掉它等于老会话全部拉不到。
     */
    private fun readableBackends(): List<StorageBackend> {
        val settings = settingsStore.settingsFlow.value
        val out = mutableListOf<Pair<Long, StorageBackend>>()
        settings.backends
            .filter { it.isConfigured }
            .forEach {
                out += (it.rangeStart ?: Long.MIN_VALUE) to StorageBackendFactory.create(it, httpClient)
            }
        if (settings.backends.none { it.id == StorageBackendConfig.LEGACY_D1_BACKEND_ID }) {
            legacyBackend(requireEnabled = false)?.let { out += Long.MIN_VALUE to it }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** 可写后端（`enabled` + 配齐），`rangeStart` 升序。墓碑扇出用。 */
    private fun writableBackends(): List<StorageBackend> {
        val settings = settingsStore.settingsFlow.value
        val out = settings.backends
            .filter { it.enabled && it.isConfigured }
            .sortedBy { it.rangeStart ?: Long.MIN_VALUE }
            .map { StorageBackendFactory.create(it, httpClient) }
            .toMutableList()
        if (settings.backends.none { it.id == StorageBackendConfig.LEGACY_D1_BACKEND_ID }) {
            legacyBackend()?.let { out += it }
        }
        return out
    }

    /** 按会话建立时间选**读**后端（`readTarget`：不看 enabled —— 关了的数据也得读得回来）。 */
    private fun readBackendForCreateAt(createdAt: Long): StorageBackend? {
        val settings = settingsStore.settingsFlow.value
        StorageBackendRouter.readTarget(settings.backends, createdAt)?.let { cfg ->
            return StorageBackendFactory.create(cfg, httpClient)
        }
        return legacyBackend(requireEnabled = false)
    }

    /**
     * 按会话**建立时间**选写后端（时间分片路由）。
     *
     * 判据必须是数据的固有属性 —— 按写入时间分片，一条老会话今天被改就得跨库搬家，
     * 于是要迁移管道 + 复活路径 + 路由表。按 `createAt` 分片，归属是纯函数，
     * 随时可重算、零状态、零迁移（同 [StorageBackendRouter] 的设计说明）。
     *
     * 命中不了任何配置（时间段没盖住 / 全关了）→ 回落 legacy `d1Config`。
     * 那是「新列表还没配齐」的过渡安全网，也是老会话唯一的去处。
     */
    private fun backendForCreateAt(createdAt: Long): StorageBackend? {
        val settings = settingsStore.settingsFlow.value
        StorageBackendRouter.writeTarget(settings.backends, createdAt)?.let { cfg ->
            SyncPerfLog.log(
                SyncPerfLog.CHANNEL_PHASE, "backend:route",
                "createdAt=$createdAt -> id=${cfg.id} type=${cfg.typeName}",
            )
            return StorageBackendFactory.create(cfg, httpClient)
        }
        return legacyBackend()
    }

    /**
     * bundles / settings 的写目标：**永远走最新的那个后端**。
     *
     * settings 是「当前状态」而不是历史，按时间段切没有意义；它们量小、改动频繁，
     * 放在最新后端才不会一改就往老库写（同 [StorageBackendRouter.latestWriteTarget]）。
     */
    private fun bundleWriteBackend(): StorageBackend? {
        val settings = settingsStore.settingsFlow.value
        StorageBackendRouter.latestWriteTarget(settings.backends)?.let { cfg ->
            return StorageBackendFactory.create(cfg, httpClient)
        }
        return legacyBackend()
    }

    /**
     * 用一份后端配置建实例并做连通性自检（多后端 · Step H）。
     *
     * 不要求该配置已启用、也不必已落盘 —— 设置页的「测试连接」拿草稿态直接调，
     * 免得用户为了测一下还得先保存。构造走 [StorageBackendFactory]，与真实同步路径同源，
     * 测得过就是真能跑。
     */
    suspend fun testBackend(config: StorageBackendConfig): BackendInfo =
        StorageBackendFactory.create(config, httpClient).testConnection()

    /**
     * 从 [SyncAdvancedConfig] 取当前代理参数。
     *
     * 每次构造 client 时现读，不缓存：用户在设置页改完开关/地址应当立刻生效，
     * 缓存会让「改了没反应」重演一遍（同 SyncNotifyClient 的 configWatcher 教训）。
     */
    private fun currentProxyConfig(): D1ProxyConfig {
        val cfg = syncAdvancedConfigStore.current
        return D1ProxyConfig(
            enabled = cfg.syncProxyEnabled,
            baseUrl = cfg.syncProxyUrl,
            secret = cfg.syncProxySecret,
            fallbackToRest = cfg.syncProxyFallbackToRest,
            maxBatchSize = cfg.syncProxyMaxBatchSize,
            timeoutMs = cfg.syncProxyTimeoutMs,
        )
    }

    private suspend fun ensureSchema(client: D1Client) {
        if (!schemaEnsured) {
            D1Schema.ensure(client)
            schemaEnsured = true
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    private fun JsonObject.long(name: String): Long? =
        this[name]?.let { if (it is JsonNull) null else it.jsonPrimitive.content.toLongOrNull() }

    private fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
