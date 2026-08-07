package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.AgentSenderMetadata
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.data.ai.agent.AgentSenderRole

/**
 * user 气泡上的署名标签（方案 2026-08-07 §3.4）。
 *
 * 在 agent 会话里，"user 消息"很可能不是真人发的，而是上层 agent 派活/追加指令、
 * 或平级 agent 投递过来的。纯文本前缀会被提示注入伪造，所以身份只认
 * [AgentSenderMetadata]（结构化 part metadata），UI 据此渲染不同标签与配色。
 *
 * senderRole 为 null 或 human 时不渲染任何东西（普通对话零变化）。
 */
@Composable
fun AgentSenderLabel(sender: AgentSenderMetadata?) {
    val role = sender?.senderRole ?: return
    if (role == AgentSenderRole.HUMAN) return

    val (label, color) = when (role) {
        AgentSenderRole.MAIN_AGENT -> "上层 Agent" to Color(0xFF7E57C2)
        AgentSenderRole.SUB_AGENT -> "子 Agent 回报" to Color(0xFF26A69A)
        AgentSenderRole.PEER_AGENT -> "平级 Agent" to Color(0xFF42A5F5)
        AgentSenderRole.SYSTEM_REPORT -> "系统通告" to Color(0xFF8D6E63)
        else -> return
    }

    val kindLabel = when (sender.messageKind) {
        "task" -> "派活"
        "report" -> "回报"
        "ask" -> "提问"
        "instruction" -> "指令"
        "peer" -> "协作"
        "system" -> "系统"
        else -> null
    }

    Surface(
        color = color.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = HugeIcons.MagicWand01,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = buildString {
                    append(label)
                    if (kindLabel != null) append(" · $kindLabel")
                    sender.title?.takeIf { it.isNotBlank() }?.let { append(" · ${it.take(20)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
