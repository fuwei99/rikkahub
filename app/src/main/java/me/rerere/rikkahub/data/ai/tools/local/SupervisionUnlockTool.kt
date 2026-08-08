package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.PendingUnlock
import me.rerere.rikkahub.data.model.isActiveNow
import kotlin.uuid.Uuid

/**
 * 监督期紧急解锁工具（见 PLAN_SUPERVISION_LOCK §10 / 紧急解锁改造）。
 *
 * 仅当满足以下**全部**条件时可用：
 * 1. 当前处于监督时段；
 * 2. 正在使用的助手 id == [SupervisionSettings.unlockGrantorAssistantId]；
 * 3. 没有已在处理中的 pendingUnlock（避免重复申请）；
 * 4. 调用必须提供非空的 [reason]，说明为什么需要提前解锁。
 *
 * 工具调用本身**不会立刻解锁**——它只是登记一个 [PendingUnlock]，进入冷却
 * （[SupervisionSettings.cooldownMinutes] 分钟）。冷却结束后，用户必须在 UI
 * 上手动点「确认解锁」才真正生效。这样防止 AI 被用户一句话忽悠就解锁。
 */
internal fun buildSupervisionUnlockTool(
    settingsStore: SettingsStore,
    conversationId: Uuid,
    assistantId: Uuid,
): Tool? {
    val settings = settingsStore.settingsFlow.value
    val sup = settings.supervision

    // 守门员未配置 / 当前助手不是守门员 / 监督未生效：不挂载此工具
    val grantor = sup.unlockGrantorAssistantId ?: return null
    if (grantor != assistantId) return null
    if (!sup.isActiveNow()) return null
    // 已有请求在处理中（PENDING / READY）就不再挂工具，避免 AI 反复申请
    if (sup.pendingUnlock != null &&
        sup.pendingUnlock.status != PendingUnlock.Status.REJECTED &&
        sup.pendingUnlock.status != PendingUnlock.Status.CANCELLED
    ) return null

    return Tool(
        name = SUPERVISION_UNLOCK_TOOL_NAME,
        description = """
            Request an early unlock of the supervision (focus) lock for THIS DEVICE.

            Use this ONLY when the user has a legitimate, urgent reason to leave focus mode
            (e.g. an emergency they must handle on the phone, not "I want to write code").
            You are the designated gatekeeper assistant. You should:

            1. Push back and ask the user why they need to unlock.
            2. Only call this tool if the reason is genuinely urgent AND time-sensitive.
            3. Refuse if the user just wants to play, browse, or vibe code.

            Calling this tool does NOT unlock immediately. It starts a ${sup.cooldownMinutes}-minute
            cooldown; the user must then confirm in the UI. If you refuse, just explain to the
            user without calling the tool.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Short explanation (1-2 sentences) of why unlocking is necessary. " +
                                "Will be shown to the user during the confirmation step.",
                        )
                    })
                },
                required = listOf("reason"),
            )
        },
        execute = { args ->
            val reason = args.jsonObject["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (reason.isBlank()) {
                return@execute listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("success", false)
                            put("error", "reason is required and must not be empty")
                        }.toString(),
                    ),
                )
            }

            val now = System.currentTimeMillis()
            val cooldownMs = sup.cooldownMinutes.coerceAtLeast(0) * 60_000L
            val pending = PendingUnlock(
                requestedAt = now,
                expiresAt = now + cooldownMs,
                reason = reason,
                grantedByAssistantId = assistantId,
                conversationId = conversationId,
                status = PendingUnlock.Status.PENDING,
            )

            // 直接走 update：Gate 会做「只许加强」检查。pendingUnlock 从 null→非空
            // 在 Gate 里要显式放行（见 SupervisionGate.sanitizePendingUnlock）。
            settingsStore.update(
                settings.copy(supervision = sup.copy(pendingUnlock = pending)),
            )

            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("status", "pending")
                        put("cooldown_minutes", sup.cooldownMinutes)
                        put(
                            "message",
                            "解锁请求已登记。冷却 ${sup.cooldownMinutes} 分钟后，" +
                                "用户需要在「专注监督」设置里确认才会生效。",
                        )
                    }.toString(),
                ),
            )
        },
    )
}

/** 稳定工具名，供 ChatService 识别并豁免于本地工具过滤器。 */
const val SUPERVISION_UNLOCK_TOOL_NAME = "supervision_request_unlock"
