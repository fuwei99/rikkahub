package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.ai.tools.local.SupervisionLockCoordinator
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.compose.koinInject

/**
 * 监督锁的申诉弹窗（PLAN_SUPERVISION_ADMIN_TOOL §5）。
 *
 * 先把语义摆清楚，免得日后有人把它当成「确认对话框」改坏：
 * **这不是征求同意**。倒计时走完、点「知道了」、提交申诉，三种结局都会落锁。
 * 输入框里的话只是投进发起 agent 的收件箱，解不解锁是它后面的二次决定。
 * 判定权不交给 LLM，就是为了避免「模型抽风/断网 → 监督静默失效」。
 *
 * 「再给一会儿」也不是免死金牌：它只是把落锁时刻往后推 N 秒，而且必须先写点
 * 东西才能点（不然就是白送时间），次数由 `appealMaxExtensions` 限死。
 */
@Composable
fun AppealDialogHost(
    coordinator: SupervisionLockCoordinator = koinInject(),
    eventBus: AppEventBus = koinInject(),
) {
    var pending by remember { mutableStateOf<AppEvent.SupervisionAppealPending?>(null) }

    LaunchedEffect(Unit) {
        eventBus.events.collect { event ->
            when (event) {
                is AppEvent.SupervisionAppealPending ->
                    // showDialog=false 是 schedule agent 无人值守发起：只走通知，不弹窗
                    if (event.showDialog) pending = event

                is AppEvent.SupervisionAppealResolved ->
                    if (pending?.appealId == event.appealId) pending = null

                else -> Unit
            }
        }
    }

    val current = pending ?: return
    var appealText by remember(current.appealId) { mutableStateOf("") }

    // 倒计时：deadlineAt 会被 extend 推后，所以 key 里带上它重启计时
    var remainingSeconds by remember(current.appealId) { mutableStateOf(0L) }
    LaunchedEffect(current.appealId, current.deadlineAt) {
        while (true) {
            val left = (current.deadlineAt - System.currentTimeMillis()) / 1000L
            remainingSeconds = left.coerceAtLeast(0L)
            if (left <= 0L) break
            delay(1000L)
        }
    }

    AlertDialog(
        // 不给点外面关：关了也照样锁，但至少让人看清发生了什么
        onDismissRequest = {},
        title = { Text("监督锁定：${current.targetLabel}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${remainingSeconds / 60}分${remainingSeconds % 60}秒后锁定生效",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (current.reason.isNotBlank()) {
                    Text(
                        text = "理由：${current.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = "锁定会照常生效——这里写的话不会阻止它，只会作为申诉材料送到发起方，" +
                        "由它决定要不要事后解锁。监督时段结束后自动放行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appealText,
                    onValueChange = { appealText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("申诉理由（可留空）") },
                    minLines = 2,
                    maxLines = 5,
                )
                if (current.extensionsLeft > 0) {
                    Text(
                        text = "写点东西才能申请延长时间（空手要时间等于白送）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    coordinator.resolveAppeal(current.appealId, appealText)
                    pending = null
                },
            ) { Text(if (appealText.isBlank()) "知道了" else "提交申诉") }
        },
        dismissButton = {
            if (current.extensionsLeft > 0) {
                TextButton(
                    enabled = appealText.isNotBlank() && remainingSeconds > 0L,
                    onClick = {
                        coordinator.extendAppeal(current.appealId)
                        // 协调器会重发 Pending 事件刷新 deadline / 剩余次数
                    },
                ) {
                    Text(
                        "再给 ${current.extensionSeconds / 60}分" +
                            "（还剩 ${current.extensionsLeft} 次）"
                    )
                }
            }
        },
    )
}
