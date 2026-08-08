package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 云端同步的本地待推送队列（云锚点同步 P0）。
 *
 * 各 Repository 写钩在写本表之外的业务表时，同事务追加一行 outbox；
 * SyncEngine 在线时立即 flush，失败由 WorkManager backoff 重试。
 * 本表是设备私有账簿，永不上云。
 */
@Entity(
    tableName = "sync_outbox",
    indices = [Index("kind"), Index("created_at")],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 业务种类：conversation | bundle | 未来扩展（file 等） */
    @ColumnInfo("kind")
    val kind: String,
    /** 云端主键：会话 id / bundle k */
    @ColumnInfo("ref_key")
    val refKey: String,
    /** upsert | delete */
    @ColumnInfo("op")
    val op: String,
    /** 乐观写基于的云端 updated_at；0 = 无基线（新建） */
    @ColumnInfo("base_updated_at")
    val baseUpdatedAt: Long = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /**
     * 永久性失败次数：只有 [me.rerere.rikkahub.data.sync.core.SyncFailureClassifier]
     * 判定为 PERMANENT（D1 明确拒绝的 4xx / 语句失败）时才 +1，达上限进隔离区。
     * 没网、超时、协程取消一律不累加 —— 否则一次离线就能把待推数据永久判死。
     */
    @ColumnInfo("retry_count")
    val retryCount: Int = 0,
    /** 瞬时失败连续次数，仅驱动退避时长；不参与隔离判定 */
    @ColumnInfo("transient_attempt", defaultValue = "0")
    val transientAttempt: Int = 0,
    /** 下次可尝试时间（epoch ms）；退避期内 pending() 不会捞出本行 */
    @ColumnInfo("next_attempt_at", defaultValue = "0")
    val nextAttemptAt: Long = 0,
    @ColumnInfo("last_error")
    val lastError: String = "",
) {
    companion object {
        const val KIND_CONVERSATION = "conversation"
        const val KIND_BUNDLE = "bundle"
        const val OP_UPSERT = "upsert"
        const val OP_DELETE = "delete"
    }
}
