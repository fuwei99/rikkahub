package me.rerere.rikkahub.data.sync.d1

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * D1 表轻量抽象（P4）：提供声明式列映射与 CRUD 辅助，无需重量级 KSP 处理。
 */
abstract class D1Table<T>(
    val tableName: String,
    val primaryKey: String,
    private val serializer: KSerializer<T>,
    private val json: Json = Json,
) {
    abstract fun toRow(item: T): Map<String, Any?>
    abstract fun fromRow(row: JsonObject): T?

    suspend fun insertOrReplace(client: D1Client, item: T): D1StatementResult {
        val row = toRow(item)
        val cols = row.keys.joinToString(", ")
        val placeholders = row.keys.joinToString(", ") { "?" }
        val sql = "INSERT OR REPLACE INTO $tableName ($cols) VALUES ($placeholders)"
        return client.query(sql, row.values.toList())
    }

    suspend fun findById(client: D1Client, id: Any): T? {
        val sql = "SELECT * FROM $tableName WHERE $primaryKey = ?"
        val row = client.query(sql, listOf(id)).results.firstOrNull() ?: return null
        return fromRow(row)
    }

    suspend fun selectAll(client: D1Client): List<T> {
        val sql = "SELECT * FROM $tableName"
        return client.query(sql).results.mapNotNull { fromRow(it) }
    }

    suspend fun deleteById(client: D1Client, id: Any): D1StatementResult {
        val sql = "DELETE FROM $tableName WHERE $primaryKey = ?"
        return client.query(sql, listOf(id))
    }

    protected fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    protected fun JsonObject.long(key: String): Long? =
        string(key)?.toLongOrNull()

    protected fun JsonObject.boolean(key: String): Boolean =
        long(key)?.let { it == 1L } ?: (string(key)?.toBooleanStrictOrNull() ?: false)
}
