package me.rerere.rikkahub.data.ai.schedule

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
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
    /**
     * 兜底触发周期（分钟）：仅在 [windows] 与 [dailyTimes] **都为空**时生效。
     *
     * 配了 windows 就该用窗口自己的 intervalMinutes，别再靠这个全局值。
     */
    val intervalMinutes: Int = 10,

    /**
     * 时间段 + 段内周期（多段，各段独立节奏）。
     *
     * 例：早/午/晚自习 10 分钟一查，夜间睡眠 60 分钟一查，写成 4 个窗口即可，
     * 不用像以前那样拆成 4 个模板。窗口外闹钟直接排到下一场开场，不空转。
     *
     * 段内格子从窗口 start 起算（`start + k*interval`），所以触发点永远钉在
     * 整刻度上——这是老 `now + interval` 漂移问题的解药。
     */
    val windows: List<ScheduleWindow> = emptyList(),

    /**
     * 每天固定时刻触发（多点），与 [windows] 正交的硬保底。
     *
     * 例：08:30 必须查一次确认起床、22:20 晚自习收卷。无论窗口怎么配都会触发。
     */
    val dailyTimes: List<ScheduleDailyTime> = emptyList(),

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

    /**
     * 备用模型链（至多 3 个，超出截断；与主模型一起去重、丢弃 Settings 里已不存在的条目）。
     *
     * 主模型生成失败（网络/限流/模型报错等可恢复错误）时由 AgentBridge 按顺序切换
     * 并重新投递同一份任务（PLAN_AGENT_RETRY_FALLBACK §2.1/§2.4 schedule 侧）。
     * 查岗这类高频任务配一条便宜模型链，主模型挂了不至于整轮查岗报废。
     */
    val fallbackModelIds: List<Uuid> = emptyList(),

    /**
     * 绑定的工作区 id（Uuid 字符串）。
     *
     * null = 跟随助手的 workspaceId（原行为）。
     * 非 null = 模板自带工作区，**不再依赖助手绑定**。
     *
     * 为什么需要这个字段：
     * - 同步覆盖 / tombstone 重建后助手的 workspaceId 可能还没同步过来 → 空壳
     * - 定时任务的 workspace 工具（grep/shell/edit）全靠会话级 workspaceId，
     *   没绑工作区就等于把手剁了
     * - 模板自带就彻底解耦：助手怎么改都不影响已建的定时任务
     */
    val workspaceId: String? = null,

    // ---- 工具（空 = 跟随助手默认；inbox 恒强制开启，否则读不到派活消息；2026-08-20 起 inbox 含收发）----
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

    // ---- 每次触发时的任务指令模板 ----
    /**
     * 占位符：`{time}` `{date}` `{name}`，以及本轮触发上下文
     * `{window}`（命中的窗口名）/ `{tag}`（命中的定时点标签）。
     */
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

    /**
     * 自动压缩覆盖（2026-08-21）：建会话时写进 `Conversation.autoCompressOverride`。
     *
     * reuse 模式下这是**唯一**能自动折叠常驻会话历史的机制：以前只能硬顶到
     * `MAX_MESSAGE_NODES - 8` 触发粗暴轮换，一轮换上下文全丢。
     * null = 跟随绑定助手的 autoCompress（助手默认关 → 等于不压）。
     */
    val autoCompress: AutoCompressOverride? = null,
    val timeoutMinutes: Int = 15,
    val maxTotalTokens: Int = 128_000,

    /**
     * 会话轮换阈值：常驻会话的 messageNodes 数达到
     * `min(maxMessageNodes, AgentLimits.MAX_MESSAGE_NODES) - rotateMargin` 时换新会话。
     *
     * 以前只有硬编码的 `MAX_MESSAGE_NODES(120) - 8`，模板无权干预：查岗一轮就十几个
     * 节点，128k 预算根本用不到就被节点数逼着轮换（上下文整段丢）。现在两个值都能配，
     * 想「按 token 换会话」就把 maxMessageNodes 拉到上限、靠 autoCompress + maxTotalTokens 控。
     * 0 / 负数 = 用 AgentLimits.MAX_MESSAGE_NODES。
     */
    val maxMessageNodes: Int = 0,

    /** 轮换余量（距上限还剩这么多节点就换会话）；<=0 时回落 8。 */
    val rotateMargin: Int = 8,

    /**
     * 卡死自愈（2026-08-21 修「Rikkahub 被杀 → 状态永久 running → 后续触发全被阻塞」）。
     *
     * 常驻会话的 status 存在 DB 里，进程被异常 kill（用户杀后台 / OOM / 重启手机）时
     * 没有任何人把 running 改回来，于是 Runner 的「正在跑就别插队」判断永久成立，
     * 定时任务从此彻底哑掉——而 UI 上还挂着「运行中」，看着像在干活。
     *
     * 现在：running/waiting_* 状态若已经 [staleRunMinutes] 分钟没有任何进展，
     * 视为**僵死**，本次触发直接抢占（回收状态 + 重新派活）。
     * 取值建议 = timeoutMinutes 的 1~2 倍；<=0 表示禁用自愈（不推荐）。
     */
    val staleRunMinutes: Int = 20,

    /**
     * 阻塞时的兜底投递（2026-08-21，天赢点名要的「解耦」）。
     *
     * true = 上一轮无论完没完成，本次触发**照样把任务投进收件箱**（inbox 天然是队列，
     * agent 下一轮读信时会一次看完积压），只是不再额外唤醒、不打断正在跑的那一轮；
     * false = 老行为，检测到忙就整轮丢弃（丢弃 = 那一次查岗永久消失）。
     *
     * 为什么默认 true：查岗漏一轮就是漏一小时的证据链。宁可让 agent 一次读到两封，
     * 也不能让某一轮凭空蒸发。
     */
    val deliverWhenBusy: Boolean = true,

    /**
     * 会话保护开关组（禁 cancel / fork / 重 roll / 删除 / 移动）。
     * 默认全关：普通定时任务行为不变；监督类任务在 JSON 里显式打开。
     */
    val protection: ScheduleProtection = ScheduleProtection(),

    // ---- 汇报 ----
    /** 无父节点：汇报是否弹系统通知（默认 true）。 */
    val notifyOnReport: Boolean = true,
    /** 提前终止（没汇报就结束）提醒次数上限，0 = 用全局默认（AgentLimits.MAX_PREMATURE_END_REMINDERS=2）。 */
    val prematureEndReminders: Int = 0,

    /** 会话所在文件夹名；null = 默认「◆ 模板名」（查岗模板可写 "监督"）。 */
    val folderName: String? = null,

    val updatedAt: Long = 0L,
) {
    /** 是否复用常驻会话（conversationMode == "reuse"）。 */
    val reuseConversation: Boolean get() = conversationMode != "fresh"

    /** 兜底周期（至少 1 分钟）。 */
    val safeIntervalMinutes: Int get() = intervalMinutes.coerceAtLeast(1)

    /** 是否使用新调度模型（窗口 / 定时点任一非空）。 */
    val usesWindowSchedule: Boolean get() = windows.isNotEmpty() || dailyTimes.isNotEmpty()

    /** 生效的节点数上限（0/越界 → 全局上限）。 */
    fun effectiveMaxMessageNodes(globalLimit: Int): Int =
        maxMessageNodes.takeIf { it in 1..globalLimit } ?: globalLimit

    /** 生效的轮换余量（至少 1，且不超过上限的一半，避免配出「永远在轮换」）。 */
    fun effectiveRotateMargin(globalLimit: Int): Int =
        rotateMargin.coerceIn(1, (effectiveMaxMessageNodes(globalLimit) / 2).coerceAtLeast(1))

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
    description = "自习时段 10 分钟一查、睡眠时段 60 分钟一查；08:30 起床确认 / 22:20 晚自习收卷定点必查。看屏幕时间 / 近期对话，判断是否在学习。",
    enabled = true,
    intervalMinutes = 10,
    windows = listOf(
        ScheduleWindow(name = "早自习", start = "08:30", end = "11:50", intervalMinutes = 10),
        ScheduleWindow(
            name = "午自习",
            days = listOf(1, 2, 3, 4, 5, 6),
            start = "14:00",
            end = "17:50",
            intervalMinutes = 10,
        ),
        ScheduleWindow(name = "晚自习", start = "18:40", end = "22:20", intervalMinutes = 10),
        ScheduleWindow(name = "午休", start = "13:20", end = "14:00", intervalMinutes = 20),
        ScheduleWindow(name = "夜间睡眠", start = "01:20", end = "06:00", intervalMinutes = 60),
    ),
    dailyTimes = listOf(
        ScheduleDailyTime(at = "08:30", tag = "起床确认"),
        ScheduleDailyTime(at = "22:20", tag = "晚自习收卷"),
    ),
    assistantId = assistantId,
    inheritMemory = true,
    inheritMemoryGraph = true,
    folderName = "监督",
    // 监督类任务全锁：被监督的人不该有「一键掐掉监工」的按钮
    protection = ScheduleProtection(enabled = true, notice = "监督查岗对话受保护"),
    staleRunMinutes = 20,
    deliverWhenBusy = true,
    taskPrompt = "查岗：请查看最近的屏幕使用时间、近期对话，判断用户是否在学习；必要时检查最近访问的网站并决定是否建议加入黑名单。完成后用 agent_report 汇报。",
    allowedLocalTools = listOf("screen_time", "ask_user", "time_info", "inbox"),
    allowedMcpTools = emptyList(),
)
