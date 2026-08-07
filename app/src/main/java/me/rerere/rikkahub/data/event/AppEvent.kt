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
}
