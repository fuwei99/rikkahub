package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * agent 收件箱条目（方案 2026-08-07「多 Agent 通信内核」收敛设计 §2/§10，落地 plan Step 1）。
 *
 * **收件箱是唯一真相源（I2）**：所有跨对话事件无条件先落这里，再谈调度动作。
 * 「没唤醒/没抢占」从不等于「丢了」——发送方入库后立即返回（I3），目标何时读由调度层决定。
 *
 * **不进 D1 同步**：与 agent_session 同策略，属本地执行期数据；
 * 进程被杀不能丢回报的兜底全靠这张表。
 */
@Entity(
    tableName = "agent_inbox",
    indices = [
        Index(value = ["target_id"]),
        Index(value = ["target_id", "read_at"]),
    ],
)
data class AgentInboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    /** 目标对话 conversationId（信进谁的箱子） */
    @ColumnInfo("target_id")
    val targetId: String,
    /**
     * 入站来源（开放枚举，字符串存库）：
     * human | sub_agent | peer | system_retry；预留 cron | external（收敛设计 §9）。
     */
    @ColumnInfo("source")
    val source: String,
    /** 紧急度：silent | mail | call | blocking（blocking 期二才接线） */
    @ColumnInfo("urgency")
    val urgency: String,
    /** 投递类型：task | report | ask | instruction | peer | system */
    @ColumnInfo("kind")
    val kind: String,
    /** 发送方对话 id（系统事件可为 null） */
    @ColumnInfo("sender_id")
    val senderId: String? = null,
    @ColumnInfo("sender_title")
    val senderTitle: String = "",
    @ColumnInfo("template_id")
    val templateId: String? = null,
    /** 消息正文（回报默认是摘要，收敛设计 §9「产出摘要传递」） */
    @ColumnInfo("body")
    val body: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** null = 未读；读取即标记（I4：同一封信不得两次进上下文） */
    @ColumnInfo("read_at")
    val readAt: Long? = null,
)
