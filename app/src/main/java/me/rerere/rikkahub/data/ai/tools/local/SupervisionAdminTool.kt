package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsJsonExchange
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.model.normalizeLockedPath
import java.io.File
import kotlin.uuid.Uuid

/** 稳定工具名，供 ChatService 识别并豁免于本地工具过滤器。 */
const val SUPERVISION_ADMIN_TOOL_NAME = "supervision_admin"

/**
 * 监督管理工具（PLAN_SUPERVISION_ADMIN_TOOL）。
 *
 * 与 `supervision_request_unlock`（申请解锁，走冷却 + 人工确认）互补：这个工具是
 * **监工侧**的执行手段，默认关闭，需要用户在助手本地工具页手动打开，且还要满足
 * 身份条件才真正挂载（双重门）。
 *
 * 挂载条件（满足任一路径即可）：
 * 1. `assistantId == supervision.unlockGrantorAssistantId`（守门员）——全部 action；
 * 2. `templateId in supervision.adminScheduleAgentIds`（定时任务白名单）——只允许**加锁**类
 *    action，且不带 AdminBypass（只能收紧，不能松绑）。
 *
 * 为什么没有 read_config / write_config：设置本来就落在 `setting-json/` 的 json 文件里，
 * agent 用 workspace 的 read / write / edit 直接改文件即可，再包一层 JSON action
 * 是重复造 API。本工具在配置方面唯一的职责是**把文件与内存状态对上**，
 * 即 export / import 两下，逻辑与「偏好设置 → 数据与备份」的两个按钮完全一致
 * （同一个 [SettingsJsonExchange] 实例，不另写一套）。
 *
 * @param assistantId 当前对话使用的助手 id。
 * @param scheduleTemplateId 当前对话若为 schedule agent 会话，则为其模板 id；否则 null。
 */
internal fun buildSupervisionAdminTool(
    settingsStore: SettingsStore,
    settingsJsonExchange: SettingsJsonExchange,
    lockCoordinator: SupervisionLockCoordinator,
    conversationId: Uuid,
    assistantId: Uuid,
    scheduleTemplateId: String?,
): Tool? {
    val settings = settingsStore.settingsFlow.value
    val sup = settings.supervision

    val isGrantor = sup.unlockGrantorAssistantId != null && sup.unlockGrantorAssistantId == assistantId
    val isAdminSchedule = scheduleTemplateId != null && scheduleTemplateId in sup.adminScheduleAgentIds
    // 两条身份路径都不满足 → 不挂载（开关开着也没用，这是「双重门」的第二道）
    if (!isGrantor && !isAdminSchedule) return null

    val allowedActions = if (isGrantor) GRANTOR_ACTIONS else SCHEDULE_ACTIONS

    return Tool(
        name = SUPERVISION_ADMIN_TOOL_NAME,
        description = """
            Administer the focus-supervision lock on THIS DEVICE. You are the designated
            supervisor; the user cannot undo these actions from the UI while a supervision
            window is active (they can only appeal, which routes back to you).

            Available actions: ${allowedActions.joinToString(", ")}

            Editing supervision config is a THREE-step dance, do not skip step 1:
              1. `export_settings`  — flush in-memory settings to setting-json/*.json
              2. edit the file with workspace_edit_file (e.g. supervision.json)
              3. `import_settings`  — load the files back and sync to other devices

            Skipping step 1 means the files may be a stale snapshot, and step 3 would then
            silently revert everything the user changed in the UI since the last export.

            Locks (`lock_conversation` / `lock_path`) only take effect **inside supervision
            windows**; outside them everything is unlocked again. Locking is immediate and
            does not wait for the user's consent: the appeal dialog is a courtesy, not a veto.
            The lock lands when the countdown ends, when the user refuses, or when the user
            files an appeal — all three. An appeal text is delivered to your inbox afterwards;
            deciding whether to `unlock_*` is a separate, later call.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(allowedActions.map { JsonPrimitive(it) }))
                        put(
                            "description",
                            "export_settings: dump settings to setting-json/. " +
                                "import_settings: apply setting-json/ back into the app (bypasses the " +
                                "\"only stricter\" gate) and sync. " +
                                "lock_conversation / unlock_conversation: block sending in one conversation " +
                                "during supervision windows. " +
                                "lock_path / unlock_path: block workspace file tools under a rootfs path prefix.",
                        )
                    })
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Target conversation uuid, for lock_conversation / unlock_conversation.")
                    })
                    put("path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Absolute rootfs path prefix, for lock_path / unlock_path (e.g. /workspace/projects). " +
                                "Everything under it is blocked during supervision windows.",
                        )
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Short reason shown to the user in the supervision settings page.")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val reason = params["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

            val result: Map<String, Any?> = when {
                action !in allowedActions -> mapOf(
                    "success" to false,
                    "error" to "action must be one of ${allowedActions.joinToString(", ")}",
                )

                action == ACTION_EXPORT -> {
                    val exported = settingsJsonExchange.exportAll()
                    mapOf(
                        "success" to true,
                        "dir" to exported.file.absolutePath,
                        "files" to exported.file.listFilesSummary(),
                        "next" to "Edit the json files, then call import_settings.",
                    )
                }

                action == ACTION_IMPORT -> {
                    // adminBypass=true：只有这条路径允许「减弱」监督配置
                    val imported = settingsJsonExchange.importAllAndSync(adminBypass = true)
                    mapOf(
                        "success" to true,
                        "dir" to imported.file.absolutePath,
                        "message" to "已应用 setting-json/ 并进入同步队列（本次绕过了「只许加强」闸门）。",
                    )
                }

                action == ACTION_LOCK_CONVERSATION || action == ACTION_UNLOCK_CONVERSATION -> {
                    val target = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
                    when {
                        target == null -> mapOf(
                            "success" to false,
                            "error" to "conversation_id is required and must be a valid uuid",
                        )
                        // 别把自己的申诉通道锁掉：守门员对话被锁 = 用户再也没法跟监工说话
                        action == ACTION_LOCK_CONVERSATION && target == conversationId -> mapOf(
                            "success" to false,
                            "error" to "refusing to lock the conversation you are speaking in " +
                                "(that would cut the user's only appeal channel)",
                        )
                        action == ACTION_LOCK_CONVERSATION -> {
                            // 上锁走协调器：先给用户一个申诉窗口（倒计时结束/拒绝/申诉都会落锁），
                            // 工具本身立即返回 —— 不能把整条生成挂在 120 秒倒计时上。
                            val outcome = lockCoordinator.requestConversationLock(
                                conversationId = target,
                                reason = reason,
                                initiatorConversationId = conversationId,
                                showDialog = !isAdminSchedule,
                            )
                            mapOf(
                                "success" to true,
                                "locked" to outcome.locked,
                                "appeal_id" to outcome.appealId,
                                "deadline_at" to outcome.deadlineAt,
                                "active_now" to settingsStore.settingsFlow.value.supervision.isActiveNow(),
                                "note" to (outcome.message + " 锁只在监督时段内生效；时段结束自动放行。"),
                            )
                        }

                        else -> {
                            val currentSup = settingsStore.settingsFlow.value.supervision
                            val next = currentSup.lockedConversationIds - target
                            // 解锁 = 减弱，必须 bypass，否则 Gate 的并集会把移除原地回滚
                            settingsStore.updateSupervisionByAdmin(
                                currentSup.copy(lockedConversationIds = next),
                                bypassGate = true,
                            )
                            mapOf(
                                "success" to true,
                                "locked_conversations" to next.map { it.toString() },
                                "active_now" to currentSup.isActiveNow(),
                                "note" to "已解锁。" + if (reason.isNotBlank()) " 理由：$reason" else "",
                            )
                        }
                    }
                }

                else -> {
                    // lock_path / unlock_path
                    val raw = params["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val normalized = normalizeLockedPath(raw)
                    when {
                        normalized == null -> mapOf(
                            "success" to false,
                            "error" to "path must be an absolute rootfs path such as /workspace/projects " +
                                "(bare \"/\" is refused: it would lock the whole filesystem)",
                        )
                        action == ACTION_LOCK_PATH -> {
                            val outcome = lockCoordinator.requestPathLock(
                                path = normalized,
                                reason = reason,
                                initiatorConversationId = conversationId,
                                showDialog = !isAdminSchedule,
                            )
                            mapOf(
                                "success" to true,
                                "locked" to outcome.locked,
                                "appeal_id" to outcome.appealId,
                                "deadline_at" to outcome.deadlineAt,
                                "active_now" to settingsStore.settingsFlow.value.supervision.isActiveNow(),
                                "note" to (outcome.message +
                                    " 路径锁在监督时段内挡住所有 workspace 文件工具（含 shell）。"),
                            )
                        }

                        else -> {
                            val currentSup = settingsStore.settingsFlow.value.supervision
                            val next = currentSup.lockedWorkspacePaths.filterNot {
                                normalizeLockedPath(it) == normalized
                            }.toSet()
                            settingsStore.updateSupervisionByAdmin(
                                currentSup.copy(lockedWorkspacePaths = next),
                                bypassGate = true,
                            )
                            mapOf(
                                "success" to true,
                                "locked_paths" to next.toList(),
                                "active_now" to currentSup.isActiveNow(),
                                "note" to "已解锁路径。" + if (reason.isNotBlank()) " 理由：$reason" else "",
                            )
                        }
                    }
                }
            }

            listOf(UIMessagePart.Text(result.toJsonString()))
        },
    )
}

private const val ACTION_EXPORT = "export_settings"
private const val ACTION_IMPORT = "import_settings"
private const val ACTION_LOCK_CONVERSATION = "lock_conversation"
private const val ACTION_UNLOCK_CONVERSATION = "unlock_conversation"
private const val ACTION_LOCK_PATH = "lock_path"
private const val ACTION_UNLOCK_PATH = "unlock_path"

/** 守门员：全部 action。 */
private val GRANTOR_ACTIONS = listOf(
    ACTION_EXPORT,
    ACTION_IMPORT,
    ACTION_LOCK_CONVERSATION,
    ACTION_UNLOCK_CONVERSATION,
    ACTION_LOCK_PATH,
    ACTION_UNLOCK_PATH,
)

/**
 * 定时任务白名单：**只许加锁**。
 *
 * 定时任务无人值守、被 prompt 注入骗到的风险最高，给它解锁 / 导入设置的权限
 * 等于把整套监督交给一个自动脚本（PLAN §1「允许增强（收紧配置）」）。
 */
private val SCHEDULE_ACTIONS = listOf(ACTION_LOCK_CONVERSATION, ACTION_LOCK_PATH)

private fun File.listFilesSummary(): String =
    listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }
        ?.sortedBy { it.name }
        ?.joinToString(", ") { "${it.name}(mtime=${it.lastModified()})" }
        .orEmpty()

private fun Map<String, Any?>.toJsonString(): String = buildJsonObject {
    this@toJsonString.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it?.toString().orEmpty()) }))
            else -> put(key, value.toString())
        }
    }
}.toString()
