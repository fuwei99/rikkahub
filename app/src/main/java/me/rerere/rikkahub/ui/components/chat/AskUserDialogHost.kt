package me.rerere.rikkahub.ui.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.common.android.ToolCallDebugLog
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * ask_user 全局弹窗（2026-08-10）。
 *
 * 原来 ask_user 只在对话流里内联渲染一个输入框：用户没滚到那条消息、或者
 * 提问来自另一个对话（子 agent / 定时任务查岗）时，那条生成就永久停在
 * Pending 上干等，表现为「卡死」。
 *
 * 这里挂在 RouteActivity 顶层，收到 [AppEvent.AskUserPending] 就强制弹一个
 * 不可点外部关闭的对话框，人不处理就走不掉；同时显示剩余倒计时，让人知道
 * 不理它也会自动放行（超时逻辑在 ChatService 里，这里只显示）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskUserDialogHost(
    onAnswer: (conversationId: Uuid, toolCallId: String, answer: String) -> Unit,
    eventBus: AppEventBus = koinInject(),
) {
    var pending by remember { mutableStateOf<AppEvent.AskUserPending?>(null) }

    LaunchedEffect(Unit) {
        eventBus.events.collect { event ->
            when (event) {
                is AppEvent.AskUserPending -> {
                    ToolCallDebugLog.askUser(
                        "DialogHost.receivePending",
                        "conv=${event.conversationId} toolCallId=${event.toolCallId} " +
                            "deadlineAt=${event.deadlineAt} argsLen=${event.argumentsJson.length} " +
                            "replacingPending=${pending?.toolCallId}",
                    )
                    pending = event
                }
                // 已在别处回答 / 超时兜底 / 生成被取消 → 关掉，别问一个作废的问题
                is AppEvent.AskUserResolved -> {
                    ToolCallDebugLog.askUser(
                        "DialogHost.receiveResolved",
                        "toolCallId=${event.toolCallId} currentPending=${pending?.toolCallId} " +
                            "willClose=${pending?.toolCallId == event.toolCallId}",
                    )
                    if (pending?.toolCallId == event.toolCallId) pending = null
                }

                else -> Unit
            }
        }
    }

    val current = pending ?: return

    val questions = remember(current.argumentsJson) {
        runCatching {
            JsonInstant.parseToJsonElement(current.argumentsJson)
                .jsonObject["questions"]?.jsonArray?.map { q ->
                    val obj = q.jsonObject
                    AskUserDialogQuestion(
                        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                        options = obj["options"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                        selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text",
                    )
                }.orEmpty()
        }.getOrElse { emptyList() }
    }

    // 解析不出问题就别挡着人（畸形 arguments 不该让 UI 卡住）
    if (questions.isEmpty()) {
        LaunchedEffect(current.toolCallId) {
            ToolCallDebugLog.askUser(
                "DialogHost.parseEmpty",
                "toolCallId=${current.toolCallId} questions parsed empty, dialog auto-dismissed. " +
                    "argsHead=${current.argumentsJson.take(200).replace("\n", "\\n")}",
            )
            pending = null
        }
        return
    }
    LaunchedEffect(current.toolCallId) {
        ToolCallDebugLog.askUserLazy("DialogHost.rendered") {
            "toolCallId=${current.toolCallId} questions=" +
                questions.joinToString { "${it.id}(${it.selectionType},opts=${it.options.size})" }
        }
    }

    val answers = remember(current.toolCallId) { mutableStateMapOf<String, String>() }
    val multiAnswers = remember(current.toolCallId) { mutableStateMapOf<String, MutableList<String>>() }

    // 倒计时：deadlineAt = 0 表示用户关掉了超时，永久等待
    var remainingSeconds by remember(current.toolCallId) { mutableStateOf(-1L) }
    LaunchedEffect(current.toolCallId, current.deadlineAt) {
        if (current.deadlineAt <= 0L) return@LaunchedEffect
        while (true) {
            val left = (current.deadlineAt - System.currentTimeMillis()) / 1000L
            remainingSeconds = left.coerceAtLeast(0L)
            if (left <= 0L) break
            delay(1000L)
        }
    }

    // 提交不再要求「每个问题都填」（2026-08-18）：
    // 原来 canSubmit 全填才亮，人在别的对话被弹窗拦住、又不想逐个作答时只能干等超时。
    // 空答案不是错误，直接作为「用户没回答这一项」交给模型判断即可。
    // 至少要有一项有内容才允许提交，纯空手就点「稍后回答」，语义更清楚。
    val canSubmit = questions.any { q ->
        when (q.selectionType) {
            "multi" -> !multiAnswers[q.id].isNullOrEmpty()
            else -> !answers[q.id].isNullOrBlank()
        }
    }

    AlertDialog(
        // 不给点外面关：这就是「不得不回复」的那个不得不
        onDismissRequest = {},
        title = { Text(text = "需要你回答") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (remainingSeconds >= 0L) {
                    Text(
                        text = "无人回答将在 ${remainingSeconds / 60}分${remainingSeconds % 60}秒后自动跳过",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = q.question, style = MaterialTheme.typography.bodyMedium)
                        when (q.selectionType) {
                            "single" -> FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                q.options.forEach { option ->
                                    FilterChip(
                                        selected = answers[q.id] == option,
                                        onClick = { answers[q.id] = option },
                                        label = { Text(option) },
                                    )
                                }
                            }

                            "multi" -> FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                q.options.forEach { option ->
                                    val selected = multiAnswers[q.id] ?: mutableStateListOf()
                                    FilterChip(
                                        selected = option in selected,
                                        onClick = {
                                            val list = multiAnswers.getOrPut(q.id) { mutableStateListOf() }
                                            if (option in list) list.remove(option) else list.add(option)
                                        },
                                        label = { Text(option) },
                                    )
                                }
                            }

                            else -> {
                                if (q.options.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        q.options.forEach { option ->
                                            FilterChip(
                                                selected = answers[q.id] == option,
                                                onClick = { answers[q.id] = option },
                                                label = { Text(option) },
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = answers[q.id] ?: "",
                                    onValueChange = { answers[q.id] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 1,
                                    maxLines = 4,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    val payload = buildJsonObject {
                        put("answers", buildJsonObject {
                            questions.forEach { q ->
                                val value = when (q.selectionType) {
                                    "multi" -> multiAnswers[q.id]?.joinToString(", ").orEmpty()
                                    else -> answers[q.id].orEmpty()
                                }
                                put(q.id, JsonPrimitive(value))
                            }
                        })
                    }
                    // 提交现场快照：看得出源头到底收集到了什么，
                    // 与 ChatService.approvalEnter 的 answerLen 一对，就能判定是
                    // 「UI 收空了」还是「传下去的路上丢了」。
                    ToolCallDebugLog.askUserLazy("DialogHost.submit") {
                        "conv=${current.conversationId} toolCallId=${current.toolCallId} " +
                            "payloadLen=${payload.toString().length} " +
                            "answers=" + questions.joinToString { q ->
                                val v = when (q.selectionType) {
                                    "multi" -> multiAnswers[q.id]?.joinToString("|").orEmpty()
                                    else -> answers[q.id].orEmpty()
                                }
                                "${q.id}=[${v.take(60)}]"
                            }
                    }
                    onAnswer(current.conversationId, current.toolCallId, payload.toString())
                    pending = null
                },
            ) { Text("提交") }
        },
        dismissButton = {
            // 「稍后在对话里回答」：只收弹窗，Pending 与超时都还在，
            // 想在消息流的内联输入框里慢慢答的人不至于被挡住。
            TextButton(onClick = {
                ToolCallDebugLog.askUser(
                    "DialogHost.later",
                    "toolCallId=${current.toolCallId} dismissed by user, pending kept",
                )
                pending = null
            }) { Text("稍后回答") }
        },
    )
}

private data class AskUserDialogQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text",
)
