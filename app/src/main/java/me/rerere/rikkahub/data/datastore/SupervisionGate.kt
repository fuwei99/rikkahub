package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PendingUnlock
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.model.ToolFilter
import me.rerere.rikkahub.data.model.isActiveNow
import kotlin.uuid.Uuid

/**
 * 监督期设置写入闸门（见 PLAN_SUPERVISION_LOCK §3）。
 *
 * 所有写入 [SettingsStore] 的 settings 都会在监督时段经过 [enforceDuringLock]：
 * - 白名单助手的关键字段被回滚为旧值（system prompt / 工具 / 模型 / 自定义 header 等）；
 * - 监督期不允许新增助手（挡 add/copy/import）；
 * - 监督期不允许新增 MCP server，不允许把已关闭的 MCP 工具重新打开；
 * - [SupervisionSettings] 字段本身只许加强；若来自云同步下拉，还会与本机配置做 strengthenWith；
 * - 紧急解锁状态 [PendingUnlock] 允许「守门员工具」登记 PENDING、用户在 UI 推进
 *   状态机（PENDING → READY → APPROVED / CANCELLED），其余路径不能直接清除。
 *
 * 注意：本类不是安全边界。UI 的只读只是体验层，真正的兜底在这里。
 */
class SupervisionGate {

    /**
     * @param isSyncPull 是否由云同步下拉触发。同步下来的监督配置本身也要被加强，
     *   防止在另一台设备上改弱后，同步一下就把监督中的本机解锁。
     */
    fun enforceDuringLock(
        old: Settings,
        incoming: Settings,
        isSyncPull: Boolean = false,
    ): Settings {
        if (old.init || !old.supervision.isActiveNow()) return incoming

        var result = incoming

        // 1) 助手层：禁止新建；白名单助手关键字段回滚
        result = sanitizeAssistants(old, result)

        // 2) MCP 层：禁止新增 server；禁止重新启用已关闭的工具
        result = sanitizeMcpServers(old, result)

        // 3) 监督配置本身：只许加强
        result = sanitizeSupervision(old, result, isSyncPull)

        return result
    }

    private fun sanitizeAssistants(old: Settings, incoming: Settings): Settings {
        val oldById = old.assistants.associateBy { it.id }
        val lockedIds = old.supervision.allowedAssistantIds

        val sanitizedList = incoming.assistants.mapNotNull { new ->
            val oldA = oldById[new.id]
            when {
                oldA == null -> null
                lockedIds.isNotEmpty() && new.id in lockedIds -> rollbackLockedAssistant(oldA, new)
                else -> new
            }
        }

        val safeAssistantId = when {
            sanitizedList.none { it.id == incoming.assistantId } -> old.assistantId
            lockedIds.isNotEmpty() && incoming.assistantId !in lockedIds -> old.assistantId
            else -> incoming.assistantId
        }

        return incoming.copy(
            assistants = sanitizedList,
            assistantId = safeAssistantId,
        )
    }

    private fun rollbackLockedAssistant(oldA: Assistant, new: Assistant): Assistant = new.copy(
        systemPrompt = oldA.systemPrompt,
        presetMessages = oldA.presetMessages,
        messageTemplate = oldA.messageTemplate,
        localTools = oldA.localTools,
        mcpServers = oldA.mcpServers,
        enabledSkills = oldA.enabledSkills,
        enableWebSearch = oldA.enableWebSearch,
        workspaceId = oldA.workspaceId,
        chatModelId = oldA.chatModelId,
        temperature = oldA.temperature,
        topP = oldA.topP,
        maxTokens = oldA.maxTokens,
        reasoningLevel = oldA.reasoningLevel,
        customHeaders = oldA.customHeaders,
        customBodies = oldA.customBodies,
        allowConversationSystemPrompt = oldA.allowConversationSystemPrompt,
        allowConversationPromptInjection = oldA.allowConversationPromptInjection,
    )

    private fun sanitizeMcpServers(old: Settings, incoming: Settings): Settings {
        if (!old.supervision.lockMcpServers) return incoming
        val oldById = old.mcpServers.associateBy { it.id }
        val guarded: List<McpServerConfig> = incoming.mcpServers.mapNotNull { server ->
            val oldServer = oldById[server.id] ?: return@mapNotNull null
            val oldToolsByName = oldServer.commonOptions.tools.associateBy { it.name }
            val guardedTools = server.commonOptions.tools.map { tool ->
                val wasEnabled = oldToolsByName[tool.name]?.enable ?: false
                tool.copy(enable = tool.enable && wasEnabled)
            }
            val wasServerEnabled = oldServer.commonOptions.enable
            val guardedEnable = server.commonOptions.enable && wasServerEnabled
            server.clone(
                commonOptions = server.commonOptions.copy(
                    enable = guardedEnable,
                    tools = guardedTools,
                ),
            )
        }
        return incoming.copy(mcpServers = guarded)
    }

    private fun sanitizeSupervision(
        old: Settings,
        incoming: Settings,
        isSyncPull: Boolean,
    ): Settings {
        val base = if (isSyncPull) {
            old.supervision.strengthenWith(incoming.supervision)
        } else {
            strengthenLocalSupervision(old.supervision, incoming.supervision)
        }
        return incoming.copy(supervision = base)
    }

    /**
     * 本地写入：逐字段比较，任何「减弱」都回滚为 old 值。
     */
    private fun strengthenLocalSupervision(
        old: SupervisionSettings,
        incoming: SupervisionSettings,
    ): SupervisionSettings {
        val safeGrantor = sanitizeGrantor(
            oldAllowed = old.allowedAssistantIds,
            oldGrantor = old.unlockGrantorAssistantId,
            incomingGrantor = incoming.unlockGrantorAssistantId,
        )

        return SupervisionSettings(
            enabled = incoming.enabled || old.enabled,
            schedules = if (incoming.schedules.containsAll(old.schedules)) incoming.schedules else old.schedules,
            allowedAssistantIds = sanitizeAllowedAssistantIds(old.allowedAssistantIds, incoming.allowedAssistantIds),
            localToolFilter = sanitizeToolFilter(old.localToolFilter, incoming.localToolFilter),
            workspaceToolFilter = sanitizeToolFilter(old.workspaceToolFilter, incoming.workspaceToolFilter),
            mcpToolFilter = sanitizeToolFilter(old.mcpToolFilter, incoming.mcpToolFilter),
            lockMcpServers = incoming.lockMcpServers || old.lockMcpServers,
            unlockGrantorAssistantId = safeGrantor,
            cooldownMinutes = maxOf(incoming.cooldownMinutes, old.cooldownMinutes),
            pendingUnlock = sanitizePendingUnlock(old.pendingUnlock, incoming.pendingUnlock),
            updatedAt = maxOf(incoming.updatedAt, old.updatedAt, System.currentTimeMillis()),
        )
    }

    /**
     * 守门员 id 的加严规则：
     * - old 为 null（无人可解锁）：不允许在监督期内设置守门员（= 凭空给自己一把钥匙），
     *   除非是首次启用监督且白名单刚设好（这种情况要在非监督期完成，不在这里）。
     * - old 非空：只允许保持同一个 id（不允许通过设置改派守门员）。
     */
    private fun sanitizeGrantor(
        oldAllowed: Set<Uuid>,
        oldGrantor: Uuid?,
        incomingGrantor: Uuid?,
    ): Uuid? {
        if (oldGrantor == null) return null
        if (incomingGrantor == oldGrantor) return oldGrantor
        // 守门员必须在白名单内
        if (incomingGrantor != null &&
            incomingGrantor == oldGrantor &&
            (oldAllowed.isEmpty() || incomingGrantor in oldAllowed)
        ) return oldGrantor
        return oldGrantor
    }

    /**
     * PendingUnlock 状态机的合法迁移：
     * ```
     * null ──守门员工具──▶ PENDING ──冷却结束(自动/UI)──▶ READY ──用户确认──▶ APPROVED (解锁生效)
     *                       │                       │
     *                       └──── 用户取消 ──────────┴──▶ CANCELLED
     * READY 超时未确认也允许回到 CANCELLED
     * ```
     * 监督期内：
     * - null → PENDING：允许（守门员工具发起）；
     * - PENDING → CANCELLED：允许（用户主动取消 = 加强）；
     * - PENDING → READY：允许（冷却结束，时间到了，不是用户能随意伪造的——UI 上
     *   根据 expiresAt 自动展示 READY 状态，写入时保留）；
     * - READY → APPROVED：**允许**（用户最终确认，这就是解锁动作本身）；
     * - READY → CANCELLED：允许；
     * - APPROVED → 其他：不允许（已解锁，等时段结束后由系统自动清理）；
     * - REJECTED/CANCELLED → null：允许（清除已结束的请求，方便下次再申请）；
     * - 其他任何「减弱」路径（如直接把 APPROVED 清掉、把 PENDING 改回 null）一律保留 old。
     */
    private fun sanitizePendingUnlock(
        old: PendingUnlock?,
        incoming: PendingUnlock?,
    ): PendingUnlock? {
        val nowMs = System.currentTimeMillis()
        if (old == incoming) return old
        if (old == null) {
            // 只允许新进入 PENDING
            return if (incoming?.status == PendingUnlock.Status.PENDING) incoming else null
        }
        return when (old.status) {
            PendingUnlock.Status.PENDING -> when (incoming?.status) {
                PendingUnlock.Status.PENDING -> incoming
                // 冷却结束才能进入 READY / APPROVED；冷却中只允许保持或取消
                PendingUnlock.Status.READY,
                PendingUnlock.Status.APPROVED ->
                    if (nowMs >= old.expiresAt) incoming else old
                PendingUnlock.Status.CANCELLED -> incoming
                else -> old
            }
            PendingUnlock.Status.READY -> when (incoming?.status) {
                PendingUnlock.Status.APPROVED -> incoming
                PendingUnlock.Status.CANCELLED -> incoming
                PendingUnlock.Status.READY -> incoming
                else -> old
            }
            PendingUnlock.Status.APPROVED -> old
            PendingUnlock.Status.REJECTED,
            PendingUnlock.Status.CANCELLED ->
                // 终态允许清除（= 下次可重新申请），不允许伪造新 PENDING
                if (incoming == null) null else old
        }
    }

    private fun sanitizeAllowedAssistantIds(
        old: Set<Uuid>,
        incoming: Set<Uuid>,
    ): Set<Uuid> = when {
        old.isEmpty() -> incoming
        incoming.isEmpty() -> old
        else -> incoming intersect old
    }

    private fun sanitizeToolFilter(old: ToolFilter, incoming: ToolFilter): ToolFilter {
        if (old.mode == ToolFilter.Mode.WHITELIST && incoming.mode == ToolFilter.Mode.BLACKLIST) {
            return old
        }
        return when (incoming.mode) {
            ToolFilter.Mode.BLACKLIST -> incoming.copy(items = old.items union incoming.items)
            ToolFilter.Mode.WHITELIST -> {
                val safeItems = if (old.mode == ToolFilter.Mode.BLACKLIST) {
                    incoming.items.filterNot { it in old.items }.toSet()
                } else {
                    incoming.items intersect old.items
                }
                incoming.copy(items = safeItems)
            }
        }
    }
}
