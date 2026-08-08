package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.db.entity.AgentSessionEntity
import me.rerere.rikkahub.ui.modifier.onClick
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 抽屉里的 Agent 组（方案 2026-08-07 §4.7-2/6）。
 *
 * 由 [AgentSessionDAO] **自绘驱动**，不走 Conversation 列表：
 * 归档只改 `agent_session.status`，抽屉按 assistantId/folder 查对话是看不到归档状态的，
 * 所以这里直接读表并过滤 archived，归档后才从抽屉消失。
 *
 * 只显示与「当前对话这棵树」（root_id）相关的 agent；没有就整块不渲染，
 * 普通用户的抽屉与改动前完全一致。
 */
@Composable
fun AgentSessionGroup(
    currentConversationId: Uuid,
    onOpenAgent: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    dao: AgentSessionDAO = koinInject(),
) {
    // 当前对话既可能是根主对话，也可能本身就是某个 agent 会话（此时看到的是同树的兄弟）
    val rootId = remember(currentConversationId) { currentConversationId.toString() }
    val flow = remember(rootId) { dao.getVisibleByRootFlow(rootId) }
    val sessions by flow.collectAsState(initial = emptyList())
    if (sessions.isEmpty()) return

    var expanded by remember { mutableStateOf(true) }
    val activeCount = sessions.count { it.status in AgentStatuses.ACTIVE }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onClick { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(HugeIcons.MagicWand01, null, modifier = Modifier.size(16.dp))
            Text(
                text = if (activeCount > 0) "Agents · $activeCount 运行中" else "Agents",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = sessions.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(HugeIcons.ArrowDown01, null, modifier = Modifier.size(14.dp))
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sessions.forEach { session ->
                    AgentSessionRow(
                        session = session,
                        onClick = {
                            runCatching { Uuid.parse(session.childId) }.getOrNull()?.let(onOpenAgent)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentSessionRow(
    session: AgentSessionEntity,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = agentStatusColor(session.status),
                shape = CircleShape,
                modifier = Modifier.size(8.dp),
                content = {},
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.taskBrief.ifBlank { session.templateId },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${session.templateId} · ${agentStatusLabel(session.status)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun agentStatusColor(status: String): Color = when (status) {
    AgentStatuses.RUNNING -> Color(0xFF4CAF50)
    AgentStatuses.WAITING_APPROVAL, AgentStatuses.FAILED, AgentStatuses.ERROR -> Color(0xFFF44336)
    AgentStatuses.WAITING_PARENT -> Color(0xFFFF9800)
    AgentStatuses.DONE -> Color(0xFF2196F3)
    else -> Color(0xFF9E9E9E)
}

private fun agentStatusLabel(status: String): String = when (status) {
    AgentStatuses.RUNNING -> "运行中"
    AgentStatuses.IDLE -> "空闲"
    AgentStatuses.WAITING_PARENT -> "等待回答"
    AgentStatuses.WAITING_APPROVAL -> "等待审批"
    AgentStatuses.DONE -> "已完成"
    AgentStatuses.FAILED -> "失败"
    AgentStatuses.ERROR -> "错误"
    AgentStatuses.STOPPED -> "已停止"
    else -> status
}
