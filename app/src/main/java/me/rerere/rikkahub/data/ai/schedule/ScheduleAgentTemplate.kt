package me.rerere.rikkahub.data.ai.schedule

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 定时任务 Agent（Schedule Agent）配置模型（PLAN_SCHEDULE_AGENTS §2）。
 *
 * 与 [me.rerere.rikkahub.data.ai.subagent.SubagentTemplate] 平行：JSON 文件存
 * `filesDir/schedule-agents/` 下的 .json 文件，AI 可直接改文件，设置页只做开关列表。
 *
 * 设计要点：
 * - 复用「对话即 Agent」子会话机制：到点由调度器往该 agent 的可见对话投递一条
 *   system 消息（模拟父节点派活），AI 执行完调 agent_report 汇报；
 * - **无真实父对话**：`parentId` 统一用 [SCHEDULE_VIRTUAL_PARENT_ID] 哨兵，
 *   汇报无处可投 → 直接弹系统通知；
 * - **可绑定助手**：[assistantId] 非空时绑定学习助手，自动继承其 systemPrompt /
 *   记忆 / 记忆图 / 模型 / 工具默认值（这是与 subagent 的关键差异）；
 * - **记忆可配**：[inheritMemory] / [inheritMemoryGraph] / [inheritRecentChats]
 *   决定每次触发时是否带记忆上下文（subagent 是全关的隔离上下文）。
 */
@Serializable
data class ScheduleAgentTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,

    // ---- 定时 ----
    /** 触发周期（分钟）。 */
    val intervalMinutes: Int = 10,
    /** 可选：每天固定时刻触发（HH:mm，优先级高于 intervalMinutes）。 */
    val dailyAt: String? = null,

    // ---- 绑定哪个助手（核心差异）----
    /**
     * 绑定的助手 id。null = 不绑（用 [systemPrompt] 当人格，assistantId 用
     * AGENTS_ASSISTANT_ID 占位，记忆按 [inheritMemory] 等配置走）。
     * 非 null = 绑定学习助手：conversation.assistantId 用该助手，
     * **自动继承其 systemPrompt / 记忆 / 记忆图 / 模型 / 工具默认值**。
     */
    val assistantId: Uuid? = null,

    /** 不绑助手时的人格 prompt（绑定助手时此字段被忽略，用助手的 systemPrompt）。 */
    val systemPrompt: String? = null,

    // ---- 模型覆盖 ----
    /**
     * 覆盖模型（模型条目 uuid）。null = 用绑定助手的 chatModelId（原行为）。
     *
     * 查岗这类「高频 + 低难度 + 长期跑」的任务不该占用助手主力模型：
     * 30 分钟一次、一天二十来发、每次带记忆 + 记忆图 + 多轮工具调用，
     * 拿贵模型跑纯属烧钱。这里指定便宜模型，助手本体不受影响。
     *
     * 落到 conversation.modelId（会话级，优先级最高）+ AgentProfile.modelId 快照；
     * 填了不存在的 id 时由 ChatService 的解析链回落（会话 → 助手 → 全局）。
     */
    val modelId: Uuid? = null,

    // ---- 工具（空 = 跟随助手默认；inbox 恒强制开启，否则读不到派活消息）----
    /** 本地工具（LocalToolOption serialName），空 = 跟随助手默认 */
    val allowedLocalTools: List<String> = emptyList(),
    /** workspace 工具，空 = 跟随助手默认 */
    val allowedWorkspaceTools: List<String> = emptyList(),
    /** MCP 工具（"serverId/toolName"），空 = 跟随助手默认 */
    val allowedMcpTools: List<String> = emptyList(),

    // ---- 记忆/图（Schedule Agent 区别于 subagent 的关键开关，默认跟随助手）----
    /** 继承助手记忆（绑定助手时才有意义）。 */
    val inheritMemory: Boolean = true,
    /** 继承助手记忆图。 */
    val inheritMemoryGraph: Boolean = true,
    /** 引用最近对话。 */
    val inheritRecentChats: Boolean = false,
    /** 当 inheritMemory=false 且未绑助手时，用全关的隔离上下文（同 AGENT_MEMORY_OPTIONS）。 */

    // ---- 每次触发时的任务指令模板（{time} / {date} / {name} 占位符）----
    val taskPrompt: String = DEFAULT_TASK_PROMPT,

    // ---- 会话复用模式 ----
    /**
     * - "reuse"（默认）：一模板一常驻对话，每次触发往同一对话追加系统消息，
     *   上下文连续（查岗类任务需要"看看你上次说了啥"，靠 auto-compress 压历史）；
     * - "fresh"：每次触发新建一个对话，执行完标记 done/归档，互不干扰。
     */
    val conversationMode: String = "reuse", // reuse | fresh

    // ---- 执行限制 ----
    val maxSteps: Int = 50,
    val timeoutMinutes: Int = 15,
    val maxTotalTokens: Int = 128_000,

    // ---- 汇报 ----
    /** 无父节点：汇报是否弹系统通知（默认 true）。 */
    val notifyOnReport: Boolean = true,
    /** 提前终止（没汇报就结束）提醒次数上限，0 = 用全局默认（AgentLimits.MAX_PREMATURE_END_REMINDERS=2）。 */
    val prematureEndReminders: Int = 0,

    // ---- 监督联动（查岗类任务用）----
    /** 仅监督时段内触发。 */
    val onlyDuringSupervision: Boolean = false,

    /** 会话所在文件夹名；null = 默认「◆ 模板名」（查岗模板可写 "监督"）。 */
    val folderName: String? = null,

    val updatedAt: Long = 0L,
) {
    /** 是否复用常驻会话（conversationMode == "reuse"）。 */
    val reuseConversation: Boolean get() = conversationMode != "fresh"

    companion object {
        const val MODE_REUSE = "reuse"
        const val MODE_FRESH = "fresh"

        /** 校验 conversationMode，非法回退 reuse。 */
        fun normalizeConversationMode(raw: String?): String =
            if (raw == MODE_FRESH) MODE_FRESH else MODE_REUSE
    }
}

/** 默认任务指令模板（可被 JSON 覆盖）。 */
const val DEFAULT_TASK_PROMPT =
    "现在是你的一次定时执行。请按当前任务要求工作，完成后调用 agent_report 汇报结果。"

/**
 * 默认内置「监督查岗」模板（PLAN_SCHEDULE_AGENTS §6）。
 *
 * [assistantId] 留空 → 任务绑 AGENTS 或默认助手，用户可在 JSON 里改成自己的
 * 学习助手 id（或由设置页「设为守门员」联动自动填）。
 */
fun defaultCheckInTemplate(assistantId: Uuid? = null): ScheduleAgentTemplate = ScheduleAgentTemplate(
    id = "supervision_checkin",
    name = "监督查岗",
    description = "监督期内每 10 分钟看一次屏幕时间 / 近期对话 / 网络，判断是否在学习，必要时建议加入黑名单。",
    enabled = true,
    intervalMinutes = 10,
    assistantId = assistantId,
    inheritMemory = true,
    inheritMemoryGraph = true,
    onlyDuringSupervision = true,
    folderName = "监督",
    taskPrompt = "查岗：请查看最近的屏幕使用时间、近期对话，判断用户是否在学习；必要时检查最近访问的网站并决定是否建议加入黑名单。完成后用 agent_report 汇报。",
    allowedLocalTools = listOf("screen_time", "ask_user", "time_info", "inbox", "send"),
    allowedMcpTools = emptyList(),
)
