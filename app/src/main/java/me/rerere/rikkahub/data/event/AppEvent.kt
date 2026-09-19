package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()

    /**
     * 某个 agent 子会话出现了必须真人处理的待审批工具调用。
     *
     * 危险工具（shell / 写文件 / patch / 闹钟 / 通知 / ask_user）属于硬名单，
     * 父 agent 无权代批，只能弹给用户；用户此刻可能根本没在看那个子对话，
     * 所以要走通知 + 主对话卡片高亮把人叫回来（plan §4.7-5）。
     */
    data class AgentApprovalPending(
        /** 待审批发生在哪个 agent 子会话 */
        val childId: Uuid,
        /** 派活的父对话，用于「回到主对话」与卡片高亮 */
        val parentId: Uuid,
        val toolName: String,
        val taskBrief: String,
    ) : AppEvent()

    /**
     * Schedule Agent（定时任务）汇报 / 异常通知（PLAN_SCHEDULE_AGENTS §4.2）。
     *
     * 定时任务没有真实父对话可汇报，完成 / 出错 / 多次未汇报都直接弹系统通知。
     * 由 ChatNotificationManager 消费。
     */
    data class ScheduleAgentNotification(
        val title: String,
        val message: String,
    ) : AppEvent()

    /**
     * ask_user 提问待回答（2026-08-10）。
     *
     * 原来 ask_user 只在对话流里内联渲染一个输入框：用户没滚到那儿、或人根本
     * 不在这个对话（子 agent / 定时任务提问）时，生成就永久停在 Pending 上
     * 干等，看起来像卡死。现在改为「全局弹窗 + 系统通知」双通道把人叫回来。
     *
     * 由 RouteActivity 消费弹全局对话框，ChatNotificationManager 消费发通知。
     */
    data class AskUserPending(
        /** 提问发生在哪个对话（回答要投回这里） */
        val conversationId: Uuid,
        val toolCallId: String,
        /** ask_user 的原始 arguments JSON，弹窗自行解析 questions */
        val argumentsJson: String,
        /** 第一个问题，用于通知正文 */
        val firstQuestion: String,
        /** 超时截止时间（epoch ms），弹窗据此显示倒计时 */
        val deadlineAt: Long,
    ) : AppEvent()

    /**
     * ask_user 已结束等待（用户回答了 / 超时兜底了 / 生成被取消）。
     * 全局弹窗据此关闭，通知据此撤销——否则人回答完弹窗还赖着不走。
     */
    data class AskUserResolved(val toolCallId: String) : AppEvent()

    /**
     * 监督管理工具要上锁，正在给用户留最后的申诉窗口
     * （PLAN_SUPERVISION_ADMIN_TOOL §5）。
     *
     * 注意语义：**这不是征求同意**。倒计时结束 / 用户点 ❌ / 用户提交申诉，
     * 三种结局都会立刻落锁；申诉正文只是投进发起方 agent 的收件箱，
     * 由它自己决定要不要后续 `unlock_*`。锁的落地绝不依赖一次 LLM 往返，
     * 否则模型抽风或断网就等于监督失效。
     *
     * 由 [me.rerere.rikkahub.ui.components.chat.AppealDialogHost] 弹窗消费，
     * [me.rerere.rikkahub.service.ChatNotificationManager] 发通知消费。
     */
    data class SupervisionAppealPending(
        /** 协调器生成的申诉 id（Uuid 字符串），Resolved 用它对账 */
        val appealId: String,
        /** 发起上锁的 agent 所在对话：申诉残句投回这里 */
        val initiatorConversationId: Uuid,
        /** 人类可读的上锁目标描述（对话 id 前缀 / 路径前缀） */
        val targetLabel: String,
        /** agent 给的上锁理由 */
        val reason: String,
        /** 绝对截止时刻（epoch ms），可被「再给一会儿」推迟 */
        val deadlineAt: Long,
        val extensionsLeft: Int,
        val extensionSeconds: Int,
        /** false = schedule agent 无人值守发起，只发通知不弹窗 */
        val showDialog: Boolean,
    ) : AppEvent()

    /** 申诉窗口结束（已落锁）。弹窗据此关闭，通知据此撤销。 */
    data class SupervisionAppealResolved(val appealId: String) : AppEvent()

    /**
     * 本机浮层提示（2026-09-19）。
     *
     * 两条投递通道，落地是同一个事件：
     * - `notify_toast` 工具直投（应用内直发，**不依赖 web server 是否开着**）
     * - `POST /api/notify/toast`（workspace shell curl / 对端设备走隧道打进来）
     *
     * 与 [AskUserPending] 的语义差别很重要：这是**单向通知**，不阻塞任何生成、
     * 不等任何人回答。别拿它当 ask_user 用。
     *
     * [expireAt] 是**绝对到期时刻**（epoch ms），不是时长 —— UI 侧按剩余时间
     * 自行倒数，宿主重建 / 旋转 / 重组后剩余时间依然正确。<= 0 表示常驻，手动关。
     *
     * 教训来源：2026-09-19 申诉弹窗那个 bug —— 让弹窗靠 `delay(duration)` 自己
     * 管生命周期，一旦宿主重建就全乱。绝对时刻没有这个问题。
     */
    data class ToastPending(
        val toastId: String,
        val text: String,
        val title: String? = null,
        /** 见 [ToastLevel]，非法值一律归一为 info */
        val level: String = ToastLevel.INFO,
        /** 绝对到期时刻（epoch ms）；<= 0 = 常驻 */
        val expireAt: Long = 0L,
        /** 来源标签（工具名 / 设备名 / external），仅用于展示与排障 */
        val source: String? = null,
    ) : AppEvent()

    /** 浮层已被点掉 / 到期自动收摊。宿主据此移除。 */
    data class ToastDismissed(val toastId: String) : AppEvent()
}

/**
 * notify_toast 的级别常量。
 *
 * 独立成 object 而不是塞进 AppEvent 伴生对象：工具、路由、UI 三边都要用它做
 * 归一化，放这里谁都能引，不会绕出循环依赖。
 */
object ToastLevel {
    const val INFO = "info"
    const val SUCCESS = "success"
    const val WARN = "warn"
    const val ERROR = "error"

    val ALL: Set<String> = setOf(INFO, SUCCESS, WARN, ERROR)

    /** 非法 / 空值一律回落到 [INFO]，绝不抛异常 —— 调用方可能是外部 HTTP。 */
    fun normalize(raw: String?): String =
        raw?.trim()?.lowercase()?.takeIf { it in ALL } ?: INFO
}
