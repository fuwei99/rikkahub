package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentTemplateManager
import me.rerere.rikkahub.data.db.entity.AgentInboxEntity
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File
import kotlin.uuid.Uuid

/**
 * 「对话即 Agent」的工具集（方案 2026-08-07 §4.4 / §4.5）。
 *
 * - 主 agent 侧：单工具 `agent` + action（spawn / send / status / read / review / stop / archive）；
 * - 子 agent 侧：`agent_report` / `agent_ask` / `agent_send`，投递目标受 parent + peers 限制。
 *
 * 所有限额校验都在 [AgentBridge] 里，工具层只做参数解析。
 */

/** 主 agent 侧：派活与管理 */
fun createAgentTools(
    bridge: AgentBridge,
    templateManager: SubagentTemplateManager,
    conversationId: Uuid,
    workspaceCwd: String?,
    /** 读子对话细节：复用 conversation_fetch 的截断逻辑 */
    fetchConversation: suspend (target: Uuid, mode: String, maxChars: Int) -> String,
): List<Tool> {
    val workspaceRoot = workspaceCwd?.let { File(it) }
    val templates = templateManager.listTemplates(workspaceRoot)
        .filter { it.visibility != "silent" }
    val templateDesc = if (templates.isNotEmpty()) {
        "Available agent templates: " + templates.joinToString(", ") { "${it.id} (${it.description})" }
    } else {
        "No agent templates are currently enabled."
    }

    return listOf(
        Tool(
            name = "agent",
            description = """
                Delegate work to sub-agents. Each sub-agent runs in its own REAL conversation the user can open,
                watch and interrupt — so prefer it over doing long multi-step grunt work inline.
                action=spawn creates one and returns its conversation_id (async by default);
                use send/status/read to steer it, review to approve its tool calls, stop/archive to finish.

                Messaging is mailbox-based: every cross-conversation message lands in the recipient's inbox
                first (never lost, never blocks the sender). Reports/questions arrive as unread mail —
                you get a notice and read them with `agent_mail(action=read)`; wait for them with
                `agent_mail(action=await)`. Never sleep or poll waiting for agents.
                $templateDesc
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("spawn")
                                add("send")
                                add("status")
                                add("read")
                                add("review")
                                add("stop")
                                add("archive")
                            })
                            put("description", "spawn | send | status | read | review | stop | archive")
                        })
                        put("template", buildJsonObject {
                            put("type", "string")
                            put("description", "Template id, required for spawn")
                        })
                        put("task", buildJsonObject {
                            put("type", "string")
                            put("description", "The task for the sub-agent, required for spawn")
                        })
                        put("context", buildJsonObject { put("type", "string") })
                        put("conversation_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target agent conversation id, for send/read/review/stop/archive")
                        })
                        put("conversation_ids", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "For status")
                        })
                        put("message", buildJsonObject {
                            put("type", "string")
                            put("description", "For send: extra instruction / correction / answer to its question")
                        })
                        put("urgency", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("mail")
                                add("call")
                            })
                            put("description", "For send (追加指令) and spawn (派活): mail (default, delivered to inbox) or call (interrupt: 抢占式打断目标当前轮，需模板打断权 + 人类总闸；无权限自动退化为 mail).")
                        })
                        put("tools", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("description", "For read: full (default) or tail")
                        })
                        put("max_chars", buildJsonObject { put("type", "integer") })
                        put("max_steps", buildJsonObject { put("type", "integer") })
                        put("timeout_minutes", buildJsonObject { put("type", "integer") })
                        put("max_total_tokens", buildJsonObject { put("type", "integer") })
                        put("report_mode", buildJsonObject {
                            put("type", "string")
                            put("description", "兼容字段（2026-08-14 起已统一）：所有模式都必须显式调用 agent_report 汇报结果，不再自动回报最后一句")
                        })
                        put("model_uuid", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional Settings model UUID; defaults to this conversation's model")
                        })
                        put("peers", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "Agent conversation ids allowed to message each other")
                        })
                        put("wait", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Block until the agent's first turn completes (default false); the agent still must call agent_report to be considered done")
                        })
                        put("tool_call_id", buildJsonObject { put("type", "string") })
                        put("approved", buildJsonObject { put("type", "boolean") })
                        put("reason", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("action"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "spawn"

                fun target(): Uuid? = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }

                val payload: JsonElement = when (action) {
                    "spawn" -> {
                        val template = obj["template"]?.jsonPrimitive?.contentOrNull
                            ?: obj["template_id"]?.jsonPrimitive?.contentOrNull
                        val task = obj["task"]?.jsonPrimitive?.contentOrNull
                        when {
                            template.isNullOrBlank() -> errorJson("template is required. $templateDesc")
                            task.isNullOrBlank() -> errorJson("task is required")
                            else -> {
                                // 派活也吃 urgency（落地 plan Step 7）。silent 仅系统内部用，
                                // 工具层挡掉：schema 只暴露 mail/call，恶意/误传 silent 一律回落 mail，
                                // 否则任务静默入箱永不唤醒子 agent，等于死信。
                                val rawUrgency = AgentUrgency.parse(obj["urgency"]?.jsonPrimitive?.contentOrNull)
                                val urgency = if (rawUrgency == AgentUrgency.SILENT) AgentUrgency.MAIL else rawUrgency
                                val result = bridge.spawn(
                                    parentId = conversationId,
                                    templateId = template,
                                    task = task,
                                    context = obj["context"]?.jsonPrimitive?.contentOrNull,
                                    overrides = SpawnOverrides(
                                        tools = parseStringList(obj["tools"]).takeIf { it.isNotEmpty() },
                                        maxSteps = obj["max_steps"]?.jsonPrimitive?.intOrNull,
                                        timeoutMinutes = obj["timeout_minutes"]?.jsonPrimitive?.intOrNull,
                                        maxTotalTokens = obj["max_total_tokens"]?.jsonPrimitive?.intOrNull,
                                        reportMode = obj["report_mode"]?.jsonPrimitive?.contentOrNull,
                                        peers = parseStringList(obj["peers"]),
                                        modelUuid = obj["model_uuid"]?.jsonPrimitive?.contentOrNull
                                            ?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                                        wait = obj["wait"]?.jsonPrimitive?.booleanOrNull == true,
                                        urgency = urgency,
                                    ),
                                )
                                if (!result.ok) {
                                    errorJson(result.error ?: "spawn failed")
                                } else {
                                    buildJsonObject {
                                        put("type", "agent_spawn")
                                        put("conversation_id", result.conversationId.toString())
                                        put("title", result.title)
                                        put("status", result.status)
                                        put("template", template)
                                        if (result.downgraded.isNotEmpty()) {
                                            put("downgraded", buildJsonArray {
                                                result.downgraded.forEach { add(it) }
                                            })
                                        }
                                        if (urgency == AgentUrgency.CALL) {
                                            put("note", "call 已按抢占投递（若目标正被真人占用或无打断权，则自动退化为 mail 入箱）")
                                        }
                                        put("hint", "它的回报/提问会进你的收件箱（有未读时系统会提示，用 agent_mail(action=read) 读）；用户可直接点开这个对话围观/插话；action=status 查进度，action=read 拉细节")
                                    }
                                }
                            }
                        }
                    }

                    "send" -> {
                        val id = target()
                        val message = obj["message"]?.jsonPrimitive?.contentOrNull
                        val urgency = AgentUrgency.parse(obj["urgency"]?.jsonPrimitive?.contentOrNull)
                        when {
                            id == null -> errorJson("conversation_id is required")
                            message.isNullOrBlank() -> errorJson("message is required")
                            else -> {
                                var result = bridge.sendToChild(conversationId, id, message, urgency)
                                if (urgency == AgentUrgency.CALL && result.startsWith("已投递")) {
                                    result += "（call 已按抢占投递；无权限或目标被真人占用时自动退化为 mail 入箱）"
                                }
                                resultJson("agent_send", result)
                            }
                        }
                    }

                    "status" -> {
                        val ids = parseStringList(obj["conversation_ids"])
                            .ifEmpty { listOfNotNull(obj["conversation_id"]?.jsonPrimitive?.contentOrNull) }
                            .mapNotNull { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
                        val infos = bridge.status(ids)
                        buildJsonObject {
                            put("type", "agent_status")
                            put("agents", buildJsonArray {
                                infos.forEach { info ->
                                    add(buildJsonObject {
                                        put("conversation_id", info.conversationId.toString())
                                        put("template", info.templateId)
                                        put("task", info.taskBrief)
                                        put("status", info.status)
                                        put("depth", info.depth)
                                        put("messages", info.messageCount)
                                        put("total_tokens", info.totalTokens)
                                        put("turns_with_parent", info.turnsWithParent)
                                        put("pending_approval", info.hasPendingApproval)
                                        put("last_summary", info.lastSummary)
                                    })
                                }
                            })
                            if (infos.isEmpty()) put("note", "没有找到对应的 agent 会话（可能已归档或不是本机派出的）")
                        }
                    }

                    "read" -> {
                        val id = target() ?: return@Tool listOf(UIMessagePart.Text(errorText("conversation_id is required")))
                        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull ?: "full"
                        val maxChars = (obj["max_chars"]?.jsonPrimitive?.intOrNull ?: 12_000).coerceIn(500, 50_000)
                        return@Tool listOf(UIMessagePart.Text(fetchConversation(id, mode, maxChars)))
                    }

                    "review" -> {
                        val id = target()
                        val toolCallId = obj["tool_call_id"]?.jsonPrimitive?.contentOrNull
                        when {
                            id == null -> errorJson("conversation_id is required")
                            toolCallId.isNullOrBlank() -> errorJson("tool_call_id is required")
                            else -> resultJson(
                                "agent_review",
                                bridge.review(
                                    callerId = conversationId,
                                    childId = id,
                                    toolCallId = toolCallId,
                                    approved = obj["approved"]?.jsonPrimitive?.booleanOrNull == true,
                                    reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                                ),
                            )
                        }
                    }

                    "stop" -> {
                        val id = target()
                        if (id == null) errorJson("conversation_id is required")
                        else resultJson("agent_stop", bridge.stop(id, obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""))
                    }

                    "archive" -> {
                        val id = target()
                        if (id == null) errorJson("conversation_id is required")
                        else resultJson("agent_archive", bridge.archive(id))
                    }

                    else -> errorJson("Unknown agent action: $action")
                }
                listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
            },
        )
    )
}

/**
 * 子 agent 侧工具：只能往 parent + peers 投递。
 *
 * report / ask 执行后 bridge 会主动结束子对话本轮（`finishPendingTools`），
 * 不能指望模型自觉收尾。
 */
fun createSubAgentSideTools(
    bridge: AgentBridge,
    conversationId: Uuid,
    allowPeerMessaging: Boolean,
): List<Tool> = buildList {
    add(
        Tool(
            name = "agent_report",
            description = """
                Report your result back to the agent that delegated this task to you.
                This summary is the ONLY thing it sees by default, so make it complete but concise:
                what you did, key findings/conclusions, and absolute paths of files you touched.
                Set done=true when the task is finished (or definitively cannot be done).
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("summary", buildJsonObject { put("type", "string") })
                        put("done", buildJsonObject {
                            put("type", "boolean")
                            put("description", "true = task finished; false = progress update, keep working after reply")
                        })
                    },
                    required = listOf("summary"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
                if (summary.isNullOrBlank()) {
                    listOf(UIMessagePart.Text(errorText("summary is required")))
                } else {
                    val message = bridge.reportToParent(
                        childId = conversationId,
                        summary = summary,
                        done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: true,
                    )
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(resultJson("agent_report", message))))
                }
            },
        )
    )
    add(
        Tool(
            name = "agent_ask",
            description = """
                Ask the agent that delegated this task a question when you are genuinely blocked by ambiguity.
                This ends your current turn; you will be resumed automatically when the answer arrives.
                Do NOT use it for things you can figure out from the workspace yourself.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("question", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("question"),
                )
            },
            execute = { args ->
                val question = args.jsonObject["question"]?.jsonPrimitive?.contentOrNull
                if (question.isNullOrBlank()) {
                    listOf(UIMessagePart.Text(errorText("question is required")))
                } else {
                    val message = bridge.askParent(conversationId, question)
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(resultJson("agent_ask", message))))
                }
            },
        )
    )
    if (allowPeerMessaging) {
        add(
            Tool(
                name = "agent_send",
                description = "Send a message to a peer agent working on the same job (only ids in your peers whitelist).",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("peer_id", buildJsonObject { put("type", "string") })
                            put("message", buildJsonObject { put("type", "string") })
                        },
                        required = listOf("peer_id", "message"),
                    )
                },
                execute = { args ->
                    val obj = args.jsonObject
                    val peerId = obj["peer_id"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull
                    when {
                        peerId == null -> listOf(UIMessagePart.Text(errorText("peer_id is invalid")))
                        message.isNullOrBlank() -> listOf(UIMessagePart.Text(errorText("message is required")))
                        else -> listOf(
                            UIMessagePart.Text(
                                JsonInstantPretty.encodeToString(
                                    resultJson("agent_send", bridge.sendToPeer(conversationId, peerId, message))
                                )
                            )
                        )
                    }
                },
            )
        )
    }
}

/**
 * `agent_mail`：跨对话通信单工具（2026-08-11 合并 inbox / send / await）。
 *
 * 三个 action 本来就是同一件事的三面（收信 / 发信 / 等信），却各挂一个工具、各交一份
 * description，白烧 token；且 inbox 与 send 在设置里同开同关。现在合并为
 * `action = read | send | await`：
 * - read：取全部未读并标记已读（原 `inbox`，I4：全文只经此进上下文一次）；
 * - send：按对话 id 投递到任意对话的收件箱（原 `send`）；
 * - await：阻塞等信 + 攒批合并返回（原 `await`，唯一合法的等待方式，I7 禁 sleep/轮询）。
 *
 * action 可见性按开关裁剪：[allowSend] / [allowAwait] 关闭时不写进 enum 与描述，
 * 免得模型去调一个会被拒的动作。
 */
fun createAgentMailTool(
    inboxStore: AgentInboxStore,
    bridge: AgentBridge,
    conversationId: Uuid,
    allowRead: Boolean,
    allowSend: Boolean,
    allowAwait: Boolean,
): Tool {
    val actions = buildList {
        if (allowRead) add("read")
        if (allowSend) add("send")
        if (allowAwait) add("await")
    }
    return Tool(
        name = "agent_mail",
        description = buildString {
            appendLine("Cross-conversation mailbox. All messages between conversations/agents go through here.")
            appendLine()
            if (allowRead) {
                appendLine("action=read — read ALL your unread mail in full (subagent reports, questions, instructions,")
                appendLine("peer mail) and mark it read. This is the ONLY channel for messages from other agents:")
                appendLine("when a system notice says you have unread mail, call this.")
            }
            if (allowSend) {
                appendLine("action=send — deliver a message to another conversation's inbox by conversation_id.")
                appendLine("The recipient gets an unread notice (and a wake-up round if idle); it only receives it")
                appendLine("if that conversation has its mailbox enabled. For delegating work use `agent` spawn instead.")
            }
            if (allowAwait) {
                appendLine("action=await — BLOCK until matching mail arrives, then return it as one batch (mails")
                appendLine("arriving close together are merged, not delivered one-by-one). Use it after `agent` spawn")
                appendLine("to collect results in the same turn. Timeout returns whatever already arrived with")
                appendLine("timed_out=true — nothing is lost. This is the ONLY allowed way to wait for agents.")
            }
            appendLine()
            append("Never sleep or poll-loop waiting for other agents; new mail surfaces itself via notices.")
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { actions.forEach { add(it) } })
                        put("description", actions.joinToString(" | "))
                    })
                    if (allowSend) {
                        put("conversation_id", buildJsonObject {
                            put("type", "string")
                            put("description", "send: target conversation id (recipient's inbox).")
                        })
                        put("message", buildJsonObject {
                            put("type", "string")
                            put("description", "send: the message body.")
                        })
                        put("urgency", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("mail")
                                add("call")
                            })
                            put(
                                "description",
                                "send: mail (default, delivered to inbox) or call (interrupt: 抢占式打断目标当前轮，需打断权 + 人类总闸；无权限自动退化为 mail)."
                            )
                        })
                    }
                    if (allowAwait) {
                        put("from", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "await: sender conversation ids to wait for. Empty = any sender.")
                        })
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add("any")
                                add("all")
                            })
                            put(
                                "description",
                                "await: any = return as soon as one matches (default); all = wait until every listed sender arrived."
                            )
                        })
                        put("timeout_seconds", buildJsonObject {
                            put("type", "integer")
                            put("description", "await: max wait in seconds (default from communication settings).")
                        })
                    }
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val action = obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase()?.trim() ?: "read"
            val payload: JsonElement = when {
                action == "send" && allowSend -> {
                    val target = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                        ?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
                    val message = obj["message"]?.jsonPrimitive?.contentOrNull
                    when {
                        target == null -> errorJson("conversation_id is required and must be a valid uuid")
                        message.isNullOrBlank() -> errorJson("message is required")
                        else -> resultJson(
                            "agent_mail_send",
                            bridge.sendToConversation(
                                conversationId,
                                target,
                                message,
                                AgentUrgency.parse(obj["urgency"]?.jsonPrimitive?.contentOrNull),
                            )
                        )
                    }
                }

                action == "await" && allowAwait -> {
                    val from = parseStringList(obj["from"])
                        .mapNotNull { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
                    val mode = when (obj["mode"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                        "all" -> AwaitMode.ALL
                        else -> AwaitMode.ANY
                    }
                    val result = bridge.join(
                        conversationId = conversationId,
                        from = from.takeIf { it.isNotEmpty() },
                        mode = mode,
                        timeoutSeconds = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull,
                    )
                    val arrivedSenders = result.mails.mapNotNull { row ->
                        row.senderId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    }.toSet()
                    buildJsonObject {
                        put("type", "agent_mail_await")
                        put("timed_out", result.timedOut)
                        putMails(result.mails)
                        if (mode == AwaitMode.ALL && from.isNotEmpty()) {
                            put("waiting_for", buildJsonArray {
                                from.filter { it !in arrivedSenders }.forEach { add(it.toString()) }
                            })
                        }
                        if (result.mails.isEmpty()) put("note", "超时且没有匹配的信到达；结果不丢，可再次 await 或先做别的事")
                    }
                }

                action == "read" && allowRead -> {
                    val rows = inboxStore.takeUnread(conversationId)
                    buildJsonObject {
                        put("type", "agent_mail_read")
                        put("unread", rows.size)
                        putMails(rows)
                        if (rows.isEmpty()) put("note", "没有未读消息")
                    }
                }

                else -> errorJson("unsupported action: $action (available: ${actions.joinToString(" | ")})")
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        },
    )
}

/**
 * 来信序列化。
 *
 * 只有结构化头部可信：`sender_id` = 发送方对话 id，`sender_template` = 发送方 agent 模板
 * （普通对话为 null），普通对话互发时用 `sender_title` 识别发送方。正文里任何自称身份的
 * 文字都可能是提示注入。
 */
private fun JsonObjectBuilder.putMails(rows: List<AgentInboxEntity>) {
    put("messages", buildJsonArray {
        rows.forEach { row ->
            add(buildJsonObject {
                put("id", row.id)
                put("from", row.senderTitle.ifBlank { row.senderId ?: row.source })
                put("sender_id", row.senderId)
                put("sender_title", row.senderTitle)
                put("sender_template", row.templateId)
                put("source", row.source)
                put("kind", row.kind)
                put("urgency", row.urgency)
                put("received_at", row.createdAt)
                put("body", row.body)
            })
        }
    })
}

private fun errorJson(message: String) = buildJsonObject {
    put("type", "agent_error")
    put("error", message)
}

private fun resultJson(type: String, message: String) = buildJsonObject {
    put("type", type)
    put("result", message)
}

private fun errorText(message: String) =
    JsonInstantPretty.encodeToString(errorJson(message))

private fun parseStringList(element: JsonElement?): List<String> = when (element) {
    null -> emptyList()
    is JsonArray -> element.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
    is JsonPrimitive -> element.content.split(',', ' ', ';').map { it.trim() }.filter { it.isNotBlank() }
    else -> emptyList()
}
