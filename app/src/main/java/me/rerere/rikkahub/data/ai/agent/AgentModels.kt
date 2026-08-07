package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 「对话即 Agent」的数据模型与限额常量（方案 2026-08-07）。
 *
 * 核心思想：subagent 不是新的执行引擎，而是"一个由 LLM 而非人类担任对话方的普通对话"。
 * 所以这里只描述 **投递协议** 与 **约束**，执行全靠 ChatService 现成的公共 API。
 */

/** agent 会话状态机（与 AgentSessionEntity.status 字符串一一对应） */
object AgentStatuses {
    const val RUNNING = "running"
    const val IDLE = "idle"
    const val WAITING_PARENT = "waiting_parent"
    const val WAITING_APPROVAL = "waiting_approval"
    const val DONE = "done"
    const val FAILED = "failed"
    const val STOPPED = "stopped"
    const val ARCHIVED = "archived"

    val ACTIVE = setOf(RUNNING, IDLE, WAITING_PARENT, WAITING_APPROVAL)
    val TERMINAL = setOf(DONE, FAILED, STOPPED, ARCHIVED)
}

/** 硬限额：全部在 AgentBridge 统一校验，工具层不自行判断（plan §3.6） */
object AgentLimits {
    /** 委派深度上限（主对话 = 0） */
    const val MAX_DEPTH = 2

    /** 单主对话活跃子 agent 上限 */
    const val MAX_ACTIVE_PER_PARENT = 3

    /** 全局活跃 agent 会话上限 */
    const val MAX_ACTIVE_GLOBAL = 6

    /** 单 agent 会话消息节点数上限 */
    const val MAX_MESSAGE_NODES = 120

    /** A↔B 往返次数上限 */
    const val MAX_TURNS_WITH_PARENT = 8

    /** 单 agent 会话 token 预算（模板可调） */
    const val DEFAULT_MAX_TOTAL_TOKENS = 128_000

    /** 单 agent 会话墙钟时长（分钟，模板可调） */
    const val DEFAULT_TIMEOUT_MINUTES = 30

    /** 自动回报摘要截断长度 */
    const val REPORT_SUMMARY_MAX_CHARS = 1500

    /** 攒批合并窗口（毫秒）：窗口内到达的多条回报合并成一条 user 消息 */
    const val BATCH_WINDOW_MS = 2_000L

    /** 等待生成结束的兜底超时（毫秒）：generationDoneFlow 不可靠，必须有超时 */
    const val WAIT_GENERATION_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * 单目标未读上限：超出后新信合并进最后一封未读（收敛设计 §10，
     * 防单个 agent 疯狂 report 撑爆收件箱与未读提示）。
     */
    const val MAX_UNREAD_PER_TARGET = 20

    /** 已读邮件保留期（毫秒）：随 agent 会话保留期清理一起删 */
    const val INBOX_READ_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
}

/**
 * 审批策略。
 *
 * `parent` 表示"转交父 agent 审批"，但**危险工具永远强制回落 user**：
 * 让 LLM 批自己等于自动放行。硬名单写在代码里，模板无权覆盖（plan §3.3）。
 */
object AgentApprovalMode {
    const val AUTO = "auto"
    const val PARENT = "parent"
    const val USER = "user"

    /** 硬名单：无论模板怎么配，这些工具的审批一律回落给真人 */
    val FORCE_USER_APPROVAL: Set<String> = setOf(
        "workspace_shell",
        "workspace_shell_session",
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_apply_patch",
        "workspace_codex_patch",
        "workspace_backup",
        "set_alarm",
        "send_notification",
        // 子 agent 反问真人必须显式化，不允许父 agent 代答
        "ask_user",
    )

    fun normalize(raw: String?): String = when (raw?.lowercase()) {
        AUTO -> AUTO
        USER -> USER
        else -> PARENT
    }

    /** 该工具的审批是否必须由真人处理 */
    fun forcesUser(toolName: String): Boolean = toolName in FORCE_USER_APPROVAL
}

/** 回报模式 */
object AgentReportMode {
    const val AUTO = "auto"
    const val MANUAL = "manual"

    fun normalize(raw: String?): String = if (raw?.lowercase() == MANUAL) MANUAL else AUTO
}

/** 消息发送方身份（写进 UIMessagePart.Text.metadata，UI 据此渲染署名） */
object AgentSenderRole {
    const val HUMAN = "human"
    const val MAIN_AGENT = "main_agent"
    const val SUB_AGENT = "sub_agent"
    const val PEER_AGENT = "peer_agent"
    const val SYSTEM_REPORT = "system_report"

    /** 系统开启的轮次（唤醒提示等）：I9 要求署名 system，不得伪装 human/agent */
    const val SYSTEM = "system"
}

/** 投递类型：决定优先级与能否延后 */
enum class AgentMessageKind {
    /** 首条任务派发（不可延后） */
    TASK,

    /** 子 → 父 自动/主动回报（可延后、可攒批） */
    REPORT,

    /** 子 → 父 反问（不可延后，不攒批） */
    ASK,

    /** 父 → 子 追加指令 / 回答（不可延后） */
    INSTRUCTION,

    /** agent ↔ peer 平级协作 */
    PEER,

    /** 系统通告（超限 / 超时 / 预算耗尽） */
    SYSTEM,
    ;

    val deferrable: Boolean get() = this == REPORT
    val batchable: Boolean get() = this == REPORT
}

/**
 * 紧急度（收敛设计 §2.2）：入库是无条件的，紧急度只决定「附加什么调度动作」。
 *
 * - SILENT：只入库，永不触发轮次（定时任务例行结果 / 系统通告）；
 * - MAIL：入库 + 按对话性质唤醒（子 agent 默认档）；
 * - CALL：入库 + 尝试抢占。**本轮行为等同 MAIL**（抢占期二接线，枚举与参数位先留）；
 * - BLOCKING：入库 + 发送方挂起等结果（join，期二接线）。
 */
enum class AgentUrgency(val wire: String) {
    SILENT("silent"),
    MAIL("mail"),
    CALL("call"),
    BLOCKING("blocking"),
    ;

    companion object {
        fun parse(raw: String?): AgentUrgency = when (raw?.lowercase()?.trim()) {
            "silent" -> SILENT
            "call" -> CALL
            "blocking" -> BLOCKING
            else -> MAIL
        }
    }
}

/**
 * 入站来源（收敛设计 §9 开放枚举）：字符串存库，加新来源不动调度层。
 * 预留 cron / external（定时任务、微信 MCP、外部 agent）本轮不实现。
 */
object AgentInboxSource {
    const val HUMAN = "human"
    const val SUB_AGENT = "sub_agent"
    const val PEER = "peer"
    const val SYSTEM_RETRY = "system_retry"
    const val SYSTEM = "system"
}

/**
 * 对话性质（收敛设计 §6.1，给大计划留的最重要口子）。
 *
 * 本轮只实现前两值的判定（有 agent_session 行 = SUB_AGENT，否则 HUMAN_MAIN）；
 * 后两值枚举先建，常驻 agent / cron 上线时只改判定函数，不动调度层分支。
 */
enum class ConversationNature {
    /** 人类主对话：有活跃派活才唤醒，否则只留未读提示 */
    HUMAN_MAIN,

    /** 子 agent 对话：来信就是它的时钟，自动唤醒续跑 */
    SUB_AGENT,

    /** 常驻 agent（未来微信侧）：事件驱动，立即唤醒 */
    RESIDENT,

    /** 静默流程（未来 cron）：永不唤醒，只落库 */
    SILENT_FLOW,
}

/**
 * 一条待投递消息。
 *
 * 投递必须队列化：`ChatService.sendMessage` 开头就是 `previousJob?.cancel()`，
 * 多个子 agent 同时回报会互相掐掉生成（plan §3.1 最高优先级问题）。
 */
data class AgentMessage(
    val target: Uuid,
    val text: String,
    val kind: AgentMessageKind,
    val senderRole: String,
    val senderConversationId: Uuid? = null,
    val senderTitle: String? = null,
    val templateId: String? = null,
    /** 是否触发目标对话生成 */
    val answer: Boolean = true,
    val enqueuedAt: Long = System.currentTimeMillis(),
)

/** spawn 的可选覆盖项（工具入参解析结果） */
data class SpawnOverrides(
    val tools: List<String>? = null,
    val maxSteps: Int? = null,
    val timeoutMinutes: Int? = null,
    val maxTotalTokens: Int? = null,
    val reportMode: String? = null,
    val peers: List<String> = emptyList(),
    val modelUuid: Uuid? = null,
    val wait: Boolean = false,
)

/** spawn 结果 */
data class AgentSpawnResult(
    val conversationId: Uuid?,
    val status: String,
    val title: String = "",
    val error: String? = null,
    /** 被人类总闸降级的模板声明清单（§7.4，空 = 无降级） */
    val downgraded: List<String> = emptyList(),
) {
    val ok: Boolean get() = error == null && conversationId != null
}

/** status 查询结果 */
data class AgentStatusInfo(
    val conversationId: Uuid,
    val templateId: String,
    val taskBrief: String,
    val status: String,
    val depth: Int,
    val messageCount: Int,
    val totalTokens: Int,
    val turnsWithParent: Int,
    val lastSummary: String,
    val hasPendingApproval: Boolean,
    val title: String,
)

/**
 * 执行快照（落 agent_session.profile_json）。
 *
 * per-conversation 的工具/workspace 覆盖 map 全在内存，重启即空、且模板随时会被用户改；
 * 快照保证"已创建的任务按创建时的配置继续跑"（plan §4.2 / §4.8）。
 */
@Serializable
data class AgentProfile(
    val workspaceId: String? = null,
    val workspaceCwd: String? = null,
    val modelId: String? = null,
    val localTools: List<String> = emptyList(),
    val workspaceTools: List<String> = emptyList(),
    val mcpTools: List<String> = emptyList(),
    val approvalMode: String = AgentApprovalMode.PARENT,
    val maxSteps: Int = 50,
    val timeoutMinutes: Int = AgentLimits.DEFAULT_TIMEOUT_MINUTES,
    val maxTotalTokens: Int = AgentLimits.DEFAULT_MAX_TOTAL_TOKENS,
    val allowPeerMessaging: Boolean = false,
    val startedAt: Long = 0L,
    // ---- 声明式权限快照（落地 plan Step 5）：按创建时的模板 + 总闸状态固化 ----
    /** 派生权（已按总闸降级后的生效值） */
    val canSpawn: Boolean = false,
    /** 派生预算（0 = 不允许再派） */
    val spawnBudget: Int = 0,
    /** 打断权（本轮建字段，期二 CALL 接线；总闸关时已降为 none） */
    val interruptRight: String = "none",
    /** 通知通道（app | silent，预留 wechat） */
    val notificationChannel: String = "app",
    /** 被总闸降级的声明清单（UI/工具返回值明示，§7.4） */
    val downgraded: List<String> = emptyList(),
)
