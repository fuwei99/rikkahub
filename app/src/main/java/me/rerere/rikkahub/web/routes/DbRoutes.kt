package me.rerere.rikkahub.web.routes

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.files.AppPaths
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.io.File
import java.io.FileOutputStream

/**
 * 数据库桥（2026-11-14）。
 *
 * 目的：把「同步 / 加载 / 细颗粒改库」从 App 内部那坨 3731 行的 SyncEngine 里剥出来，
 * 交给 workspace 的 schedule process 脚本驱动。脚本坏了改脚本就行，不用重新编译 App。
 *
 * ## 为什么写库必须走 Room 自己的连接
 *
 * `AppDatabase.openHelper.writableDatabase` 上挂着 Room 的 InvalidationTracker 临时触发器。
 * 只要所有写都经这条连接，`ConversationRepository` 的 Flow / PagingSource 会自动刷新，
 * 侧边栏、`/api/events` 的 `conversation_list_invalidate` 也会跟着推 —— 不需要额外的
 * 「通知 App 重新加载」接口。若用独立的 `SQLiteDatabase.openDatabase()` 写同一文件，
 * Room 观测不到，就会出现「库里有了、界面看不见」。
 *
 * ## 鉴权
 *
 * 与 `/api/tools` `/api/mail` `/api/shell` 共用设备桥独立 Bearer（`shellBridgeToken`），
 * 不走 web JWT。matepad 走 CF 隧道 / 自己走 localhost 都用同一个 token。
 *
 * ## 表清单
 *
 * | 方法 | 路径 | 作用 |
 * |---|---|---|
 * | GET  | `/api/db/health` | 探活 |
 * | GET  | `/api/db/status` | 设备 / 文件 / WAL / outbox / sync_state 摘要 |
 * | GET  | `/api/db/tables` | 表名 + 行数 + 建表 SQL |
 * | POST | `/api/db/sql` | 任意只读 SQL，返回列 + 行 |
 * | POST | `/api/db/exec` | 任意写 SQL（可多条、可事务） |
 * | GET  | `/api/db/manifest?since=` | 增量清单（脚本 diff 用） |
 * | GET  | `/api/db/export?tables=&since=` | 按表导出行 JSON |
 * | POST | `/api/db/import` | 按表 INSERT OR REPLACE，立即生效 |
 * | GET  | `/api/db/conversations?since=` | 会话清单 |
 * | GET  | `/api/db/conversations/{id}` | 会话 + 全部 message_node |
 * | POST | `/api/db/conversations` | upsert 会话 + 节点（跨设备同步主力） |
 * | DELETE | `/api/db/conversations/{id}` | 删会话（级联） |
 * | POST | `/api/db/checkpoint` | `wal_checkpoint(TRUNCATE)` |
 * | GET  | `/api/db/snapshot` | `VACUUM INTO` 干净单文件 DB 下载（无 -wal/-shm） |
 * | POST | `/api/db/restore` | 上传 DB → 校验 + 暂存（不自动覆盖） |
 * | POST | `/api/db/restore/commit` | 覆盖 + 重启 App |
 * | POST | `/api/db/settings/export` | 内存 Settings → setting-json 分片文件 |
 * | POST | `/api/db/settings/import` | setting-json 分片文件 → 内存并扩散 |
 * | POST | `/api/db/restart` | 重启 App |
 */
class DbBridge(
    val context: Context,
    private val database: AppDatabase,
    private val settingsExchange: SettingsJsonExchange,
) {
    private fun db(): SupportSQLiteDatabase = database.openHelper.writableDatabase

    // ---------------- 元信息 ----------------

    fun tableNames(): List<String> {
        val out = mutableListOf<String>()
        db().query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name"
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    private fun columnsOf(table: String): List<String> {
        val out = mutableListOf<String>()
        db().query("PRAGMA table_info(\"${table.escapeIdent()}\")").use { c ->
            while (c.moveToNext()) out += c.getString(1)
        }
        return out
    }

    private fun countOf(table: String): Long =
        db().query("SELECT COUNT(*) FROM \"${table.escapeIdent()}\"").use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    fun status(): JsonObject {
        val dbFile = AppPaths.databaseFile(context)
        val tables = tableNames()
        return buildJsonObject {
            put("ok", true)
            put("package", context.packageName)
            put("database_path", dbFile.absolutePath)
            put("database_bytes", dbFile.length())
            put("wal_bytes", File(dbFile.path + "-wal").length())
            put("shm_bytes", File(dbFile.path + "-shm").length())
            put("journal_mode", scalar("PRAGMA journal_mode"))
            put("schema_version", scalarLong("PRAGMA user_version"))
            put("table_count", tables.size)
            put("conversation_count", countOf("ConversationEntity"))
            put("message_node_count", countOf("message_node"))
            put("sync_outbox_count", countOf("sync_outbox"))
            put("sync_state_count", countOf("sync_state"))
            put("media_upload_outbox_count", countOf("media_upload_outbox"))
        }
    }

    fun tables(): JsonObject = buildJsonObject {
        tableNames().forEach { t ->
            put(t, buildJsonObject {
                put("rows", countOf(t))
                put("columns", kotlinx.serialization.json.buildJsonArray {
                    columnsOf(t).forEach { add(JsonPrimitive(it)) }
                })
                put("sql", scalar("SELECT sql FROM sqlite_master WHERE name='${t.escapeSql()}'"))
            })
        }
    }

    // ---------------- 通用 SQL ----------------

    fun query(sql: String, maxRows: Int): SqlResponse {
        requireArg(sql.isNotBlank()) { "sql 不能为空" }
        val started = System.currentTimeMillis()
        val rows = mutableListOf<Map<String, JsonElement>>()
        var columns = emptyList<String>()
        var truncated = false
        db().query(sql).use { c ->
            columns = c.columnNames.toList()
            while (c.moveToNext()) {
                if (rows.size >= maxRows.coerceAtLeast(1)) {
                    truncated = true
                    break
                }
                rows += LinkedHashMap<String, JsonElement>().also { m ->
                    columns.forEachIndexed { i, name -> m[name] = readCell(c, i) }
                }
            }
        }
        return SqlResponse(
            columns = columns,
            rows = rows,
            rowCount = rows.size,
            truncated = truncated,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    fun exec(statements: List<String>, transactional: Boolean): ExecResponse {
        requireArg(statements.isNotEmpty()) { "没有可执行的语句" }
        val started = System.currentTimeMillis()
        val d = db()
        // changes() / last_insert_rowid() 必须在同一条连接、同一事务内、最后一条
        // execSQL 之后立刻取。等 endTransaction() 再查，SQLite 已把 changes() 归零，
        // 会永远回 0（2026-11-14 实测）。
        var changes = 0L
        var lastId = 0L
        if (transactional) d.beginTransaction()
        try {
            statements.forEach { s ->
                if (s.isNotBlank()) d.execSQL(s)
            }
            changes = scalarLong("SELECT changes()")
            lastId = scalarLong("SELECT last_insert_rowid()")
            if (transactional) d.setTransactionSuccessful()
        } finally {
            if (transactional) d.endTransaction()
        }
        return ExecResponse(
            statements = statements.size,
            changes = changes.toInt(),
            lastInsertRowId = lastId,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    // ---------------- 增量 / 导入导出 ----------------

    /** 每个表给 rowCount + maxUpdateAt（有该列时），再给会话清单和 sync_state 的 bundle 游标。 */
    fun manifest(since: Long): JsonObject = buildJsonObject {
        put("now", System.currentTimeMillis())
        put("since", since)
        put("tables", buildJsonObject {
            tableNames().forEach { t ->
                val cols = columnsOf(t)
                val maxUpdateAt = if ("update_at" in cols) {
                    scalarLong("SELECT COALESCE(MAX(update_at),0) FROM \"${t.escapeIdent()}\"")
                } else null
                put(t, buildJsonObject {
                    put("rows", countOf(t))
                    if (maxUpdateAt != null) put("max_update_at", maxUpdateAt)
                })
            }
        })
        put("conversations", rowsAsArray(
            "SELECT id, title, update_at, is_pinned, folder_id FROM ConversationEntity " +
                (if (since > 0) "WHERE update_at > $since " else "") +
                "ORDER BY update_at DESC LIMIT 20000"
        ))
        put("bundles", rowsAsArray(
            "SELECT `key`, updated_at, substr(value,1,120) AS value_head FROM sync_state " +
                "WHERE `key` LIKE 'bundle:%' ORDER BY `key`"
        ))
    }

    /** 按表导出行。有 update_at 且 since>0 时自动加增量条件。 */
    fun export(requested: List<String>, since: Long): JsonObject {
        val all = tableNames()
        val targets = if (requested.isEmpty()) all else {
            requested.forEach { requireArg(it in all) { "未知表：$it" } }
            requested
        }
        return buildJsonObject {
            targets.forEach { t ->
                val where = if (since > 0 && "update_at" in columnsOf(t)) {
                    " WHERE update_at > $since"
                } else ""
                put(t, rowsAsArray("SELECT * FROM \"${t.escapeIdent()}\"$where"))
            }
        }
    }

    /** 白名单校验列名后 INSERT OR REPLACE。走 Room 连接 → UI 自动刷新。 */
    fun importRows(payload: Map<String, List<Map<String, JsonElement>>>): Map<String, Int> {
        val d = db()
        d.beginTransaction()
        try {
            val result = importRowsLocked(d, payload)
            d.setTransactionSuccessful()
            return result
        } finally {
            d.endTransaction()
        }
    }

    /** 无事务版本：给已经开着事务的 [upsertConversation] 复用，避免嵌套事务。 */
    private fun importRowsLocked(
        d: SupportSQLiteDatabase,
        payload: Map<String, List<Map<String, JsonElement>>>,
    ): Map<String, Int> {
        val all = tableNames()
        val result = LinkedHashMap<String, Int>()
        payload.forEach { (table, rows) ->
            requireArg(table in all) { "未知表：$table" }
            val cols = columnsOf(table)
            requireArg(cols.isNotEmpty()) { "表无列：$table" }
            var n = 0
            rows.forEach { row ->
                val used = row.keys.filter { it in cols }
                requireArg(used.isNotEmpty()) { "$table 行里没有合法列" }
                val sql = "INSERT OR REPLACE INTO \"${table.escapeIdent()}\" " +
                    "(${used.joinToString(",") { "\"${it.escapeIdent()}\"" }}) " +
                    "VALUES (${used.joinToString(",") { "?" }})"
                val args = used.map { key -> row.getValue(key).toSqlValue() }.toTypedArray()
                d.execSQL(sql, args)
                n++
            }
            result[table] = n
        }
        return result
    }

    fun conversation(id: String): JsonObject {
        val conv = query(
            "SELECT * FROM ConversationEntity WHERE id='${id.escapeSql()}'", 2
        ).rows.firstOrNull() ?: throw NotFoundException("Conversation not found: $id")
        return buildJsonObject {
            put("conversation", JsonObject(conv))
            put("nodes", rowsAsArray(
                "SELECT * FROM message_node WHERE conversation_id='${id.escapeSql()}' ORDER BY node_index ASC"
            ))
        }
    }

    fun conversations(since: Long): JsonObject = buildJsonObject {
        put("conversations", rowsAsArray(
            "SELECT c.*, (SELECT COUNT(*) FROM message_node n WHERE n.conversation_id = c.id) AS node_count " +
                "FROM ConversationEntity c " +
                (if (since > 0) "WHERE c.update_at > $since " else "") +
                "ORDER BY c.update_at DESC LIMIT 20000"
        ))
    }

    fun upsertConversation(conv: Map<String, JsonElement>, nodes: List<Map<String, JsonElement>>): Int {
        val id = conv["id"]?.jsonPrimitive?.contentOrNull
            ?: throw BadRequestException("conversation.id 缺失")
        val d = db()
        d.beginTransaction()
        try {
            importRowsLocked(d, mapOf("ConversationEntity" to listOf(conv)))
            d.execSQL(
                "DELETE FROM message_node WHERE conversation_id=?",
                arrayOf<Any?>(id)
            )
            if (nodes.isNotEmpty()) {
                importRowsLocked(d, mapOf("message_node" to nodes))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return nodes.size
    }

    fun checkpoint(): JsonObject {
        db().query("PRAGMA wal_checkpoint(TRUNCATE)").use { /* drain */ }
        return buildJsonObject {
            put("ok", true)
            put("journal_mode", scalar("PRAGMA journal_mode"))
            put("wal_bytes", File(AppPaths.databaseFile(context).path + "-wal").length())
        }
    }

    // ---------------- 整库快照 / 恢复 ----------------

    /** VACUUM INTO：干净单文件、不含 -wal/-shm，可直接在另一台设备覆盖。 */
    fun snapshot(): File {
        val out = File(AppPaths.filesDir(context), "databases/rikka_hub.snapshot.db")
        if (out.exists()) out.delete()
        out.parentFile?.mkdirs()
        val d = db()
        // ⚠ PRAGMA wal_checkpoint(TRUNCATE) 会返回 (busy, log, checkpointed) 三列结果集。
        // Android 的 execSQL 对「有结果集的语句」直接抛
        // "Queries can be performed using SQLiteDatabase query or rawQuery methods only"。
        // 必须走 query() 把游标 drain 掉（与 checkpoint() 写法一致），不能 execSQL。
        d.query("PRAGMA wal_checkpoint(TRUNCATE)").use { /* drain */ }
        val vacuumOk = runCatching {
            d.execSQL("VACUUM INTO '${out.absolutePath.escapeSql()}'")
        }.isSuccess
        if (!vacuumOk || !out.exists() || out.length() == 0L) {
            // 旧 SQLite / InvalidationTracker 占用事务时 VACUUM INTO 会失败：
            // TRUNCATE 已冲完 WAL，主库文件本身就是完整一致快照，直接拷。
            d.query("PRAGMA wal_checkpoint(TRUNCATE)").use { /* drain */ }
            AppPaths.databaseFile(context).copyTo(out, overwrite = true)
        }
        if (!out.exists() || out.length() == 0L) {
            throw IllegalStateException("快照未产出文件")
        }
        return out
    }

    fun stageRestore(bytes: File): JsonObject {
        requireArg(bytes.length() > 100) { "上传的 DB 太小" }
        val header = ByteArray(16)
        bytes.inputStream().use { it.read(header) }
        requireArg(String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3") { "不是 SQLite 文件" }
        // 独立连接只做校验，绝不写主库
        android.database.sqlite.SQLiteDatabase.openDatabase(
            bytes.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { tmp ->
            val check = tmp.rawQuery("PRAGMA quick_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else "unknown"
            }
            requireArg(check == "ok") { "quick_check 不通过：$check" }
        }
        val staged = File(AppPaths.filesDir(context), "databases/rikka_hub.incoming")
        bytes.copyTo(staged, overwrite = true)
        return buildJsonObject {
            put("ok", true)
            put("staged", staged.absolutePath)
            put("bytes", staged.length())
            put("need_restart", true)
            put("next", "POST /api/db/restore/commit")
        }
    }

    fun commitRestore(): JsonObject {
        val staged = File(AppPaths.filesDir(context), "databases/rikka_hub.incoming")
        if (!staged.exists()) throw NotFoundException("没有暂存的 DB，先调 /api/db/restore")
        val target = AppPaths.databaseFile(context)
        database.close()
        staged.copyTo(target, overwrite = true)
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        staged.delete()
        scheduleRestart()
        return buildJsonObject {
            put("ok", true)
            put("restarting", true)
            put("restored_bytes", target.length())
        }
    }

    fun scheduleRestart(delayMs: Long = 1200L) {
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (intent != null) context.startActivity(intent)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }, delayMs)
    }

    // ---------------- helpers ----------------

    private fun rowsAsArray(sql: String) = kotlinx.serialization.json.buildJsonArray {
        db().query(sql).use { c ->
            val cols = c.columnNames.toList()
            while (c.moveToNext()) {
                add(buildJsonObject {
                    cols.forEachIndexed { i, name -> put(name, readCell(c, i)) }
                })
            }
        }
    }

    private fun scalar(sql: String): String =
        db().query(sql).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }

    private fun scalarLong(sql: String): Long =
        db().query(sql).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun readCell(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(
            BLOB_JSON_PREFIX + Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
        )
        else -> JsonPrimitive(c.getString(i) ?: "")
    }

    private fun String.escapeIdent(): String = replace("\"", "\"\"")
    private fun String.escapeSql(): String = replace("'", "''")

    private fun JsonElement.toSqlValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> {
            val s = contentOrNull
            when {
                s == null -> null
                // readCell 给 BLOB 打了前缀，这里还原成 ByteArray，execSQL 会 bindBlob，
                // 否则 base64 字符串写回 BLOB 列会变成 TEXT（2026-11-15 修）。
                s.startsWith(BLOB_JSON_PREFIX) ->
                    runCatching {
                        Base64.decode(s.removePrefix(BLOB_JSON_PREFIX), Base64.NO_WRAP)
                    }.getOrDefault(s)
                // 只有真正的 JSON 布尔才转 1/0；TEXT 列里存的字符串 "true" 必须原样保留。
                !isString && s == "true" -> 1L
                !isString && s == "false" -> 0L
                !isString && s.toLongOrNull() != null -> s.toLongOrNull()
                !isString && s.toDoubleOrNull() != null -> s.toDoubleOrNull()
                else -> s
            }
        }
        else -> toString()
    }

    suspend fun exportSettings() = settingsExchange.exportAll()
    suspend fun importSettings() = settingsExchange.importAllAndSync(adminBypass = true)
}

// ---------------- DTO ----------------

/**
 * 参数校验统一走 [BadRequestException]（→ HTTP 400），而不是 Kotlin 的
 * [IllegalArgumentException]（会被 StatusPages 兜成 500）。
 * 未知表 / 空 SQL / 非 SQLite 文件 / quick_check 不过，全是调用方传错了，
 * 语义上就该 400 —— 2026-11-15 实测 9 处 require 全被兜成 500，一并纠正。
 *
 * 必须是**文件级顶层**函数：DbBridge 内部要调，dbRoutes 的路由 lambda 里也要调。
 * 放在类里当 private 方法，路由侧会 Unresolved reference（CI 43614164871 实证）。
 */
private inline fun requireArg(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw BadRequestException(lazyMessage())
}

@Serializable
data class SqlRequest(val sql: String, val maxRows: Int = 5000)

@Serializable
data class SqlResponse(
    val columns: List<String>,
    val rows: List<Map<String, JsonElement>>,
    val rowCount: Int,
    val truncated: Boolean,
    val elapsedMs: Long,
)

@Serializable
data class ExecRequest(
    val sql: String = "",
    val statements: List<String> = emptyList(),
    val transactional: Boolean = true,
)

@Serializable
data class ExecResponse(
    val statements: Int,
    val changes: Int,
    val lastInsertRowId: Long,
    val elapsedMs: Long,
)

@Serializable
data class ImportRequest(val tables: Map<String, List<Map<String, JsonElement>>>)

@Serializable
data class ConversationUpsertRequest(
    val conversation: Map<String, JsonElement>,
    val nodes: List<Map<String, JsonElement>> = emptyList(),
)

private const val MAX_DB_UPLOAD_BYTES = 3L * 1024 * 1024 * 1024

/**
 * BLOB 列在 JSON 里用这个前缀标记，导入时还原成 ByteArray（见 readCell / toSqlValue）。
 * Base64.decode 对非法串会抛异常，被 runCatching 兜住原样写回，
 * 所以普通 TEXT 内容即使恰好以这个前缀开头也不会被破坏。
 */
private const val BLOB_JSON_PREFIX = "rikkahub-blob-base64:"

/** JsonObject.toString() 本身就是合法 JSON；避开 Ktor 对 JsonElement 的序列化器查找。 */
private suspend fun ApplicationCall.respondJson(obj: JsonElement) {
    respondText(obj.toString(), ContentType.Application.Json)
}

fun Route.dbRoutes(bridge: DbBridge, advancedConfigStore: SyncAdvancedConfigStore) {
    route("/db") {
        get {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            call.respondJson(bridge.status())
        }

        get("/health") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            call.respondJson(buildJsonObject { put("ok", true) })
        }

        get("/status") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            call.respondJson(bridge.status())
        }

        get("/tables") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            call.respondJson(bridge.tables())
        }

        post("/sql") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val req = call.receive<SqlRequest>()
            call.respond(bridge.query(req.sql, req.maxRows))
        }

        post("/exec") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val req = call.receive<ExecRequest>()
            val stmts = if (req.statements.isNotEmpty()) req.statements else splitStatements(req.sql)
            if (stmts.isEmpty()) throw BadRequestException("没有可执行的语句")
            call.respond(bridge.exec(stmts, req.transactional))
        }

        get("/manifest") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            call.respondJson(bridge.manifest(since))
        }

        get("/export") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val tables = call.request.queryParameters.getAll("tables").orEmpty()
                .flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            call.respondJson(bridge.export(tables, since))
        }

        post("/import") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val req = call.receive<ImportRequest>()
            val result = bridge.importRows(req.tables)
            call.respondJson(buildJsonObject {
                put("ok", true)
                put("tables", buildJsonObject { result.forEach { (k, v) -> put(k, v) } })
            })
        }

        get("/conversations") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
            call.respondJson(bridge.conversations(since))
        }

        get("/conversations/{id}") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val id = call.parameters["id"] ?: throw BadRequestException("Missing conversation id")
            call.respondJson(bridge.conversation(id))
        }

        post("/conversations") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val req = call.receive<ConversationUpsertRequest>()
            val n = bridge.upsertConversation(req.conversation, req.nodes)
            call.respondJson(buildJsonObject {
                put("ok", true)
                put("nodes_written", n)
            })
        }

        delete("/conversations/{id}") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val id = call.parameters["id"] ?: throw BadRequestException("Missing conversation id")
            bridge.exec(listOf("DELETE FROM ConversationEntity WHERE id='${id.replace("'", "''")}'"), false)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/checkpoint") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            call.respondJson(bridge.checkpoint())
        }

        get("/snapshot") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val file = bridge.snapshot()
            call.response.header(
                "Content-Disposition", "attachment; filename=\"rikka_hub.snapshot.db\""
            )
            call.response.header("Content-Type", ContentType.Application.OctetStream.toString())
            call.respondFile(file)
        }

        post("/restore") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val tmp = File(AppPaths.filesDir(bridge.context), "databases/rikka_hub.upload.tmp")
            try {
                val multipart = call.receiveMultipart()
                var got = false
                while (true) {
                    val part = multipart.readPart() ?: break
                    try {
                        if (part is PartData.FileItem && !got) {
                            writePartToFile(part, tmp)
                            got = true
                        }
                    } finally {
                        part.dispose()
                    }
                }
                requireArg(got) { "没有上传文件" }
                call.respondJson(bridge.stageRestore(tmp))
            } finally {
                // 校验失败（不是 SQLite / quick_check 不过 / 超过上限）也必须清掉临时文件，
                // 否则手机存储里会一直堆 1.1G 的 upload.tmp。
                tmp.delete()
            }
        }

        post("/restore/commit") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            call.respondJson(bridge.commitRestore())
        }

        post("/settings/export") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val r = bridge.exportSettings()
            call.respondJson(buildJsonObject {
                put("ok", true)
                put("dir", r.file.absolutePath)
            })
        }

        post("/settings/import") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            val r = bridge.importSettings()
            call.respondJson(buildJsonObject {
                put("ok", true)
                put("dir", r.file.absolutePath)
            })
        }

        post("/restart") {
            call.requireDeviceBridgeToken(advancedConfigStore, "db")
            bridge.scheduleRestart()
            call.respondJson(buildJsonObject { put("ok", true); put("restarting", true) })
        }
    }
}

private suspend fun writePartToFile(part: PartData.FileItem, dest: File) {
    dest.parentFile?.mkdirs()
    if (dest.exists()) dest.delete()
    val input = part.provider()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    FileOutputStream(dest).use { out ->
        while (true) {
            val read = input.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) break
            total += read
            if (total > MAX_DB_UPLOAD_BYTES) {
                throw BadRequestException("DB 超过 ${MAX_DB_UPLOAD_BYTES / 1024 / 1024} MB 上限")
            }
            out.write(buffer, 0, read)
        }
    }
}

/**
 * 按分号拆多条 SQL，但跳过单引号字符串 / 双引号标识符内的分号，以及
 * `--` 行注释、`/* */` 块注释里的分号。原先裸 split(";") 会把
 * `UPDATE t SET title='a;b'` 拦腰砍断（2026-11-15 修）。
 * 复杂场景仍建议调用方直接传 `statements` 数组。
 */
private fun splitStatements(sql: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var quote: Char? = null
    var lineComment = false
    var blockComment = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val n = sql.getOrNull(i + 1)
        if (lineComment) {
            sb.append(c)
            if (c == '\n') lineComment = false
            i++
            continue
        }
        if (blockComment) {
            sb.append(c)
            if (c == '*' && n == '/') { sb.append('/'); blockComment = false; i += 2; continue }
            i++
            continue
        }
        if (quote != null) {
            sb.append(c)
            if (c == quote) {
                // SQL 里 '' / "" 是转义引号，不是结束
                if (n == quote) { sb.append(n); i += 2; continue }
                quote = null
            }
            i++
            continue
        }
        when {
            c == '\'' || c == '"' -> { quote = c; sb.append(c); i++ }
            c == '-' && n == '-' -> { lineComment = true; sb.append(c).append(n); i += 2 }
            c == '/' && n == '*' -> { blockComment = true; sb.append(c).append(n); i += 2 }
            c == ';' -> {
                val s = sb.toString().trim()
                if (s.isNotEmpty() && s != ";") out += s
                sb.setLength(0)
                i++
            }
            else -> { sb.append(c); i++ }
        }
    }
    val tail = sb.toString().trim()
    if (tail.isNotEmpty()) out += tail
    return out
}
