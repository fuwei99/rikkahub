package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.agent.AgentInboxStore
import kotlin.uuid.Uuid

/**
 * 未读提示注入器（方案 2026-08-07「多 Agent 通信内核」收敛设计 §3.2，落地 plan Step 4）。
 *
 * 每个生成 step 都会重新 transform（GenerationHandler 循环内调用），因此：
 * - 生成中途新到的信，**下一个工具调用之前**模型就能看到提示；
 * - 信被 inbox 读掉后提示自动消失（countUnread 归零）。
 *
 * 纪律对齐：
 * - 只注入「有 N 封未读」的指针，**不塞信的正文**（I4：同一封信不得两次进上下文；
 *   正文只经 inbox 工具结果进入一次）；
 * - 注入消息是临时传输层消息，不落库（与 TimeReminderTransformer 同款先例），
 *   会话历史前缀字节稳定不受影响。
 */
class UnreadHintTransformer(
    private val conversationId: Uuid,
    private val inboxStore: AgentInboxStore,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val unread = runCatching { inboxStore.countUnread(conversationId) }.getOrDefault(0)
        if (unread <= 0) return messages
        val notice = UIMessage.user(
            "<inbox_notice>你有 $unread 封未读的跨对话消息（子 agent 回报/提问/指令等都在收件箱里）。" +
                "调用 agent_mail(action=read) 读取全文；在读到之前不要假设它们的内容。</inbox_notice>"
        )
        return messages + notice
    }
}
