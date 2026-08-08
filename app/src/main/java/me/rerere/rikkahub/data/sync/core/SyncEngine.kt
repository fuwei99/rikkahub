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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.DisplaySetting
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
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationItem
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import me.rerere.rikkahub.data.db.entity.ScreenTimeDayEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryGraphRegistry
import me.rerere.rikkahub.data.screentime.CLOUD_RETENTION_DAYS
import me.rerere.rikkahub.data.screentime.SCREEN_TIME_BUNDLE_PREFIX
import me.rerere.rikkahub.data.screentime.SyncScreenTimeAppItem
import me.rerere.rikkahub.data.screentime.SyncScreenTimeDayItem
import me.rerere.rikkahub.data.sync.d1.D1Client
import me.rerere.rikkahub.data.sync.d1.D1Schema
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
 * 跨设备屏幕时间（方案 2026-08-09）：key = screen_time:<deviceId>，按设备隔离，
 * 前缀常量与 payload 模型定义在 data.screentime（采集器/工具共用）。
 */
const val BUNDLE_SCREEN_TIME_PREFIX = SCREEN_TIME_BUNDLE_PREFIX

/** conversations 增量拉取水位（sync_state 本地键，非云端 bundle） */
private const val STATE_CONV_WATERMARK = "sync:conv_watermark"

/** P3 node 级本地状态前缀：sync_state 键 = 该前缀 + convId，value = {"nodes":{nodeId:sha}} */
private const val STATE_CONV_NODES_PREFIX = "sync:convnodes:"

/** 批量取 conversation data 的单批上限（D1 位置参数有上限，留足余量） */
private const val CONV_DATA_FETCH_CHUNK = 20

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
) {
    private val pushMutex = Mutex()
    private val pullMutex = Mutex()
    private var schemaEnsured = false

    /** 最后一次成功同步时间（仅内存，UI 展示用） */
    private val _lastSyncedAt = MutableStateFlow(0L)
    val lastSyncedAt: StateFlow<Long> = _lastSyncedAt.asStateFlow()

    /**
     * 分叉另存回调（conversationId, 分支标题）。
     * ChatService 在初始化时注入，避免 SyncEngine 直接依赖 ChatService 造成 Koin 循环。
     */
    @Volatile
    var onConversationForked: ((String, String) -> Unit)? = null

    /** pull 内存下需要在 ApplyGate 关闭后重推的 bundle key */
    private val pendingRepush = mutableSetOf<String>()

    /** pull 重建后本地含云端缺失节点、需要回推的会话 id（P3） */
    private val pendingRepushConversations = mutableSetOf<String>()

    private var consecutiveFailures = 0
    private var circuitBreakerOpenTime: Long = 0L
    private val _isCircuitBreakerOpen = MutableStateFlow(false)
    val isCircuitBreakerOpen: StateFlow<Boolean> = _isCircuitBreakerOpen.asStateFlow()

    fun resetCircuitBreaker() {
        consecutiveFailures = 0
        circuitBreakerOpenTime = 0L
        _isCircuitBreakerOpen.value = false
    }

    private fun checkCircuitBreaker(): Boolean {
        if (!_isCircuitBreakerOpen.value) return false
        val now = System.currentTimeMillis()
        if (now - circuitBreakerOpenTime > syncAdvancedConfigStore.current.circuitBreakerCooldownMs) {
            resetCircuitBreaker()
            return false
        }
        return true
    }

    private fun recordSuccess() {
        consecutiveFailures = 0
        if (_isCircuitBreakerOpen.value) {
            _isCircuitBreakerOpen.value = false
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
            Log.w(TAG, "$tag skipped: circuit breaker is OPEN")
            if (force) throw IllegalStateException("Cloud sync is paused after repeated errors; retry later or test the connection")
            return false
        }
        return true
    }

    suspend fun syncCycle(force: Boolean = false) {
        if (!guardEntry(force, "syncCycle")) return
        var failure: Throwable? = null
        pushMutex.withLock {
            runCatching { flushOutbox(reportQuarantined = force) }
                .onFailure {
                    failure = it
                    Log.e(TAG, "syncCycle push failed; pull will still run", it)
                }
        }
        pullMutex.withLock {
            runCatching { pullAll() }
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
        val client = requireClient() ?: return
        ensureSchema(client)
        val outbox = database.syncOutboxDao()
        val failures = mutableListOf<String>()
        val attempted = mutableSetOf<Long>()
        while (true) {
            val pending = outbox.pending(now = System.currentTimeMillis(), limit = 50)
                .filter { it.id !in attempted }
            if (pending.isEmpty()) break
            pending.forEach { item ->
                attempted += item.id
                try {
                    processOutboxItem(client, item)
                    outbox.deleteByIds(listOf(item.id))
                } catch (e: Throwable) {
                    val verdict = SyncFailureClassifier.classify(e)
                    // 协程取消是正常生命周期事件，不是数据问题：不记账、不判刑，直接上抛。
                    if (verdict == SyncFailureClassifier.Verdict.CANCELLED) throw e
                    val msg = (e.message ?: e.toString()).take(200)
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

    private suspend fun processOutboxItem(client: D1Client, item: SyncOutboxEntity) {
        when (item.kind) {
            SyncOutboxEntity.KIND_CONVERSATION ->
                if (item.op == SyncOutboxEntity.OP_DELETE) {
                    tombstoneRemoteConversation(client, item.refKey)
                } else {
                    pushConversation(client, item.refKey)
                }

            SyncOutboxEntity.KIND_BUNDLE -> pushBundle(client, item.refKey)
        }
    }

    private suspend fun pushConversation(client: D1Client, refKey: String) {
        val uuid = runCatching { Uuid.parse(refKey) }.getOrElse { return }
        val conv = conversationRepository.getConversationById(uuid)
        if (conv == null) {
            tombstoneRemoteConversation(client, refKey)
            return
        }
        val syncConv = conv.copy(workspaceCwd = null)
        val slimConv = ConversationPartsOffloader.offloadIfNeeded(syncConv, r2MediaStore)
        val updatedAt = conv.updateAt.toEpochMilli()
        val myDevice = SyncLocalPrefs.tieBreakKey(context)

        // ---- P3 S2：node 级增量（双写期也维护 conv_nodes，为 S5 铺路）----
        pushConversationNodes(client, refKey, slimConv.messageNodes, myDevice)

        if (syncAdvancedConfigStore.current.nodeOnlyPush) {
            // S5：上行只走 node 通道；conversations 行仅维护水位与标题，不写 data/sha
            pushConversationMetaOnly(client, refKey, conv, updatedAt, myDevice)
            return
        }

        // ---- 整包双写（原有路径；乐观写 + 前缀快进合并）----
        val data = json.encodeToString(slimConv)
        val sha = sha256Hex(data)
        val base = readStateUpdatedAt(stateKeyConv(refKey)) ?: 0L

        // 乐观写：基线命中则直推。锁已取消，这里不再有任何额外往返。
        val updated = client.query(
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
            val inserted = client.query(
                "INSERT OR IGNORE INTO conversations(id, title, updated_at, deleted, sha, data, last_device) VALUES(?,?,?,0,?,?,?)",
                listOf(refKey, conv.title, updatedAt, sha, data, myDevice)
            )
            if (inserted.changes > 0) {
                saveState(stateKeyConv(refKey), updatedAt, sha)
                return
            }
        }

        resolveConversationConflict(client, refKey, conv, data, sha, updatedAt, myDevice)
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
        client: D1Client,
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
        if (result.statements.isNotEmpty()) {
            client.batch(result.statements)
        }
        // batch 成功后才推进基准；失败（异常抛出）则保留旧 state，下次重试全量重 diff
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
        client: D1Client,
        refKey: String,
        conv: Conversation,
        updatedAt: Long,
        myDevice: String,
    ) {
        val bumped = maxOf(updatedAt, (readStateUpdatedAt(stateKeyConv(refKey)) ?: 0L) + 1)
        val updated = client.query(
            "UPDATE conversations SET title = ?, updated_at = ?, deleted = 0, last_device = ? WHERE id = ?",
            listOf(conv.title, bumped, myDevice, refKey)
        )
        if (updated.changes == 0L) {
            client.query(
                "INSERT OR IGNORE INTO conversations(id, title, updated_at, deleted, sha, data, last_device) VALUES(?,?,?,0,'','',?)",
                listOf(refKey, conv.title, bumped, myDevice)
            )
        }
        saveState(stateKeyConv(refKey), bumped, "")
    }

    /**
     * 乐观写未命中：拉下远端做前缀快进合并（参见 [ConversationMerger]）。
     *
     * 旧实现是拿两台设备的墙钟比大小做 LWW，输的一方整个会话被覆盖；
     * 现在只有真分叉才产生分支，"另一台设备多发了几条" 直接快进，一条不丢。
     */
    private suspend fun resolveConversationConflict(
        client: D1Client,
        refKey: String,
        local: Conversation,
        data: String,
        sha: String,
        updatedAt: Long,
        myDevice: String,
    ) {
        val row = client.query(
            "SELECT updated_at, sha, data, last_device FROM conversations WHERE id = ?",
            listOf(refKey)
        ).results.firstOrNull()

        if (row == null) {
            client.query(
                "INSERT OR REPLACE INTO conversations(id, title, updated_at, deleted, sha, data, last_device) VALUES(?,?,?,0,?,?,?)",
                listOf(refKey, local.title, updatedAt, sha, data, myDevice)
            )
            saveState(stateKeyConv(refKey), updatedAt, sha)
            return
        }

        val remoteUpdatedAt = row.long("updated_at") ?: 0L
        val remoteDevice = row.string("last_device")
        val remoteData = row.string("data")

        // 上一次写入者就是本机：自己覆盖自己不算冲禁，直接快进，省下一次解析。
        if (!remoteDevice.isNullOrBlank() && remoteDevice == myDevice) {
            forcePushConversation(client, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)
            return
        }

        val remoteConv = remoteData?.let {
            runCatching { json.decodeFromString<Conversation>(it) }.getOrNull()
        }
        if (remoteConv == null) {
            // 远端不可解析（旧格式/损坏）：保守回退到本地优先强推，不丢本机数据
            Log.w(TAG, "resolveConversationConflict: remote data unreadable for $refKey, force pushing local")
            forcePushConversation(client, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)
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
                saveState(stateKeyConv(refKey), remoteUpdatedAt, row.string("sha") ?: sha)
            }

            is ConversationMerger.Resolution.KeepLocal ->
                forcePushConversation(client, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)

            is ConversationMerger.Resolution.TakeRemote -> {
                SyncApplyGate.applyingRemote = true
                try {
                    applyRemoteConversation(refKey, remoteData, remoteUpdatedAt, row.string("sha") ?: "")
                } finally {
                    SyncApplyGate.applyingRemote = false
                }
            }

            is ConversationMerger.Resolution.Fork -> {
                if (resolution.localKeepsId) {
                    // 用户拍板：本机保留原 id，云端版本另存为 xxx-<对端 label>
                    forkRemoteCopy(remoteConv, remoteDevice)
                    forcePushConversation(client, refKey, local.title, data, sha, updatedAt, remoteUpdatedAt, myDevice)
                } else {
                    // 对端裁决胜出（它也会把我的版本另存）：本机自己另存后快进远端
                    forkLocalCopy(local)
                    SyncApplyGate.applyingRemote = true
                    try {
                        applyRemoteConversation(refKey, remoteData, remoteUpdatedAt, row.string("sha") ?: "")
                    } finally {
                        SyncApplyGate.applyingRemote = false
                    }
                }
            }
        }
    }

    /** 放弃基线强推；updated_at 严格递增，避免写入比云端还小的值导致下次又被判输 */
    private suspend fun forcePushConversation(
        client: D1Client,
        refKey: String,
        title: String,
        data: String,
        sha: String,
        updatedAt: Long,
        remoteUpdatedAt: Long,
        myDevice: String,
    ) {
        val bumped = maxOf(updatedAt, remoteUpdatedAt + 1)
        client.query(
            "UPDATE conversations SET title = ?, updated_at = ?, deleted = 0, sha = ?, data = ?, last_device = ? WHERE id = ?",
            listOf(title, bumped, sha, data, myDevice, refKey)
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

    private suspend fun tombstoneRemoteConversation(client: D1Client, refKey: String) {
        val now = System.currentTimeMillis()
        // Keep sha/data in sync with the tombstone. If sha remains the old conversation sha,
        // other devices can skip the row before seeing deleted=1 and never delete locally.
        val updated = client.query(
            "UPDATE conversations SET title = '', updated_at = ?, deleted = 1, sha = 'tombstone', data = '' WHERE id = ?",
            listOf(now, refKey)
        )
        if (updated.changes == 0L) {
            client.query(
                "INSERT OR IGNORE INTO conversations(id, title, updated_at, deleted, sha, data) VALUES(?,?,?,1,'tombstone','')",
                listOf(refKey, "", now)
            )
        }
        // P3：顺带 tombstone 该会话的全部 node 行，并清本地 node 基准
        runCatching {
            client.query(
                "UPDATE conv_nodes SET deleted = 1, updated_at = ?, sha = 'tombstone' WHERE conv_id = ?",
                listOf(now, refKey)
            )
        }.onFailure { Log.w(TAG, "tombstone conv_nodes failed for $refKey", it) }
        clearLocalNodeState(refKey)
        saveState(stateKeyConv(refKey), now, "tombstone")
    }

    private suspend fun pushBundle(client: D1Client, key: String) {
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

            // 跨设备屏幕时间：key = screen_time:<deviceId>，每台设备只写自己的行
            key.startsWith(BUNDLE_SCREEN_TIME_PREFIX) -> exportScreenTime(key.removePrefix(BUNDLE_SCREEN_TIME_PREFIX))

            else -> return
        }
        val sha = sha256Hex(payload)
        val now = System.currentTimeMillis()
        val state = readState(stateKeyBundle(key))
        if (state?.sha == sha) return
        val base = state?.updatedAt ?: 0L

        val updated = client.query(
            "UPDATE bundles SET updated_at = ?, deleted = 0, sha = ?, data = ? WHERE k = ? AND updated_at = ?",
            listOf(now, sha, payload, key, base)
        )
        if (updated.changes > 0) {
            saveState(stateKeyBundle(key), now, sha)
            return
        }
        if (base == 0L) {
            val inserted = client.query(
                "INSERT OR IGNORE INTO bundles(k, updated_at, deleted, sha, data) VALUES(?,?,0,?,?)",
                listOf(key, now, sha, payload)
            )
            if (inserted.changes > 0) {
                saveState(stateKeyBundle(key), now, sha)
                return
            }
        }
        val row = client.query("SELECT updated_at, sha, data FROM bundles WHERE k = ?", listOf(key))
            .results.firstOrNull() ?: return
        val remoteUp = row.long("updated_at") ?: 0L
        // 不能拿两台设备的墙钟比大小：对端时钟快几秒就会把本机刚改的整包设置判输。
        // 只看云端是否已经走在本机基线之前：真有新版本才采纳云端。
        if (remoteUp > base) {
            // 采纳云端时必须走 ApplyGate，否则本地写钩会把刚应用的变更再次入队造成推送回环
            SyncApplyGate.applyingRemote = true
            try {
                if (key.startsWith(BUNDLE_SCREEN_TIME_PREFIX)) {
                    // screen_time 专用应用逻辑（本机行跳过，避免云端覆盖本地采集）
                    applyRemoteScreenTimeBundle(key, row.string("data") ?: return, remoteUp, row.string("sha") ?: "")
                } else {
                    applyRemoteBundle(key, row.string("data") ?: return, remoteUp, row.string("sha") ?: "")
                }
            } finally {
                SyncApplyGate.applyingRemote = false
            }
            // 云端赢了不等于本地改动该死：mergeRemote 已做逐项 LWW，
            // 合并结果可能与云端不同，重新入队把合并后的真相推上去。
            if (key == BUNDLE_SETTINGS || key == BUNDLE_SETTINGS_DISPLAY || key.startsWith(BUNDLE_SCREEN_TIME_PREFIX)) {
                SyncBundleEnqueuer.enqueue(key)
            }
        } else {
            // 云端没比基线新，却没命中乐观锁（常见于对端时钟回拨或初始化竞争）：
            // 用严格递增的版本号强推，避免写入一个比云端还小的 updated_at 导致下次又被判输。
            val bumped = maxOf(now, remoteUp + 1)
            client.query(
                "UPDATE bundles SET updated_at = ?, deleted = 0, sha = ?, data = ? WHERE k = ?",
                listOf(bumped, sha, payload, key)
            )
            saveState(stateKeyBundle(key), bumped, sha)
        }
    }

    private suspend fun exportMemory(): String {
        val items = database.memoryDao().getAllMemories()
            .map { SyncMemoryItem(id = it.id, assistantId = it.assistantId, content = it.content) }
        return json.encodeToString(items)
    }

    /**
     * 跨设备屏幕时间（方案 2026-08-09）：导出本机最近 [CLOUD_RETENTION_DAYS] 天日聚合为 bundle payload。
     * 不携带 updated_at：内容没变 → sha 不变 → pushBundle 直接跳过，空闲设备零流量。
     */
    private suspend fun exportScreenTime(deviceId: String): String {
        val items = database.screenTimeDayDao().getByDevice(deviceId)
            .take(CLOUD_RETENTION_DAYS)
            .map { row ->
                SyncScreenTimeDayItem(
                    deviceId = row.deviceId,
                    deviceLabel = row.deviceLabel,
                    timezone = ZoneId.systemDefault().id,
                    date = row.date,
                    totalMs = row.totalMs,
                    apps = runCatching { json.decodeFromString<List<SyncScreenTimeAppItem>>(row.appsJson) }
                        .getOrDefault(emptyList()),
                )
            }
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
        val client = requireClient() ?: return
        ensureSchema(client)
        pendingRepush.clear()
        SyncApplyGate.applyingRemote = true
        try {
            pullConversations(client)
            pullBundleKey(client, BUNDLE_SETTINGS)
            pullBundleKey(client, BUNDLE_SETTINGS_DISPLAY)
            pullBundleKey(client, BUNDLE_MEMORY)
            pullBundleKey(client, BUNDLE_MEMORY_LINKS)
            // 注册表必须先于节点 / 边落地，避免远端多图短暂进入孤儿态。
            pullBundleKey(client, BUNDLE_MEMORY_GRAPHS)
            // 先应用边但暂不清理，再应用节点并在节点完成后校验边，避免边 bundle 先到时丢失。
            pullBundleKey(client, BUNDLE_MEMORY_GRAPH_LINKS)
            pullBundleKey(client, BUNDLE_MEMORY_GRAPH_NODES)
            pullBundleKey(client, BUNDLE_FAVORITES)
            pullBundleKey(client, BUNDLE_FOLDERS)
            pullBundleKey(client, BUNDLE_GENMEDIA)
            pullBundleKey(client, BUNDLE_MANAGED_FILES)
            pullBundleKey(client, BUNDLE_ASSET_LABELS)
            pullBundleKey(client, BUNDLE_SUBAGENT_TEMPLATES)
            pullBundleKey(client, BUNDLE_SKILLS)
            pullBundleKey(client, BUNDLE_SCHEDULED_NOTIFICATIONS)
            // 跨设备屏幕时间：前缀拉取所有设备的 screen_time:* bundle
            pullScreenTimeBundles(client)
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

    private suspend fun pullConversations(client: D1Client) {
        // 增量 manifest：只拉比本机水位新的行。以前是 SELECT 全表，
        // 会话一多每次同步都在白传几百行 manifest。
        val watermark = readStateUpdatedAt(STATE_CONV_WATERMARK) ?: 0L
        val rows = client.query(
            "SELECT id, updated_at, sha, deleted FROM conversations WHERE updated_at > ? ORDER BY updated_at ASC",
            listOf(watermark)
        ).results
        if (rows.isEmpty()) return

        var maxUpdatedAt = watermark
        val needData = mutableListOf<Triple<String, Long, String>>()

        for (row in rows) {
            val id = row.string("id") ?: continue
            val updatedAt = row.long("updated_at") ?: continue
            val sha = row.string("sha") ?: ""
            val deleted = (row.long("deleted") ?: 0L) == 1L
            if (updatedAt > maxUpdatedAt) maxUpdatedAt = updatedAt

            val uuid = runCatching { Uuid.parse(id) }.getOrElse { continue }
            if (deleted) {
                // Tombstone must win before sha short-circuiting; older code left sha unchanged
                // on delete, which made peers skip deletion forever.
                if (conversationRepository.existsConversationById(uuid)) {
                    conversationRepository.getConversationById(uuid)
                        ?.let { conversationRepository.deleteConversation(it) }
                }
                clearLocalNodeState(id)
                saveState(stateKeyConv(id), updatedAt, sha)
                continue
            }

            val state = readState(stateKeyConv(id))
            if (state != null && state.sha == sha) {
                // data 未变：本会话若已是 node 模式（本端曾推送过 node），
                // 对端可能只更新了 conv_nodes（node-only 通道）→ 走 node 增量读取
                if (readLocalNodeState(id) != null) {
                    pullNodeIncremental(client, id, updatedAt, sha)
                }
                continue
            }
            needData += Triple(id, updatedAt, sha)
        }

        // 批量取 data：旧实现是每个会话一次 POST（N+1），拉 10 个会话 = 11 次串行往返。
        needData.chunked(CONV_DATA_FETCH_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val dataRows = client.query(
                "SELECT id, data FROM conversations WHERE id IN ($placeholders)",
                chunk.map { it.first }
            ).results
            val dataById = dataRows.mapNotNull { r ->
                val id = r.string("id") ?: return@mapNotNull null
                val data = r.string("data") ?: return@mapNotNull null
                id to data
            }.toMap()
            chunk.forEach { (id, updatedAt, sha) ->
                val data = dataById[id] ?: return@forEach
                // node-only 对端的行 data 为空：本端若对该会话有 node 基准，改走 node 通道读取
                if (data.isBlank() && readLocalNodeState(id) != null) {
                    pullNodeIncremental(client, id, updatedAt, sha)
                    return@forEach
                }
                applyRemoteConversation(id, data, updatedAt, sha)
            }
        }

        // 水位只在本轮全部应用完毕后推进；中途抛异常则下次重拉，宁可重复不可丢。
        if (maxUpdatedAt > watermark) {
            saveState(STATE_CONV_WATERMARK, maxUpdatedAt, "")
        }
    }

    private suspend fun applyRemoteConversation(refKey: String, data: String, updatedAt: Long, sha: String) {
        val conv = runCatching { json.decodeFromString<Conversation>(data) }.getOrElse {
            Log.e(TAG, "applyRemoteConversation: decode failed for $refKey", it)
            return
        }
        val hydratedConv = ConversationPartsOffloader.hydrateIfNeeded(conv, r2MediaStore)
        val localWorkspaceCwd = conversationRepository.getConversationById(hydratedConv.id)?.workspaceCwd
        val deviceLocalConv = hydratedConv.copy(workspaceCwd = localWorkspaceCwd)
        if (conversationRepository.existsConversationById(deviceLocalConv.id)) {
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
    private suspend fun pullNodeIncremental(client: D1Client, convId: String, updatedAt: Long, sha: String) {
        val uuid = runCatching { Uuid.parse(convId) }.getOrElse { return }
        val rows = client.query(
            "SELECT node_id, idx, select_index, updated_at, deleted, sha FROM conv_nodes WHERE conv_id = ?",
            listOf(convId)
        ).results
        if (rows.isEmpty()) return

        data class CloudNode(val nodeId: String, val idx: Int, val sha: String, val deleted: Boolean)

        val cloud = rows.mapNotNull { row ->
            val nodeId = row.string("node_id") ?: return@mapNotNull null
            val idx = row.long("idx")?.toInt() ?: return@mapNotNull null
            CloudNode(nodeId, idx, row.string("sha") ?: "", (row.long("deleted") ?: 0L) == 1L)
        }
        val alive = cloud.filter { !it.deleted }
        if (alive.isEmpty()) return

        val localState = readLocalNodeState(convId) ?: return
        val need = alive.filter { localState[it.nodeId] != it.sha }
        if (need.isEmpty()) return

        // 批量取需要更新的 node data
        val dataById = need.chunked(CONV_DATA_FETCH_CHUNK).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            client.query(
                "SELECT node_id, data FROM conv_nodes WHERE conv_id = ? AND node_id IN ($placeholders)",
                listOf(convId) + chunk.map { it.nodeId }
            ).results.mapNotNull { r ->
                val id = r.string("node_id") ?: return@mapNotNull null
                val d = r.string("data") ?: return@mapNotNull null
                id to d
            }
        }.toMap()
        if (dataById.isEmpty()) return

        val cloudNodes = alive.sortedBy { it.idx }.mapNotNull { cn ->
            val d = dataById[cn.nodeId] ?: return@mapNotNull null
            runCatching { json.decodeFromString<MessageNode>(d) }.getOrNull()
        }
        if (cloudNodes.isEmpty()) return

        val localConv = conversationRepository.getConversationById(uuid) ?: return
        val cloudIds = alive.map { it.nodeId }.toSet()
        // 本地有而云端缺失的节点（对端旧版本只写过 data）→ 保留并回推
        val localExtra = localConv.messageNodes.filter { it.id.toString() !in cloudIds }
        if (localExtra.isNotEmpty()) {
            Log.w(TAG, "pullNodeIncremental: $convId has ${localExtra.size} local-only nodes, will repush")
            pendingRepushConversations += convId
        }

        val merged = localConv.copy(messageNodes = cloudNodes + localExtra)
        // 云端 node 可能含 R2 引用（对端 offload 过大 part），重建后必须 hydrate
        val hydrated = ConversationPartsOffloader.hydrateIfNeeded(merged, r2MediaStore)
        conversationRepository.updateConversation(hydrated)
        // 基准只推进到「已取到 data」的节点，未取到的保持旧 sha，下一轮会重试
        val synced = alive.filter { it.nodeId in dataById }.associate { it.nodeId to it.sha }
        saveLocalNodeState(convId, synced)
        saveState(stateKeyConv(convId), updatedAt, sha)
    }

    private suspend fun pullBundleKey(client: D1Client, key: String) {
        if (key == BUNDLE_SETTINGS_DISPLAY && !SyncLocalPrefs.isDisplaySyncEnabled(context)) return
        val row = client.query("SELECT updated_at, sha, data FROM bundles WHERE k = ?", listOf(key))
            .results.firstOrNull() ?: return
        val sha = row.string("sha") ?: ""
        val state = readState(stateKeyBundle(key))
        if (state != null && state.sha == sha) return
        val updatedAt = row.long("updated_at") ?: return
        applyRemoteBundle(key, row.string("data") ?: return, updatedAt, sha)
    }

    private suspend fun applyRemoteBundle(key: String, data: String, updatedAt: Long, sha: String) {
        when (key) {
            BUNDLE_SETTINGS -> {
                val remote = runCatching { json.decodeFromString<Settings>(data) }.getOrElse {
                    Log.e(TAG, "applyRemoteBundle: settings decode failed", it)
                    return
                }
                val local = settingsStore.settingsFlow.value
                val merged = SyncSettingsFilter.mergeRemote(local, remote)
                settingsStore.update(merged)
                // 合并结果与云端 payload 不一致（本地有更新的渠道/助手赢了 LWW）时，
                // 必须把合并后的真相回推，否则本地改动永远到不了对端。
                if (SyncSettingsFilter.forUpload(merged) != SyncSettingsFilter.forUpload(remote)) {
                    pendingRepush += BUNDLE_SETTINGS
                }
            }

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

    /**
     * 跨设备屏幕时间（方案 2026-08-09）：前缀拉取所有设备的 screen_time:* bundle 增量。
     */
    private suspend fun pullScreenTimeBundles(client: D1Client) {
        val rows = client.query("SELECT k, updated_at, sha, data FROM bundles WHERE k LIKE 'screen_time:%'").results
        rows.forEach { row ->
            val key = row.string("k") ?: return@forEach
            val sha = row.string("sha") ?: ""
            val state = readState(stateKeyBundle(key))
            if (state != null && state.sha == sha) return@forEach
            val updatedAt = row.long("updated_at") ?: return@forEach
            val data = row.string("data") ?: return@forEach
            applyRemoteScreenTimeBundle(key, data, updatedAt, sha)
        }
    }

    /**
     * 应用对端设备的屏幕时间 bundle：整组替换该 device_id 的本地行。
     * 本机行永远以本地采集为准，云端回读不覆盖（防回环）。
     */
    private suspend fun applyRemoteScreenTimeBundle(key: String, data: String, updatedAt: Long, sha: String) {
        val deviceId = key.removePrefix(BUNDLE_SCREEN_TIME_PREFIX)
        if (deviceId == SyncLocalPrefs.deviceId(context)) {
            // 自己的数据以本地采集为准；只推进记账避免重复拉取
            saveState(stateKeyBundle(key), updatedAt, sha)
            return
        }
        val items = runCatching { json.decodeFromString<List<SyncScreenTimeDayItem>>(data) }.getOrElse { return }
        database.withTransaction {
            val dao = database.screenTimeDayDao()
            dao.deleteByDevice(deviceId)
            items.forEach { item ->
                dao.upsert(
                    ScreenTimeDayEntity(
                        deviceId = item.deviceId,
                        deviceLabel = item.deviceLabel,
                        date = item.date,
                        totalMs = item.totalMs,
                        appsJson = json.encodeToString(item.apps),
                        updatedAt = updatedAt,
                    )
                )
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
            BUNDLE_SCHEDULED_NOTIFICATIONS,
            BUNDLE_SCREEN_TIME_PREFIX + SyncLocalPrefs.deviceId(context),
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

    private fun stateKeyConv(id: String) = "conv:$id"
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
        return D1Client(cfg, httpClient)
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
