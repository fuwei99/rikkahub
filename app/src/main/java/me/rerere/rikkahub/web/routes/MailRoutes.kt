package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentInboxStore
import me.rerere.rikkahub.data.ai.agent.AgentUrgency
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.WebMailInboxResponse
import me.rerere.rikkahub.web.dto.WebMailItemDto
import me.rerere.rikkahub.web.dto.WebMailSendRequest
import me.rerere.rikkahub.web.dto.WebMailSendResponse
import kotlin.uuid.Uuid

/**
 * 信箱接口（2026-09-22）。
 *
 * ## 一句话
 *
 * 给外部（workspace shell / 对端设备 / 脚本）：**按对话 id 收信、按对话 id 发信**。
 *
 * ```
 * GET  /api/mail/inbox?conversation_id=<uuid>&limit=50   —— 查任意对话的收件箱
 * POST /api/mail/send   {"to": "<uuid>", "message": "...", "from": "<uuid>?", "urgency": "mail"?}
 * ```
 *
 * ## 为什么要有它（以及为什么不是「又一个工具」）
 *
 * `agent_mail` 工具是**给对话里的模型**用的，它的 `read` 走 I4「读即已读」——
 * 读一次，那封未读信就永久离开工具视图。外部调用方要的是完全不同的东西：
 * **只读、可重复、能问任意一个对话**。这两个语义塞不进同一个口子，所以单开一条。
 *
 * ## 非破坏性是硬约束
 *
 * `GET /inbox` **绝不写 `read_at`**：
 * - 拉十次结果一样；
 * - 不会把某个对话的未读「偷偷吃掉」（那会让对话里的 agent 永远收不到信）；
 * - `unread` 字段只是快照，是「还剩几封没被消费」，不是「本次读了几封」。
 *
 * ## 历史 / 搜索怎么办
 *
 * 不提供历史查询接口 —— 每封信在入箱那一刻已经追加进明文归档
 * （`AgentInboxStore.enqueue` → `AgentMailArchive`），响应里的 `archive`
 * 字段就是那个文件的绝对路径。想回看、想全量搜，直接 `rg` 它。
 *
 * ## 鉴权与安全
 *
 * 与 `/api/shell*`、`/api/tools*` 共用**设备桥独立 Bearer**
 * （[requireDeviceBridgeToken]，`shellBridgeToken`，空即整条路 403）。
 *
 * ⚠️ 这条路等于把**任意对话的收件箱**交出去，且能**以任意身份向任意对话投信** ——
 * 后者是给对话里的 agent 下指令的通道。**只在可信局域网 / 隧道里开**，token 泄露立刻换。
 */
fun Route.mailRoutes(
    bridge: AgentBridge,
    inboxStore: AgentInboxStore,
    conversationRepo: ConversationRepository,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/mail") {

        /** 查：某个对话的全部来信（含已读），倒序。**非破坏性**。 */
        get("/inbox") {
            call.requireDeviceBridgeToken(advancedConfigStore, "mail")

            val target = requireUuid(call.request.queryParameters["conversation_id"], "conversation_id")
            if (!conversationRepo.existsConversationById(target)) {
                throw NotFoundException("对话不存在：$target")
            }

            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_MAIL_LIMIT
            val rows = inboxStore.listAll(target, limit)

            call.respond(
                HttpStatusCode.OK,
                WebMailInboxResponse(
                    conversationId = target.toString(),
                    // 注意：这是快照。本接口不会让它变小。
                    unread = inboxStore.countUnread(target),
                    count = rows.size,
                    archive = inboxStore.archivePath(target),
                    mails = rows.map { row ->
                        WebMailItemDto(
                            id = row.id,
                            from = row.senderTitle.ifBlank { row.senderId ?: row.source },
                            senderId = row.senderId,
                            senderTitle = row.senderTitle,
                            source = row.source,
                            kind = row.kind,
                            urgency = row.urgency,
                            receivedAt = row.createdAt,
                            read = row.readAt != null,
                            body = row.body,
                        )
                    },
                ),
            )
        }

        /** 发：往任意对话的收件箱投一封信。 */
        post("/send") {
            call.requireDeviceBridgeToken(advancedConfigStore, "mail")

            val request = call.receive<WebMailSendRequest>()
            if (request.message.isBlank()) {
                throw BadRequestException("message is required")
            }
            val target = requireUuid(request.to, "to")
            val sender = request.from?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { requireUuid(it, "from") }
                ?: EXTERNAL_SENDER

            // sendToConversation 用「返回错误文案」而不是抛异常表达失败（对话内调用要的是
            // 一段能进上下文的文字）。HTTP 侧照样把文案原样回给调用方，另外用一个布尔字段
            // 把「到底投进去没有」标出来，省得调用方去 match 中文前缀。
            val detail = bridge.sendToConversation(
                senderId = sender,
                targetId = target,
                message = request.message,
                urgency = AgentUrgency.parse(request.urgency),
                senderTitleOverride = request.senderName,
            )

            call.respond(
                HttpStatusCode.OK,
                WebMailSendResponse(
                    delivered = detail.startsWith(DELIVERY_OK_PREFIX),
                    detail = detail,
                    archive = inboxStore.archivePath(target),
                ),
            )
        }
    }
}

private const val DEFAULT_MAIL_LIMIT = 50

/** `AgentBridge.sendToConversation` 成功时的文案前缀（`已投递给对话 <id>`）。 */
private const val DELIVERY_OK_PREFIX = "已投递给对话"

/**
 * 外部调用方的哨兵身份。
 *
 * 它**不是**任何真实对话：收方看到的 `sender_id` 解析不出会话，`sender_title` 也空。
 * 所以强烈建议调用方显式传 `from`（并可用 `sender_name` 给个人话名）；
 * 哨兵只是「不传也能用」的兜底，让最简单的 `{"to": ..., "message": ...}` 不至于被拒。
 */
private val EXTERNAL_SENDER: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000e7")

private fun requireUuid(raw: String?, field: String): Uuid {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw BadRequestException("$field is required")
    return runCatching { Uuid.parse(value) }.getOrElse {
        throw BadRequestException("$field 不是合法 uuid: $value")
    }
}
