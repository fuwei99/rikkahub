package me.rerere.rikkahub.data.sync.core

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
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationItem
import me.rerere.rikkahub.data.ai.tools.local.ScheduledNotificationManager
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.d1.D1Client
import me.rerere.rikkahub.data.sync.d1.D1Schema
import me.rerere.rikkahub.data.sync.r2.R2MediaStore
import me.rerere.rikkahub.data.sync.r2.R2Ref
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "SyncEngine"

/** bundles 表中的持久 key */
const val BUNDLE_SETTINGS = "settings"
const val BUNDLE_SETTINGS_DISPLAY = "settings.display"
const val BUNDLE_MEMORY = "memory"
const val BUNDLE_FAVORITES = "favorites"
const val BUNDLE_FOLDERS = "folders"
const val BUNDLE_GENMEDIA = "genmedia"
const val BUNDLE_MANAGED_FILES = "managed_files"
const val BUNDLE_SUBAGENT_TEMPLATES = "subagent_templates"
const val BUNDLE_SKILLS = "skills"
const val BUNDLE_SCHEDULED_NOTIFICATIONS = "scheduled_notifications"

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
    val folder: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val r2Key: String? = null,
    val r2Acct: String? = null,
)

@Serializable
private data class SyncMemoryItem(
    val assistantId: String,
    val content: String,
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
) {
    private val mutex = Mutex()
    private var schemaEnsured = false

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
        if (now - circuitBreakerOpenTime > 3600_000L) {
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
        if (consecutiveFailures >= 10) {
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
    suspend fun testConnection() = mutex.withLock {
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

    /** 进程前台：推积压 + 拉差异 */
    suspend fun onForeground() = syncCycle()

    /** 进程退后台：尽快推积压，拉取交给 Worker */
    suspend fun onBackground() {
        if (!isConfigured() || checkCircuitBreaker()) return
        mutex.withLock {
            runCatching { flushOutbox() }
                .onFailure { Log.e(TAG, "onBackground flush failed", it) }
        }
    }

    /** WorkManager 路径 */
    suspend fun syncOnce() = syncCycle()

    suspend fun syncCycle(force: Boolean = false) {
        if (!isConfigured()) {
            if (force) throw IllegalStateException("Cloud sync is disabled or D1 config is incomplete")
            return
        }
        if (force) resetCircuitBreaker()
        if (checkCircuitBreaker()) {
            Log.w(TAG, "syncCycle skipped: circuit breaker is OPEN")
            if (force) throw IllegalStateException("Cloud sync is paused after repeated errors; retry later or test the connection")
            return
        }
        mutex.withLock {
            runCatching {
                flushOutbox(reportQuarantined = force)
                pullAll()
            }.onSuccess {
                recordSuccess()
            }.onFailure {
                recordFailure()
                Log.e(TAG, "syncCycle failed", it)
                if (force) throw it
            }
        }
    }

    // ---------------- Push ----------------

    private suspend fun flushOutbox(reportQuarantined: Boolean = false) {
        val client = requireClient() ?: return
        ensureSchema(client)
        val outbox = database.syncOutboxDao()
        val failures = mutableListOf<String>()
        while (true) {
            val pending = outbox.pending(limit = 50)
            if (pending.isEmpty()) break
            pending.forEach { item ->
                runCatching { processOutboxItem(client, item) }
                    .onSuccess { outbox.deleteByIds(listOf(item.id)) }
                    .onFailure { e ->
                        val msg = (e.message ?: e.toString()).take(200)
                        outbox.markFailed(item.id, msg)
                        failures += "${item.kind}/${item.refKey}: $msg"
                        Log.e(TAG, "flushOutbox: ${item.kind}/${item.refKey} failed", e)
                    }
            }
            if (failures.isNotEmpty()) break
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
        val slimConv = ConversationPartsOffloader.offloadIfNeeded(conv, r2MediaStore)
        val data = json.encodeToString(slimConv)
        val sha = sha256Hex(data)
        val updatedAt = conv.updateAt.toEpochMilli()
        val base = readStateUpdatedAt(stateKeyConv(refKey)) ?: 0L

        // P2 锁 final check：云端正被其他设备活锁持有 → 绝不覆盖；本地内容孤儿副本化保留
        if (isForeignLocked(client, refKey)) {
            orphanLocalCopy(conv)
            // Do not advance sync_state for the original conversation: this local write was
            // intentionally not pushed, and the original key must still pull the remote winner.
            return
        }

        val updated = client.query(
            "UPDATE conversations SET title = ?, updated_at = ?, deleted = 0, sha = ?, data = ? WHERE id = ? AND updated_at = ?",
            listOf(conv.title, updatedAt, sha, data, refKey, base)
        )
        if (updated.changes > 0) {
            saveState(stateKeyConv(refKey), updatedAt, sha)
            return
        }

        if (base == 0L) {
            val inserted = client.query(
                "INSERT OR IGNORE INTO conversations(id, title, updated_at, deleted, sha, data) VALUES(?,?,?,0,?,?)",
                listOf(refKey, conv.title, updatedAt, sha, data)
            )
            if (inserted.changes > 0) {
                saveState(stateKeyConv(refKey), updatedAt, sha)
                return
            }
        }

        resolveConversationConflict(client, refKey, conv, data, sha, updatedAt, base)
    }

    private suspend fun resolveConversationConflict(
        client: D1Client,
        refKey: String,
        local: Conversation,
        data: String,
        sha: String,
        updatedAt: Long,
        base: Long,
    ) {
        val row = client.query(
            "SELECT updated_at, sha, data FROM conversations WHERE id = ?",
            listOf(refKey)
        ).results.firstOrNull()

        if (row == null) {
            client.query(
                "INSERT OR REPLACE INTO conversations(id, title, updated_at, deleted, sha, data) VALUES(?,?,?,0,?,?)",
                listOf(refKey, local.title, updatedAt, sha, data)
            )
            saveState(stateKeyConv(refKey), updatedAt, sha)
            return
        }

        val remoteUpdatedAt = row.long("updated_at") ?: 0L
        if (remoteUpdatedAt >= updatedAt) {
            // 云端较新：采纳云端。若本机无基线却撞到同 id 远端行，先把本地内容孤儿化，
            // 避免“刚发的消息”被云端同 id 会话直接吞掉。
            if (base == 0L) orphanLocalCopy(local)
            val remoteData = row.string("data")
            if (remoteData != null) {
                SyncApplyGate.applyingRemote = true
                try {
                    applyRemoteConversation(refKey, remoteData, remoteUpdatedAt, row.string("sha") ?: "")
                } finally {
                    SyncApplyGate.applyingRemote = false
                }
            }
        } else {
            // 本地较新：放弃基线强推
            client.query(
                "UPDATE conversations SET title = ?, updated_at = ?, deleted = 0, sha = ?, data = ? WHERE id = ?",
                listOf(local.title, updatedAt, sha, data, refKey)
            )
            saveState(stateKeyConv(refKey), updatedAt, sha)
        }
    }

    /** 云端是否存在"他机持有的未过期锁"（过期/本机/无锁均可安全推） */
    private suspend fun isForeignLocked(client: D1Client, refKey: String): Boolean {
        val row = runCatching {
            client.query("SELECT device_id, expires_at FROM locks WHERE conv_id = ?", listOf(refKey))
                .results.firstOrNull()
        }.getOrNull() ?: return false
        val expiresAt = row.long("expires_at") ?: return false
        if (expiresAt < System.currentTimeMillis()) return false
        val deviceId = row.string("device_id") ?: return false
        return deviceId != SyncLocalPrefs.deviceId(context)
    }

    /** 被偷锁后的本地孤儿副本：换发新 id 存为新会话，原 refKey 让位云端版本 */
    private suspend fun orphanLocalCopy(conv: Conversation) {
        runCatching {
            conversationRepository.insertConversation(
                conv.copy(
                    id = Uuid.random(),
                    title = "${conv.title} ${context.getString(R.string.sync_orphan_suffix)}",
                )
            )
            Log.w(TAG, "pushConversation: ${conv.id} orphaned locally (foreign lock active)")
        }.onFailure { Log.e(TAG, "orphanLocalCopy failed for ${conv.id}", it) }
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

            BUNDLE_FAVORITES -> exportFavorites()

            BUNDLE_FOLDERS -> exportFolders()

            BUNDLE_GENMEDIA -> exportGenMedia()

            BUNDLE_MANAGED_FILES -> exportManagedFiles()

            BUNDLE_SUBAGENT_TEMPLATES -> exportSubagentTemplates()

            BUNDLE_SKILLS -> exportSkills()

            BUNDLE_SCHEDULED_NOTIFICATIONS -> json.encodeToString(ScheduledNotificationManager.getItems(context))

            else -> return
        }
        val sha = sha256Hex(payload)
        val now = System.currentTimeMillis()
        val base = readStateUpdatedAt(stateKeyBundle(key)) ?: 0L

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
        if (remoteUp >= now) {
            // 采纳云端时必须走 ApplyGate，否则本地写钩会把刚应用的变更再次入队造成推送回环
            SyncApplyGate.applyingRemote = true
            try {
                applyRemoteBundle(key, row.string("data") ?: return, remoteUp, row.string("sha") ?: "")
            } finally {
                SyncApplyGate.applyingRemote = false
            }
        } else {
            client.query(
                "UPDATE bundles SET updated_at = ?, deleted = 0, sha = ?, data = ? WHERE k = ?",
                listOf(now, sha, payload, key)
            )
            saveState(stateKeyBundle(key), now, sha)
        }
    }

    private suspend fun exportMemory(): String {
        val items = database.memoryDao().getAllMemories()
            .map { SyncMemoryItem(it.assistantId, it.content) }
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
            )
        }
        return json.encodeToString(items)
    }

    private suspend fun exportManagedFiles(): String {
        val items = database.managedFileDao().getAllFiles().map {
            SyncManagedFileItem(
                folder = it.folder,
                relativePath = it.relativePath,
                displayName = it.displayName,
                mimeType = it.mimeType,
                sizeBytes = it.sizeBytes,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                r2Key = it.r2Key,
                r2Acct = it.r2Acct,
            )
        }
        return json.encodeToString(items)
    }

    private fun exportSubagentTemplates(): String {
        val dir = File(context.filesDir, "subagents")
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
        val dir = File(context.filesDir, "subagents")
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
        val dir = File(context.filesDir, FileFolders.SKILLS)
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
        val root = File(context.filesDir, FileFolders.SKILLS).canonicalFile
        root.deleteRecursively()
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
        SyncApplyGate.applyingRemote = true
        try {
            pullConversations(client)
            pullBundleKey(client, BUNDLE_SETTINGS)
            pullBundleKey(client, BUNDLE_SETTINGS_DISPLAY)
            pullBundleKey(client, BUNDLE_MEMORY)
            pullBundleKey(client, BUNDLE_FAVORITES)
            pullBundleKey(client, BUNDLE_FOLDERS)
            pullBundleKey(client, BUNDLE_GENMEDIA)
            pullBundleKey(client, BUNDLE_MANAGED_FILES)
            pullBundleKey(client, BUNDLE_SUBAGENT_TEMPLATES)
            pullBundleKey(client, BUNDLE_SKILLS)
            pullBundleKey(client, BUNDLE_SCHEDULED_NOTIFICATIONS)
        } finally {
            SyncApplyGate.applyingRemote = false
        }
    }

    private suspend fun pullConversations(client: D1Client) {
        val rows = client.query("SELECT id, updated_at, sha, deleted FROM conversations").results
        for (row in rows) {
            val id = row.string("id") ?: continue
            val updatedAt = row.long("updated_at") ?: continue
            val sha = row.string("sha") ?: ""
            val deleted = (row.long("deleted") ?: 0L) == 1L

            val uuid = runCatching { Uuid.parse(id) }.getOrElse { continue }
            if (deleted) {
                // Tombstone must win before sha short-circuiting; older code left sha unchanged
                // on delete, which made peers skip deletion forever.
                if (conversationRepository.existsConversationById(uuid)) {
                    conversationRepository.getConversationById(uuid)
                        ?.let { conversationRepository.deleteConversation(it) }
                }
                saveState(stateKeyConv(id), updatedAt, sha)
                continue
            }

            val state = readState(stateKeyConv(id))
            if (state != null && state.sha == sha) continue

            val dataRow = client.query("SELECT data FROM conversations WHERE id = ?", listOf(id))
                .results.firstOrNull()
            val data = dataRow?.string("data") ?: continue
            applyRemoteConversation(id, data, updatedAt, sha)
        }
    }

    private suspend fun applyRemoteConversation(refKey: String, data: String, updatedAt: Long, sha: String) {
        val conv = runCatching { json.decodeFromString<Conversation>(data) }.getOrElse {
            Log.e(TAG, "applyRemoteConversation: decode failed for $refKey", it)
            return
        }
        val hydratedConv = ConversationPartsOffloader.hydrateIfNeeded(conv, r2MediaStore)
        if (conversationRepository.existsConversationById(hydratedConv.id)) {
            conversationRepository.updateConversation(hydratedConv)
        } else {
            conversationRepository.insertConversation(hydratedConv)
        }
        saveState(stateKeyConv(refKey), updatedAt, sha)
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
                settingsStore.update(SyncSettingsFilter.mergeRemote(local, remote))
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
                            MemoryEntity(assistantId = it.assistantId, content = it.content)
                        )
                    }
                }
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
                    dao.deleteAll()
                    items.forEach { item ->
                        dao.insert(
                            me.rerere.rikkahub.data.db.entity.ManagedFileEntity(
                                folder = item.folder,
                                relativePath = item.relativePath,
                                displayName = item.displayName,
                                mimeType = item.mimeType,
                                sizeBytes = item.sizeBytes,
                                createdAt = item.createdAt,
                                updatedAt = item.updatedAt,
                                r2Key = item.r2Key,
                                r2Acct = item.r2Acct,
                            )
                        )
                    }
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

    // ---------------- Seeding（首次装机全量上推） ----------------

    /** 把本地全部会话/设置/记忆入队上推；返回会话数量 */
    suspend fun seedLocalData(): Int {
        val outbox = database.syncOutboxDao()
        val now = System.currentTimeMillis()
        val ids = conversationRepository.getAllConversationIds()
        ids.forEach {
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_CONVERSATION,
                    refKey = it,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = now,
                )
            )
        }
        listOf(
            BUNDLE_SETTINGS,
            BUNDLE_SETTINGS_DISPLAY,
            BUNDLE_MEMORY,
            BUNDLE_FAVORITES,
            BUNDLE_FOLDERS,
            BUNDLE_GENMEDIA,
            BUNDLE_MANAGED_FILES,
            BUNDLE_SUBAGENT_TEMPLATES,
            BUNDLE_SKILLS,
            BUNDLE_SCHEDULED_NOTIFICATIONS,
        ).forEach {
            outbox.insert(
                SyncOutboxEntity(
                    kind = SyncOutboxEntity.KIND_BUNDLE,
                    refKey = it,
                    op = SyncOutboxEntity.OP_UPSERT,
                    createdAt = now,
                )
            )
        }
        return ids.size
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
