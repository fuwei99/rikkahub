package me.rerere.rikkahub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentProfile
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_VIRTUAL_PARENT_ID
import me.rerere.rikkahub.data.ai.prompts.AutoCompressOverride
import me.rerere.rikkahub.data.ai.schedule.ScheduleAction
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentManager
import me.rerere.rikkahub.data.ai.schedule.ScheduleAgentScheduler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.model.MemoryGraphBinding
import me.rerere.rikkahub.data.model.MemoryOptions
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.core.SyncAdvancedConfigStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.ForbiddenException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.web.dto.AgentSessionAdminDto
import me.rerere.rikkahub.web.dto.OkResponse
import me.rerere.rikkahub.web.dto.toDto
import kotlin.uuid.Uuid

@Serializable
private data class BooleanPatch(val enabled: Boolean)

/**
 * Device-bridge admin API.
 *
 * Why this exists: scheduled_process scripts live in the workspace and naturally carry only
 * the device-bridge Bearer token. They should be able to drive conversations/agent sessions
 * without needing the Web JWT. This route is therefore deliberately placed outside the JWT
 * `authenticate` block and guarded by `requireDeviceBridgeToken`.
 */
fun Route.adminRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    agentBridge: AgentBridge,
    agentSessionDao: AgentSessionDAO,
    scheduleManager: ScheduleAgentManager,
    scheduler: ScheduleAgentScheduler,
    advancedConfigStore: SyncAdvancedConfigStore,
) {
    route("/admin") {
        suspend fun requireAdmin() {
            call.requireDeviceBridgeToken(advancedConfigStore, "admin")
        }

        suspend fun requireScheduleAction(conversationId: Uuid, action: ScheduleAction) {
            chatService.scheduleProtectionBlockReason(conversationId, action)?.let {
                throw ForbiddenException(it)
            }
        }

        suspend fun getConversationForAdmin(id: Uuid) = run {
            conversationRepo.getConversationById(id)
                ?: throw NotFoundException("Conversation not found")
            chatService.initializeConversation(id)
            chatService.getConversationFlow(id).value
        }

        suspend fun validateModelId(raw: String?, field: String): Uuid? {
            if (raw == null) return null
            val uuid = raw.toUuid(field)
            val exists = settingsStore.settingsFlow.value.providers.any { provider ->
                provider.models.any { model -> model.id == uuid }
            }
            if (!exists) throw BadRequestException("$field not found in settings: $raw")
            return uuid
        }

        fun decodeString(element: JsonElement): String? =
            if (element is JsonNull) null else element.jsonPrimitive.content

        fun decodeUuid(element: JsonElement, field: String): Uuid? =
            decodeString(element)?.toUuid(field)

        inline fun <reified T> decode(element: JsonElement, field: String): T? {
            if (element is JsonNull) return null
            return runCatching { JsonInstant.decodeFromJsonElement<T>(element) }
                .getOrElse { throw BadRequestException("Invalid $field: $element") }
        }

        fun decodeLocalTools(element: JsonElement, field: String) =
            if (element is JsonNull) null else decode<List<String>>(element, field)?.map { name ->
                runCatching { JsonInstant.decodeFromString<me.rerere.rikkahub.data.ai.tools.local.LocalToolOption>("\"$name\"") }
                    .getOrElse { throw BadRequestException("Invalid $field entry: $name") }
            }

        fun decodeReasoning(element: JsonElement, field: String) =
            if (element is JsonNull) null else decodeString(element)?.let { raw ->
                runCatching { JsonInstant.decodeFromString<me.rerere.ai.core.ReasoningLevel>("\"$raw\"") }
                    .getOrElse { throw BadRequestException("Invalid $field: $raw") }
            }

        // ---------------------------------------------------------------- conversation config

        /**
         * POST /api/admin/conversations/{id}/config
         *
         * Body is a raw JSON object. Absent key = do not touch; explicit null = clear/inherit.
         * This preserves RikkaHub's tri-state semantics (null = inherit, empty = all off).
         */
        post("/conversations/{id}/config") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            requireScheduleAction(uuid, ScheduleAction.EDIT_CONFIG)
            val patch = call.receive<JsonObject>()
            val base = getConversationForAdmin(uuid)

            val updated = base.copy(
                modelId = if ("modelId" in patch) validateModelId(decodeString(patch["modelId"]!!), "modelId") else base.modelId,
                workspaceId = if ("workspaceId" in patch) decodeUuid(patch["workspaceId"]!!, "workspaceId") else base.workspaceId,
                workspaceCwd = if ("workspaceCwd" in patch) decodeString(patch["workspaceCwd"]!!) else base.workspaceCwd,
                customSystemPrompt = if ("customSystemPrompt" in patch) decodeString(patch["customSystemPrompt"]!!) else base.customSystemPrompt,
                reasoningLevel = if ("reasoningLevel" in patch) decodeReasoning(patch["reasoningLevel"]!!, "reasoningLevel") else base.reasoningLevel,
                enableWebSearch = if ("enableWebSearch" in patch) decode<Boolean>(patch["enableWebSearch"]!!, "enableWebSearch") else base.enableWebSearch,
                enabledSkills = if ("enabledSkills" in patch) decode<Set<String>>(patch["enabledSkills"]!!, "enabledSkills") else base.enabledSkills,
                localTools = if ("localTools" in patch) decodeLocalTools(patch["localTools"]!!, "localTools") else base.localTools,
                workspaceTools = if ("workspaceTools" in patch) decode<Set<String>>(patch["workspaceTools"]!!, "workspaceTools") else base.workspaceTools,
                mcpTools = if ("mcpTools" in patch) decode<Set<String>>(patch["mcpTools"]!!, "mcpTools") else base.mcpTools,
                mcpServers = if ("mcpServers" in patch) {
                    val nodes = patch["mcpServers"]!!
                    if (nodes is JsonNull) null
                    else decode<List<String>>(nodes, "mcpServers")?.map { it.toUuid("mcpServers") }?.toSet()
                } else base.mcpServers,
                leasedTools = if ("leasedTools" in patch) decode<Set<String>>(patch["leasedTools"]!!, "leasedTools") else base.leasedTools,
                memoryOptions = if ("memoryOptions" in patch) decode<MemoryOptions>(patch["memoryOptions"]!!, "memoryOptions") else base.memoryOptions,
                memoryGraphBindings = if ("memoryGraphBindings" in patch) decode<List<MemoryGraphBinding>>(patch["memoryGraphBindings"]!!, "memoryGraphBindings") else base.memoryGraphBindings,
                autoCompressOverride = if ("autoCompressOverride" in patch) decode<AutoCompressOverride>(patch["autoCompressOverride"]!!, "autoCompressOverride") else base.autoCompressOverride,
            )

            chatService.saveConversation(uuid, updated)
            val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
            call.respond(HttpStatusCode.OK, updated.toDto(isGenerating))
        }

        // ---------------------------------------------------------------- message admin wrappers

        post("/conversations/{id}/messages") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            val body = call.receive<JsonObject>()
            val parts = body["parts"]?.let { decode<List<UIMessagePart>>(it, "parts") }
                ?: throw BadRequestException("parts is required")
            getConversationForAdmin(uuid)
            chatService.sendMessage(uuid, parts, answer = true)
            call.respond(HttpStatusCode.Accepted, OkResponse(id = uuid.toString()))
        }

        post("/conversations/{id}/messages/{messageId}/edit") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            requireScheduleAction(uuid, ScheduleAction.EDIT_MESSAGE)
            val body = call.receive<JsonObject>()
            val parts = body["parts"]?.let { decode<List<UIMessagePart>>(it, "parts") }
                ?: throw BadRequestException("parts is required")
            getConversationForAdmin(uuid)
            chatService.editMessage(uuid, messageId, parts)
            call.respond(HttpStatusCode.Accepted, OkResponse(id = messageId.toString()))
        }

        post("/conversations/{id}/fork") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            requireScheduleAction(uuid, ScheduleAction.FORK)
            val body = call.receive<JsonObject>()
            val messageId = body["messageId"]?.let { decodeUuid(it, "messageId") }
                ?: throw BadRequestException("messageId is required")
            getConversationForAdmin(uuid)
            val fork = chatService.forkConversationAtMessage(uuid, messageId)
            call.respond(HttpStatusCode.Created, OkResponse(id = fork.id.toString()))
        }

        delete("/conversations/{id}/messages/{messageId}") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            requireScheduleAction(uuid, ScheduleAction.DELETE_MESSAGE)
            getConversationForAdmin(uuid)
            chatService.deleteMessage(uuid, messageId)
            call.respond(HttpStatusCode.OK, OkResponse(id = messageId.toString()))
        }

        post("/conversations/{id}/nodes/{nodeId}/select") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            val nodeId = call.parameters["nodeId"].toUuid("node id")
            val body = call.receive<JsonObject>()
            val selectIndex = body["selectIndex"]?.let { decode<Int>(it, "selectIndex") }
                ?: throw BadRequestException("selectIndex is required")
            getConversationForAdmin(uuid)
            chatService.selectMessageNode(uuid, nodeId, selectIndex)
            call.respond(HttpStatusCode.OK, OkResponse(id = nodeId.toString()))
        }

        post("/conversations/{id}/regenerate") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            requireScheduleAction(uuid, ScheduleAction.REGENERATE)
            val body = call.receive<JsonObject>()
            val messageId = body["messageId"]?.let { decodeUuid(it, "messageId") }
                ?: throw BadRequestException("messageId is required")
            val conversation = getConversationForAdmin(uuid)
            val node = conversation.getMessageNodeByMessageId(messageId)
                ?: throw NotFoundException("Message not found")
            val message = node.messages.firstOrNull { it.id == messageId }
                ?: throw NotFoundException("Message not found")
            chatService.regenerateAtMessage(uuid, message)
            call.respond(HttpStatusCode.Accepted, OkResponse(id = messageId.toString()))
        }

        post("/conversations/{id}/stop") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            requireScheduleAction(uuid, ScheduleAction.CANCEL)
            chatService.stopGeneration(uuid)
            call.respond(HttpStatusCode.OK, OkResponse(id = uuid.toString()))
        }

        delete("/conversations/{id}/nodes/{nodeId}") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            val nodeId = call.parameters["nodeId"].toUuid("node id")
            requireScheduleAction(uuid, ScheduleAction.DELETE_MESSAGE)
            getConversationForAdmin(uuid)
            chatService.deleteMessageNode(uuid, nodeId)
            call.respond(HttpStatusCode.OK, OkResponse(id = nodeId.toString()))
        }

        post("/conversations/{id}/tool-approval") {
            requireAdmin()
            val uuid = call.parameters["id"].toUuid("conversation id")
            val body = call.receive<JsonObject>()
            val toolCallId = body["toolCallId"]?.let { decodeString(it) }
                ?: throw BadRequestException("toolCallId is required")
            val approved = body["approved"]?.let { decode<Boolean>(it, "approved") }
                ?: throw BadRequestException("approved is required")
            val reason = body["reason"]?.let { decodeString(it) }.orEmpty()
            val answer = body["answer"]?.let { decodeString(it) }
            getConversationForAdmin(uuid)
            chatService.handleToolApproval(uuid, toolCallId, approved, reason, answer)
            call.respond(HttpStatusCode.OK, OkResponse(id = uuid.toString()))
        }

        // ---------------------------------------------------------------- agent sessions

        get("/agent/sessions/{childId}") {
            requireAdmin()
            val childId = call.parameters["childId"].toUuid("child id")
            val row = agentSessionDao.getByChildId(childId.toString())
                ?: throw NotFoundException("Agent session not found")
            call.respond(
                AgentSessionAdminDto(
                    childId = row.childId,
                    parentId = row.parentId,
                    rootId = row.rootId,
                    templateId = row.templateId,
                    status = row.status,
                    taskBrief = row.taskBrief,
                    depth = row.depth,
                    totalTokens = row.totalTokens,
                    turnsWithParent = row.turnsWithParent,
                    prematureEndCount = row.prematureEndCount,
                    createdAt = row.createdAt,
                    finishedAt = row.finishedAt,
                    lastSummary = row.lastSummary,
                    profileJson = row.profileJson,
                )
            )
        }

        post("/agent/sessions/{childId}/status") {
            requireAdmin()
            val childId = call.parameters["childId"].toUuid("child id")
            val body = call.receive<JsonObject>()
            val status = body["status"]?.let { decodeString(it) }
                ?: throw BadRequestException("status is required")
            val allowed = setOf(
                AgentStatuses.RUNNING,
                AgentStatuses.IDLE,
                AgentStatuses.WAITING_PARENT,
                AgentStatuses.WAITING_APPROVAL,
                AgentStatuses.DONE,
                AgentStatuses.FAILED,
                AgentStatuses.ERROR,
                AgentStatuses.STOPPED,
                AgentStatuses.ARCHIVED,
            )
            if (status !in allowed) throw BadRequestException("Invalid status: $status")
            agentSessionDao.getByChildId(childId.toString())
                ?: throw NotFoundException("Agent session not found")
            agentSessionDao.updateStatus(childId.toString(), status)
            call.respond(HttpStatusCode.OK, OkResponse(id = childId.toString()))
        }

        post("/agent/sessions/{childId}/archive") {
            requireAdmin()
            val childId = call.parameters["childId"].toUuid("child id")
            val result = agentBridge.archive(childId)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true, "result" to result))
        }

        post("/agent/sessions/{childId}/recover") {
            requireAdmin()
            val childId = call.parameters["childId"].toUuid("child id")
            agentBridge.recoverStaleSchedule(childId, "manual admin API recover")
            call.respond(HttpStatusCode.OK, OkResponse(id = childId.toString()))
        }

        post("/agent/sessions/{childId}/model") {
            requireAdmin()
            val childId = call.parameters["childId"].toUuid("child id")
            val body = call.receive<JsonObject>()
            val row = agentSessionDao.getByChildId(childId.toString())
                ?: throw NotFoundException("Agent session not found")
            val profile = runCatching { JsonInstant.decodeFromString<AgentProfile>(row.profileJson) }
                .getOrElse { throw BadRequestException("Invalid profile_json") }

            val modelId = body["modelId"]?.let { validateModelId(decodeString(it), "modelId") }
            val fallback = body["fallbackModelIds"]?.let { nodes ->
                if (nodes is JsonNull) emptyList()
                else decode<List<String>>(nodes, "fallbackModelIds")
                    .orEmpty().mapIndexed { i, raw ->
                        validateModelId(raw, "fallbackModelIds[$i]")!!.toString()
                    }
            } ?: profile.fallbackModelIds

            val next = profile.copy(
                modelId = modelId?.toString() ?: profile.modelId,
                fallbackModelIds = fallback,
            )
            agentSessionDao.upsert(row.copy(profileJson = JsonInstant.encodeToString(next)))

            val syncConversation = body["syncConversation"]?.let { decode<Boolean>(it, "syncConversation") } ?: true
            if (syncConversation) {
                conversationRepo.getConversationById(childId)?.let { conv ->
                    chatService.saveConversation(childId, conv.copy(modelId = modelId ?: conv.modelId))
                }
            }
            call.respond(HttpStatusCode.OK, OkResponse(id = childId.toString()))
        }

        // ---------------------------------------------------------------- schedule templates

        get("/schedule/templates") {
            requireAdmin()
            call.respond(scheduleManager.listTemplates(includeDisabled = true))
        }

        post("/schedule/reschedule") {
            requireAdmin()
            scheduler.rescheduleAll()
            call.respond(HttpStatusCode.OK, OkResponse())
        }

        post("/schedule/{templateId}/enabled") {
            requireAdmin()
            val templateId = call.parameters["templateId"]
                ?: throw BadRequestException("templateId is required")
            val body = call.receive<BooleanPatch>()
            val ok = scheduleManager.updateTemplate(templateId) { it.copy(enabled = body.enabled) }
            if (!ok) throw NotFoundException("Schedule template not found")
            call.respond(HttpStatusCode.OK, OkResponse(id = templateId))
        }

        /**
         * POST /api/admin/schedule/{templateId}/model
         *
         * Fixes the real schedule-agent model switching problem in one shot:
         * template JSON + current agent_session profile + current Conversation.model_id.
         */
        post("/schedule/{templateId}/model") {
            requireAdmin()
            val templateId = call.parameters["templateId"]
                ?: throw BadRequestException("templateId is required")
            val body = call.receive<JsonObject>()
            val template = scheduleManager.getTemplate(templateId)
                ?: throw NotFoundException("Schedule template not found")

            val modelId = body["modelId"]?.let { validateModelId(decodeString(it), "modelId") }
            val fallback = body["fallbackModelIds"]?.let { nodes ->
                if (nodes is JsonNull) emptyList()
                else decode<List<String>>(nodes, "fallbackModelIds")
                    .orEmpty().mapIndexed { i, raw -> validateModelId(raw, "fallbackModelIds[$i]")!! }
            } ?: template.fallbackModelIds

            scheduleManager.updateTemplate(templateId) { t ->
                t.copy(
                    modelId = modelId ?: t.modelId,
                    fallbackModelIds = fallback,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            val syncSession = body["syncSession"]?.let { decode<Boolean>(it, "syncSession") } ?: true
            if (syncSession) {
                val row = agentSessionDao.getByTemplateId(
                    template.id,
                    SCHEDULE_VIRTUAL_PARENT_ID.toString(),
                )
                if (row != null) {
                    val childId = runCatching { Uuid.parse(row.childId) }.getOrNull()
                    if (childId != null) {
                        val profile = runCatching { JsonInstant.decodeFromString<AgentProfile>(row.profileJson) }
                            .getOrDefault(AgentProfile())
                        agentSessionDao.upsert(
                            row.copy(
                                profileJson = JsonInstant.encodeToString(
                                    profile.copy(
                                        modelId = modelId?.toString() ?: profile.modelId,
                                        fallbackModelIds = fallback.map { it.toString() },
                                    )
                                )
                            )
                        )
                        conversationRepo.getConversationById(childId)?.let { conv ->
                            chatService.saveConversation(childId, conv.copy(modelId = modelId ?: conv.modelId))
                        }
                    }
                }
            }
            call.respond(HttpStatusCode.OK, OkResponse(id = template.id))
        }
    }
}
