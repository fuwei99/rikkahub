package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.db.entity.AgentSessionEntity
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 「对话即 Agent」的工具卡片（方案 2026-08-07 §4.7-1）。
 *
 * 与旧 `SubagentToolUI` 的关键区别：子 agent 是**真实对话**，
 * 所以不渲染内存 trace，而是读 `agent_session` 表的实时状态。
 */
object AgentToolUI : ToolUIRenderer {
    override val toolName: String = "agent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val action = context.agentAction()
        val session = context.agentSession()
        return when (action) {
            "spawn" -> {
                val status = session?.status ?: context.content.getStringContent("status")
                    ?: if (context.loading) AgentStatuses.RUNNING else "spawn"
                val brief = session?.taskBrief
                    ?: context.arguments.getStringContent("task")
                    ?: ""
                "Agent ${statusLabel(status)} · ${brief.take(48)}"
            }

            "status" -> "Agent 状态查询"
            "send" -> "Agent 追加指令"
            "read" -> "Agent 读取子对话"
            "review" -> "Agent 代审批"
            "stop" -> "Agent 停止"
            "archive" -> "Agent 归档"
            else -> "Agent $action"
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        val childId = context.childId()
        val session = context.agentSession()
        val error = context.content.getStringContent("error")

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (session != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(session.status)
                    Text(
                        text = statusLabel(session.status),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "· ${session.templateId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = session.taskBrief,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "depth ${session.depth} · 往返 ${session.turnsWithParent} · tokens ${session.totalTokens}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (session.lastSummary.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = session.lastSummary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            } else if (error == null) {
                Text(
                    text = context.content.getStringContent("result")
                        ?: context.content.getStringContent("hint")
                        ?: if (context.loading) "正在派活…" else "已执行",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (childId != null) {
                val navigator = LocalNavController.current
                FilledTonalButton(
                    onClick = { navigateToChatPage(navigator, childId) },
                ) {
                    Icon(HugeIcons.MagicWand01, null, modifier = Modifier.size(16.dp))
                    Text("  点开围观 / 插话")
                }
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val childId = context.childId()
        val session = context.agentSession()
        if (session == null) {
            DefaultToolPreview(context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Agent · ${statusLabel(session.status)}", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = session.taskBrief,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "模板 ${session.templateId} · depth ${session.depth} · 回报 ${session.reportMode}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "与父对话往返 ${session.turnsWithParent} · tokens ${session.totalTokens}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (session.lastSummary.isNotBlank()) {
                Text("最近回报", style = MaterialTheme.typography.titleSmall)
                Text(session.lastSummary, style = MaterialTheme.typography.bodySmall)
            }
            if (childId != null) {
                val navigator = LocalNavController.current
                FilledTonalButton(
                    onClick = {
                        onDismissRequest()
                        navigateToChatPage(navigator, childId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开这个 agent 的对话")
                }
            }
            Text(
                text = "这是一个真实对话：可以直接在里面发言纠正它，也能重生成/编辑它的消息。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private fun ToolUIContext.agentAction(): String =
        arguments.getStringContent("action") ?: "spawn"

    private fun ToolUIContext.childId(): Uuid? {
        val raw = content.getStringContent("conversation_id")
            ?: arguments.getStringContent("conversation_id")
            ?: return null
        return runCatching { Uuid.parse(raw.trim()) }.getOrNull()
    }

    @Composable
    private fun ToolUIContext.agentSession(): AgentSessionEntity? {
        val id = childId() ?: return null
        val dao: AgentSessionDAO = koinInject()
        val flow = remember(id) { dao.getByChildIdFlow(id.toString()) }
        return flow.collectAsState(initial = null).value
    }
}

@Composable
private fun StatusDot(status: String) {
    Surface(
        color = statusColor(status),
        shape = CircleShape,
        modifier = Modifier.size(8.dp),
        content = {},
    )
}

private fun statusColor(status: String): Color = when (status) {
    AgentStatuses.RUNNING -> Color(0xFF4CAF50)
    AgentStatuses.WAITING_APPROVAL -> Color(0xFFF44336)
    AgentStatuses.WAITING_PARENT -> Color(0xFFFF9800)
    AgentStatuses.DONE -> Color(0xFF2196F3)
    AgentStatuses.FAILED -> Color(0xFFF44336)
    AgentStatuses.ERROR -> Color(0xFFF44336)
    else -> Color(0xFF9E9E9E)
}

internal fun statusLabel(status: String): String = when (status) {
    AgentStatuses.RUNNING -> "运行中"
    AgentStatuses.IDLE -> "空闲"
    AgentStatuses.WAITING_PARENT -> "等待回答"
    AgentStatuses.WAITING_APPROVAL -> "等待审批"
    AgentStatuses.DONE -> "已完成"
    AgentStatuses.FAILED -> "失败"
    AgentStatuses.ERROR -> "错误"
    AgentStatuses.STOPPED -> "已停止"
    AgentStatuses.ARCHIVED -> "已归档"
    else -> status
}

/**
 * 子 agent 侧工具卡片（在**子对话内部**看到的）。
 *
 * 这三条不需要跳转按钮：用户已经在那个 agent 的对话里了。
 */
internal abstract class SubAgentSideToolUI(
    override val toolName: String,
    private val label: String,
    private val bodyKey: String,
) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    @Composable
    override fun title(context: ToolUIContext): String {
        val body = context.arguments.getStringContent(bodyKey)?.take(40).orEmpty()
        return if (body.isBlank()) label else "$label · $body"
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            context.arguments.getStringContent(bodyKey)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            val result = context.content.getStringContent("result")
                ?: context.content.getStringContent("error")
            if (result != null) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (context.content.getStringContent("error") != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

internal object AgentReportToolUI : SubAgentSideToolUI("agent_report", "回报给上层", "summary")

internal object AgentAskToolUI : SubAgentSideToolUI("agent_ask", "反问上层", "question")

internal object AgentSendToolUI : SubAgentSideToolUI("agent_send", "发给平级 agent", "message")

/**
 * `agent_mail`（2026-08-11 合并 inbox / send / await）的卡片。
 *
 * 标题按 action 分：读信显示未读封数、发信显示目标、等信显示是否超时。
 * 旧的 inbox / send / await 走默认渲染器（历史消息不掉渲染，也无需专门卡片）。
 */
internal object AgentMailToolUI : ToolUIRenderer {
    override val toolName: String = "agent_mail"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01

    private fun action(context: ToolUIContext): String =
        context.arguments.getStringContent("action") ?: "read"

    private fun mails(context: ToolUIContext): List<JsonElement> =
        (context.content?.jsonObjectOrNull?.get("messages") as? JsonArray) ?: emptyList()

    @Composable
    override fun title(context: ToolUIContext): String = when (action(context)) {
        "send" -> "跨对话发信"
        "await" -> if (context.content?.jsonObjectOrNull?.get("timed_out")
                ?.jsonPrimitiveOrNull?.booleanOrNull == true
        ) "等待来信 · 超时" else "等待来信"

        else -> {
            val unread = context.content?.jsonObjectOrNull?.get("unread")?.jsonPrimitiveOrNull?.intOrNull
            if (unread != null) "查收信箱 · $unread 封" else "查收信箱"
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean = true

    @Composable
    override fun Summary(context: ToolUIContext) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (action(context) == "send") {
                context.arguments.getStringContent("message")?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
            val error = context.content.getStringContent("error")
            val note = context.content.getStringContent("note")
            val result = context.content.getStringContent("result")
            val mails = mails(context)
            val summary = error ?: result ?: note
                ?: mails.mapNotNull { it.getStringContent("from") }
                    .takeIf { it.isNotEmpty() }?.joinToString(", ") { "来自 $it" }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
