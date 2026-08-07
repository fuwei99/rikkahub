package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentMessage
import me.rerere.rikkahub.data.ai.agent.AgentMessageKind
import me.rerere.rikkahub.data.ai.agent.AgentSenderRole
import me.rerere.rikkahub.data.ai.agent.AgentUrgency
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ConflictException
import me.rerere.rikkahub.web.ForbiddenException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.UnauthorizedException
import me.rerere.rikkahub.web.dto.ExternalDeliverRequest
import me.rerere.rikkahub.web.dto.ExternalDeliverResponse
import java.security.MessageDigest

/**
 * 外部投递接口（跨平台 Mail MCP 提醒入口，落地 plan 2026-08-08 Step 1/2）。
 *
 * 语义：只做「提醒」，不塞信正文（I4）——body 是一句 system 提示（如「你有 N 封新邮件，
 * 用 mail_read 读取」），目标对话用 inbox / mail_* 工具自行收信。
 *
 * - mode=queue     → urgency=MAIL：落库 + 等目标空闲再唤醒（dispatchWake 兜底判定）
 * - mode=interrupt → urgency=CALL：本轮行为=MAIL 的「立即唤醒」档（真抢占随内核期二接线）
 *
 * 鉴权：独立 Bearer key（Settings.externalDeliveryToken），与 web JWT 开关解耦；
 * 空 key = 接口整体关闭（403）。kind 强制 system（I9：系统开轮不得伪装 human/agent）。
 */
fun Route.externalDeliveryRoutes(
    agentBridge: AgentBridge,
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
) {
    route("/external") {
        post("/deliver") {
            val settings = settingsStore.settingsFlow.value
            val token = settings.externalDeliveryToken
            if (token.isBlank()) {
                throw ForbiddenException("外部投递接口未启用（externalDeliveryToken 为空）")
            }
            val header = call.request.headers[HttpHeaders.Authorization]
            val bearer = header?.removePrefix("Bearer ")?.trim()
            if (bearer.isNullOrEmpty() || !secureEquals(bearer, token)) {
                throw UnauthorizedException("无效的外部投递 token")
            }

            val mode = call.request.queryParameters["mode"] ?: "queue"
            val urgency = when (mode) {
                "queue" -> AgentUrgency.MAIL
                "interrupt" -> AgentUrgency.CALL
                else -> throw BadRequestException("mode 只支持 queue / interrupt")
            }

            val request = call.receive<ExternalDeliverRequest>()
            val conversationId = request.conversationId.toUuid("conversationId")
            if (conversationRepo.getConversationById(conversationId) == null) {
                throw NotFoundException("conversation not found")
            }
            if (request.kind != "system") {
                throw BadRequestException("外部投递只允许 kind=system（I9：不得伪装 human/agent）")
            }
            if (request.body.isBlank()) {
                throw BadRequestException("body is required")
            }

            val text = if (request.sender.isBlank()) request.body else "[${request.sender}] ${request.body}"

            val error = agentBridge.deliver(
                message = AgentMessage(
                    target = conversationId,
                    text = text,
                    kind = AgentMessageKind.SYSTEM,
                    senderRole = AgentSenderRole.SYSTEM,
                    senderConversationId = null,
                    senderTitle = request.title.ifBlank { null },
                ),
                urgency = urgency,
            )
            if (error != null) {
                throw ConflictException(error)
            }
            call.respond(HttpStatusCode.OK, ExternalDeliverResponse(accepted = true, mode = mode))
        }
    }
}

private fun secureEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
