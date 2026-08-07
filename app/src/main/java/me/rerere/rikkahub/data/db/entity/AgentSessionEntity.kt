package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * agent 会话编排元数据（方案 2026-08-07「对话即 Agent：跨对话协作式 Subagent」）。
 *
 * 每个 subagent = 一个真实 Conversation（落库 / 可打开 / 可插话 / 参与同步），
 * 本表只存"执行期编排信息"：父子关系、深度、状态、预算、执行快照。
 *
 * **不进 D1 同步**：agent 编排是本地执行期元数据，跨端观察靠对话本体同步已足够；
 * 对端同步来的 agent 对话在本端没有这行 → 按"只读观察"处理，禁止投递。
 */
@Entity(
    tableName = "agent_session",
    indices = [Index(value = ["parent_id"]), Index(value = ["root_id"]), Index(value = ["status"])],
)
data class AgentSessionEntity(
    /** = 子对话 conversationId */
    @PrimaryKey
    @ColumnInfo("child_id")
    val childId: String,
    /** 父对话 id（主 agent 或上层 agent） */
    @ColumnInfo("parent_id")
    val parentId: String,
    /** 根主对话 id，便于整棵树查询 / 清理 */
    @ColumnInfo("root_id")
    val rootId: String,
    @ColumnInfo("template_id")
    val templateId: String,
    /** 主对话 = 0，其子 = 1，上限见 AgentLimits.MAX_DEPTH */
    @ColumnInfo("depth")
    val depth: Int,
    /** running | idle | waiting_parent | waiting_approval | done | failed | stopped | archived */
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("task_brief")
    val taskBrief: String,
    /** auto | manual */
    @ColumnInfo("report_mode")
    val reportMode: String,
    /** 允许互发消息的 agent 会话 id JSON 数组 */
    @ColumnInfo("peers")
    val peers: String = "[]",
    @ColumnInfo("turns_with_parent")
    val turnsWithParent: Int = 0,
    @ColumnInfo("total_tokens")
    val totalTokens: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo("last_summary")
    val lastSummary: String = "",
    /** 执行快照 JSON：workspaceId / modelId / 工具白名单 / 审批策略 / 时限（模板事后被改不影响已建任务） */
    @ColumnInfo("profile_json")
    val profileJson: String = "{}",
)
