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
import me.rerere.rikkahub.data.model.PendingUnlock
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.model.isUnlockStale
import me.rerere.rikkahub.data.model.normalizeLockedPath
import me.rerere.rikkahub.focus.FocusPolicyEngine
import java.io.File
import kotlin.uuid.Uuid

/** 稳定工具名，供 ChatService 识别并豁免于本地工具过滤器。 */
const val SUPERVISION_ADMIN_TOOL_NAME = "supervision_admin"

/**
 * 监督管理工具（PLAN_SUPERVISION_ADMIN_TOOL）。
 *
 * 2026-08-18：原独立工具 `supervision_request_unlock` 已并入本工具的
 * [ACTION_REQUEST_UNLOCK] action。理由：那个工具**没有任何开关**，只要
 * 「守门员助手 + 监督时段」就自动挂载，约 900 字符的 description 每轮硬注入；
 * 而两者身份门槛本来就完全一致（都要求 `assistantId == unlockGrantorAssistantId`），
 * 没必要占两个工具位。合并后一律**默认关闭**，用户在助手本地工具页手动开
 * （监督期内 Gate 对这一位开了例外，所以随时能开，见 SupervisionGate 洞①）。
 *
 * ⚠️ 代价（天赢 2026-08-18 拍的方案 B）：开关关着时监督期内**没有**申请解锁的
 * 通道，得先去助手设置里把这个工具打开。不做「精简模式自动兜底」。
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

    // request_unlock 的可用条件沿用原 SupervisionUnlockTool 的三条判断：
    // 监督期内 + 守门员 + 没有正在处理的申请（过期的已批准记录要跳过，否则解锁一次后永久不可用）。
    // 不满足时**不放进 enum**，省掉模型无意义的尝试和这段 schema 的 token。
    val activePending = sup.pendingUnlock?.takeUnless { sup.isUnlockStale() }
    val canRequestUnlock = isGrantor && sup.isActiveNow() && (
        activePending == null ||
            activePending.status == PendingUnlock.Status.REJECTED ||
            activePending.status == PendingUnlock.Status.CANCELLED
        )

    val allowedActions = when {
        isGrantor && canRequestUnlock -> GRANTOR_ACTIONS + ACTION_REQUEST_UNLOCK
        isGrantor -> GRANTOR_ACTIONS
        else -> SCHEDULE_ACTIONS
    }

    // 单独拼好再插进 description：raw string 里套 raw string 虽然编得过，
    // 但可读性差且容易再踩 42f4ddfe 那种注释/引号事故。
    val unlockSection = if (!canRequestUnlock) "" else "\n\n" + """
        `request_unlock` is the OPPOSITE direction, and the user's only escape hatch.
        Use it ONLY for a genuinely urgent, time-sensitive real-world emergency — not
        "I want to code / browse / play". Push back and demand a reason first. It does not
        unlock anything by itself: it registers a request, starts a ${sup.cooldownMinutes}-minute
        cooldown, and the user must still confirm in the UI. If you refuse, just say so
        without calling the tool.
    """.trimIndent()

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

            Focus lock actions control the on-device AccessibilityService. The service must
            first be enabled by the user in Android settings. Phase 1 supports the HOME-action
            interceptor and temporary package grants; overlay UI is intentionally not enabled yet.
        """.trimIndent() + unlockSection,
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
                                "lock_path / unlock_path: block workspace file tools under a rootfs path prefix." +
                                if (canRequestUnlock) {
                                    " request_unlock: ask to end the whole supervision window early " +
                                        "(cooldown + user confirmation; requires reason)."
                                } else {
                                    ""
                                },
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
                        put(
                            "description",
                            "Short reason shown to the user in the supervision settings page. " +
                                "REQUIRED for request_unlock (1-2 sentences, shown during confirmation).",
                        )
                    })
                    put("active", buildJsonObject {
                        put("type", "boolean")
                        put("description", "New physical focus-lock state for set_focus_lock_state.")
                    })
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Android package name for grant_temporary_whitelist.")
                    })
                    put("duration_minutes", buildJsonObject {
                        put("type", "integer")
                        put("description", "Temporary whitelist duration; must be greater than zero.")
                    })
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val reason = params["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val active = params["active"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            val packageName = params["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val durationMinutes = params["duration_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

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

                action == ACTION_SET_FOCUS_LOCK_STATE -> {
                    when {
                        active == null -> mapOf(
                            "success" to false,
                            "error" to "active is required and must be a boolean",
                        )
                        !isGrantor && !active -> mapOf(
                            "success" to false,
                            "error" to "schedule agents may only enable the physical focus lock",
                        )
                        else -> {
                            FocusPolicyEngine.setLockActive(active)
                            mapOf(
                                "success" to true,
                                "is_lock_active" to FocusPolicyEngine.isLockActive,
                                "note" to "AccessibilityService policy updated in this process.",
                            )
                        }
                    }
                }

                action == ACTION_GET_FOCUS_STATUS -> mapOf(
                    "success" to true,
                    "is_lock_active" to FocusPolicyEngine.isLockActive,
                    "base_whitelist" to FocusPolicyEngine.baseWhiteList.toList(),
                    "temporary_whitelist" to FocusPolicyEngine.temporaryWhiteListSnapshot(),
                    "note" to "The user must enable RikkaHub's AccessibilityService in Android settings.",
                )

                action == ACTION_GRANT_TEMPORARY_WHITELIST -> {
                    if (!isGrantor) {
                        mapOf("success" to false, "error" to "only the designated supervisor may grant temporary access")
                    } else if (packageName.isBlank() || durationMinutes <= 0) {
                        mapOf("success" to false, "error" to "package and positive duration_minutes are required")
                    } else {
                        val granted = FocusPolicyEngine.grantTemporary(packageName, durationMinutes)
                        mapOf(
                            "success" to granted,
                            "package" to packageName,
                            "duration_minutes" to durationMinutes,
                        )
                    }
                }

                action == ACTION_REQUEST_UNLOCK -> {
                    // 原 supervision_request_unlock 的逻辑整体搬过来（2026-08-18 合并）。
                    // 注意重新读一遍 settings：工具是在生成开始时构造的，等到真正 execute
                    // 可能已经过了几十秒，中间用户可能自己动过监督配置。
                    val fresh = settingsStore.settingsFlow.value
                    val freshSup = fresh.supervision
                    val freshPending = freshSup.pendingUnlock?.takeUnless { freshSup.isUnlockStale() }
                    when {
                        reason.isBlank() -> mapOf(
                            "success" to false,
                            "error" to "reason is required and must not be empty",
                        )

                        !freshSup.isActiveNow() -> mapOf(
                            "success" to false,
                            "error" to "not inside a supervision window right now; nothing to unlock",
                        )

                        freshPending != null &&
                            freshPending.status != PendingUnlock.Status.REJECTED &&
                            freshPending.status != PendingUnlock.Status.CANCELLED -> mapOf(
                            "success" to false,
                            "error" to "an unlock request is already ${freshPending.status}; " +
                                "wait for it instead of filing another",
                        )

                        else -> {
                            val now = System.currentTimeMillis()
                            val cooldownMs = freshSup.cooldownMinutes.coerceAtLeast(0) * 60_000L
                            val pending = PendingUnlock(
                                requestedAt = now,
                                expiresAt = now + cooldownMs,
                                reason = reason,
                                grantedByAssistantId = assistantId,
                                conversationId = conversationId,
                                status = PendingUnlock.Status.PENDING,
                            )
                            // 走普通 update 而非 updateSupervisionByAdmin：pendingUnlock 从
                            // null→PENDING 是 Gate 显式放行的合法迁移（sanitizePendingUnlock），
                            // 不该借 AdminBypass 的道 —— 那会顺手把别的字段也放开。
                            settingsStore.update(
                                fresh.copy(supervision = freshSup.copy(pendingUnlock = pending)),
                            )
                            mapOf(
                                "success" to true,
                                "status" to "pending",
                                "cooldown_minutes" to freshSup.cooldownMinutes,
                                "message" to "解锁请求已登记。冷却 ${freshSup.cooldownMinutes} 分钟后，" +
                                    "用户需要在「专注监督」设置里确认才会生效。",
                            )
                        }
                    }
                }

                action == ACTION_LOCK_CONVERSATION || action == ACTION_UNLOCK_CONVERSATION -> {
                    val target = params["conversation_id"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
                    when {
                        target == null -> mapOf(
                            "success" to false,
                            "error" to "conversation_id is required and must be a valid uuid",
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
                                    " 路径锁在监督时段内挡住指向该路径的 workspace 文件工具；" +
                                    "shell 只拒绝命令文本里显式引用该路径的调用，其余命令照跑。"),
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

/**
 * 申请提前结束整个监督时段（原独立工具 `supervision_request_unlock`，2026-08-18 并入）。
 *
 * 只在「守门员 + 监督期内 + 无进行中的申请」时进入 enum；不满足则整条 action 不存在，
 * 连 schema 都不生成。语义与旧工具一致：只登记 [PendingUnlock]，冷却结束后用户在 UI 确认。
 */
private const val ACTION_REQUEST_UNLOCK = "request_unlock"
private const val ACTION_SET_FOCUS_LOCK_STATE = "set_focus_lock_state"
private const val ACTION_GRANT_TEMPORARY_WHITELIST = "grant_temporary_whitelist"
private const val ACTION_GET_FOCUS_STATUS = "get_focus_status"

/** 守门员：全部 action。 */
private val GRANTOR_ACTIONS = listOf(
    ACTION_EXPORT,
    ACTION_IMPORT,
    ACTION_LOCK_CONVERSATION,
    ACTION_UNLOCK_CONVERSATION,
    ACTION_LOCK_PATH,
    ACTION_UNLOCK_PATH,
    ACTION_SET_FOCUS_LOCK_STATE,
    ACTION_GRANT_TEMPORARY_WHITELIST,
    ACTION_GET_FOCUS_STATUS,
)

/**
 * 定时任务白名单：**只许加锁**。
 *
 * 定时任务无人值守、被 prompt 注入骗到的风险最高，给它解锁 / 导入设置的权限
 * 等于把整套监督交给一个自动脚本（PLAN §1「允许增强（收紧配置）」）。
 */
private val SCHEDULE_ACTIONS = listOf(
    ACTION_LOCK_CONVERSATION,
    ACTION_LOCK_PATH,
    ACTION_SET_FOCUS_LOCK_STATE,
    ACTION_GET_FOCUS_STATUS,
)

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
