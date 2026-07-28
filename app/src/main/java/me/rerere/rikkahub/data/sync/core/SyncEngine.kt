package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.SyncOutboxEntity
import me.rerere.rikkahub.data.db.entity.SyncStateEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.d1.D1Client
import me.rerere.rikkahub.data.sync.d1.D1Schema
import java.security.MessageDigest
import kotlin.uuid.Uuid

private const val TAG = "SyncEngine"

/** bundles 表中的持久 key */
const val BUNDLE_SETTINGS = "settings"
const val BUNDLE_SETTINGS_DISPLAY = "settings.display"
const val BUNDLE_MEMORY = "memory"

@Serializable
private data class SyncMemoryItem(
    val assistantId: String,
    val content: String,
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
) {
    private val mutex = Mutex()
    private var schemaEnsured = false

    fun isConfigured(): Boolean = settingsStore.settingsFlow.value.d1Config.isConfigured

    suspend fun testConnection(): Boolean = mutex.withLock {
        val client = requireClient() ?: return@withLock false
        runCatching { D1Schema.ensure(client) }
            .onSuccess {
                schemaEnsured = true
                Log.i(TAG, "testConnection: ok")
            }
            .onFailure { Log.e(TAG, "testConnection: failed", it) }
            .isSuccess
    }

    /** 进程前台：推积压 + 拉差异 */
    suspend fun onForeground() = syncCycle()

    /** 进程退后台：尽快推积压，拉取交给 Worker */
    suspend fun onBackground() {
        if (!isConfigured()) return
        mutex.withLock {
            runCatching { flushOutbox() }
                .onFailure { Log.e(TAG, "onBackground flush failed", it) }
        }
    }

    /** WorkManager 路径 */
    suspend fun syncOnce() = syncCycle()

    private suspend fun syncCycle() {
        if (!isConfigured()) return
        mutex.withLock {
            runCatching {
                flushOutbox()
                pullAll()
            }.onFailure { Log.e(TAG, "syncCycle failed", it) }
        }
    }

    // ---------------- Push ----------------

    private suspend fun flushOutbox() {
        val client = requireClient() ?: return
        ensureSchema(client)
        val outbox = database.syncOutboxDao()
        outbox.pending(limit = 50).forEach { item ->
            runCatching { processOutboxItem(client, item) }
                .onSuccess { outbox.deleteByIds(listOf(item.id)) }
                .onFailure { e ->
                    outbox.markFailed(item.id, (e.message ?: "unknown").take(200))
                    Log.e(TAG, "flushOutbox: ${item.kind}/${item.refKey} failed", e)
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
        val data = json.encodeToString(conv)
        val sha = sha256Hex(data)
        val updatedAt = conv.updateAt.toEpochMilli()
        val base = readStateUpdatedAt(stateKeyConv(refKey)) ?: 0L

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

        resolveConversationConflict(client, refKey, conv, data, sha, updatedAt)
    }

    private suspend fun resolveConversationConflict(
        client: D1Client,
        refKey: String,
        local: Conversation,
        data: String,
        sha: String,
        updatedAt: Long,
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
            // 云端较新：采纳云端
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

    private suspend fun tombstoneRemoteConversation(client: D1Client, refKey: String) {
        val now = System.currentTimeMillis()
        client.query("UPDATE conversations SET deleted = 1, updated_at = ? WHERE id = ?", listOf(now, refKey))
        client.query(
            "INSERT OR IGNORE INTO conversations(id, title, updated_at, deleted, sha, data) VALUES(?,?,?,1,'','')",
            listOf(refKey, "", now)
        )
        saveState(stateKeyConv(refKey), now, "")
    }

    private suspend fun pushBundle(client: D1Client, key: String) {
        val payload = when (key) {
            BUNDLE_SETTINGS -> json.encodeToString(
                SyncSettingsFilter.forUpload(settingsStore.settingsFlow.value)
            )

            BUNDLE_SETTINGS_DISPLAY -> {
                if (!SyncLocalPrefs.isDisplaySyncEnabled(context)) return
                json.encodeToString(settingsStore.settingsFlow.value.displaySetting)
            }

            BUNDLE_MEMORY -> exportMemory()

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
            applyRemoteBundle(key, row.string("data") ?: return, remoteUp, row.string("sha") ?: "")
        } else {
            client.query(
                "UPDATE bundles SET updated_at = ?, deleted = 0, sha = ?, data = ? WHERE k = ?",
                listOf(now, sha, payload, key)
            )
            saveState(stateKeyBundle(key), now, sha)
        }
    }

    private fun exportMemory(): String {
        val items = database.memoryDao().getAllMemories()
            .map { SyncMemoryItem(it.assistantId, it.content) }
        return json.encodeToString(items)
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

            val state = readState(stateKeyConv(id))
            if (state != null && state.sha == sha) continue

            val uuid = runCatching { Uuid.parse(id) }.getOrElse { continue }
            if (deleted) {
                if (conversationRepository.existsConversationById(uuid)) {
                    conversationRepository.getConversationById(uuid)
                        ?.let { conversationRepository.deleteConversation(it) }
                }
                saveState(stateKeyConv(id), updatedAt, sha)
                continue
            }

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
        if (conversationRepository.existsConversationById(conv.id)) {
            conversationRepository.updateConversation(conv)
        } else {
            conversationRepository.insertConversation(conv)
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
                settingsStore.update(local.copy(displaySetting = display))
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
        }
        saveState(stateKeyBundle(key), updatedAt, sha)
    }

    // ---------------- Seeding（首次装机全量上推） ----------------

    /** 把本地全部会话/设置/记忆入队上推；返回会话数量 */
    suspend fun seedLocalData(): Int {
        val outbox = database.syncOutboxDao()
        val now = System.currentTimeMillis()
        val ids = mutableListOf<String>()
        database.query("SELECT id FROM conversation", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
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
        listOf(BUNDLE_SETTINGS, BUNDLE_SETTINGS_DISPLAY, BUNDLE_MEMORY).forEach {
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
                value = "{\"updated_at\":$updatedAt,\"sha\":\"$sha\"}",
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    // ---------------- 工具 ----------------

    private fun requireClient(): D1Client? {
        val cfg = settingsStore.settingsFlow.value.d1Config
        if (!cfg.isConfigured) return null
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

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
