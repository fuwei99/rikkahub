package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
                you get a notice and read them with the `inbox` tool. Never sleep or poll waiting for agents.
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
                            put("description", "For send (追加指令) and spawn (派活): mail (default, delivered to inbox) or call (interrupt). Note: call behaves as mail in this build; interruption lands in a later phase.")
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
                            put("description", "auto (default: reports back when done) or manual")
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
                            put("description", "Block until the agent finishes (default false)")
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
                                            put("note", "call 打断本期未接线，已按 mail 入箱（子 agent 由收件箱唤醒）")
                                        }
                                        put("hint", "它的回报/提问会进你的收件箱（有未读时系统会提示，用 inbox 读）；用户可直接点开这个对话围观/插话；action=status 查进度，action=read 拉细节")
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
                                    result += "（call 打断本期未接线，已按 mail 入箱）"
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
 * `inbox`：查收自己的收件箱（方案 2026-08-07「多 Agent 通信内核」收敛设计 §3.2，落地 plan Step 4）。
 *
 * 返回全部未读全文，读取即标记已读（I4：全文只经此进入上下文一次）。
 * 主侧与子侧都挂：子 agent 的任务/指令、父 agent 的回报，全在各自的箱里。
 */
fun createInboxTool(
    inboxStore: AgentInboxStore,
    conversationId: Uuid,
): Tool = Tool(
    name = "inbox",
    description = """
        Read your own unread cross-conversation messages (subagent reports, questions, instructions, peer mail).
        Returns ALL unread mail in full and marks it read. This is the ONLY channel for messages from other
        agents — when a system notice says you have unread mail, call this tool to get it.
        Never sleep, idle-loop or poll waiting for other agents; new mail surfaces itself via notices.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {},
            required = emptyList(),
        )
    },
    execute = {
        val rows = inboxStore.takeUnread(conversationId)
        val payload = buildJsonObject {
            put("type", "inbox")
            put("unread", rows.size)
            put("messages", buildJsonArray {
                rows.forEach { row ->
                    add(buildJsonObject {
                        put("id", row.id)
                        put("from", row.senderTitle.ifBlank { row.senderId ?: row.source })
                        put("source", row.source)
                        put("kind", row.kind)
                        put("urgency", row.urgency)
                        put("received_at", row.createdAt)
                        put("body", row.body)
                    })
                }
            })
            if (rows.isEmpty()) put("note", "没有未读消息")
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    },
)

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
