package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记忆自动提炼候选（记忆图 P3，对齐 Operit MemoryAutoSaveCandidate）：
 * 对话完成后入队，攒够 MIN_TOTAL_CANDIDATES 才批量抽取，避免每轮都调 LLM 烧 token。
 */
@Entity(tableName = "memory_auto_save_candidate")
data class MemoryAutoSaveCandidateEntity(
    @PrimaryKey(true)
    val id: Long = 0,
    /** 记忆作用域（assistant.id.toString() 或 GLOBAL_MEMORY_ID） */
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("chat_id")
    val chatId: String,
    /** 触发消息时间戳（调度器按此取最近消息） */
    @ColumnInfo("trigger_timestamp")
    val triggerTimestamp: Long,
    @ColumnInfo("source_type")
    val sourceType: String = SOURCE_REPLY_FINALIZED_AUTO,
    @ColumnInfo("status")
    val status: String = STATUS_PENDING,
    @ColumnInfo("error")
    val error: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_FAILED = "failed"

        /** 对话回复完成后自动入队（对应 Operit reply_finalized_auto） */
        const val SOURCE_REPLY_FINALIZED_AUTO = "reply_finalized_auto"
    }
}
