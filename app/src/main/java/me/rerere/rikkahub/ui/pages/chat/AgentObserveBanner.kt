package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * agent 会话的「观察态」横幅（方案 2026-08-07 §4.7-3）。
 *
 * 用户点进子对话时得知道：这不是我和助手的普通聊天，而是某个 agent 在干活，
 * 并且要能一键回到派活的那个主对话。非 agent 对话下整块不渲染（普通聊天零变化）。
 *
 * 用户仍可直接在这里发言（身份 human），这就是「盯着它干活并随时纠正」。
 */
@Composable
fun AgentObserveBanner(
    conversationId: Uuid,
    modifier: Modifier = Modifier,
    dao: AgentSessionDAO = koinInject(),
) {
    val flow = remember(conversationId) { dao.getByChildIdFlow(conversationId.toString()) }
    val session by flow.collectAsState(initial = null)
    val row = session ?: return
    val navigator = LocalNavController.current
    val parentId = remember(row.parentId) { runCatching { Uuid.parse(row.parentId) }.getOrNull() }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(HugeIcons.MagicWand01, null, modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "◆ Agent · ${row.templateId} · ${agentBannerStatus(row.status)}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.taskBrief,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (parentId != null) {
                TextButton(onClick = { navigateToChatPage(navigator, parentId) }) {
                    Text("回到主对话", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun agentBannerStatus(status: String): String = when (status) {
    "running" -> "运行中"
    "idle" -> "空闲"
    "waiting_parent" -> "等待回答"
    "waiting_approval" -> "等待你授权"
    "done" -> "已完成"
    "failed" -> "失败"
    "error" -> "错误"
    "stopped" -> "已停止"
    "archived" -> "已归档"
    else -> status
}
