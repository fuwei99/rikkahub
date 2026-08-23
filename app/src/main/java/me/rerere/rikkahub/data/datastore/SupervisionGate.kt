package me.rerere.rikkahub.data.datastore

import kotlinx.coroutines.ThreadContextElement
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.PendingUnlock
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.model.ToolFilter
import me.rerere.rikkahub.data.model.clearStaleUnlock
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.model.isUnlockStale
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/**
 * 监督期设置写入闸门（见 PLAN_SUPERVISION_LOCK §3）。
 *
 * 所有写入 [SettingsStore] 的 settings 都会在监督时段经过 [enforceDuringLock]：
 * - 白名单助手的关键字段被回滚为旧值（system prompt / 本地工具 / 自定义 header 等；
 *   思维链、网页搜索、模型、采样参数等学习相关字段不锁）；
 * - 监督期不允许新增助手（挡 add/copy/import）；
 * - 监督期不允许新增 / 删除 MCP server，不允许改地址与 headers；
 *   MCP 的 enable 开关默认不锁（需要时开 lockMcpToolToggles）；
 *   助手上「挂哪些 MCP server」同样默认不锁 —— 只有 lockMcpServers /
 *   lockMcpToolToggles 任一为真时才回滚（2026-08-18 死锁修复）；
 * - [SupervisionSettings] 字段本身只许加强；若来自云同步下拉，还会与本机配置做 strengthenWith；
 * - 紧急解锁状态 [PendingUnlock] 允许「守门员工具」登记 PENDING、用户在 UI 推进
 *   状态机（PENDING → READY → APPROVED / CANCELLED），其余路径不能直接清除。
 *
 * 注意：本类不是安全边界。UI 的只读只是体验层，真正的兜底在这里。
 */
class SupervisionGate {

    /**
     * 监督管理工具的「减弱豁免」开关（PLAN_SUPERVISION_ADMIN_TOOL §2.1）。
     *
     * [SupervisionGate] 是全局兜底，不能整体关，所以给 `supervision_admin` 的
     * `import_settings` 开一条**协程局部**的旁路：期间 [enforceDuringLock] 直接
     * 原样返回 incoming（允许减弱）。
     *
     * 为什么不用 ThreadLocal：`SettingsJsonExchange.importAllAndSync` 内部自己
     * `withContext(Dispatchers.IO)`，ThreadLocal 跨线程就丢了，旁路会静默失效。
     * 这里用 [ThreadContextElement] 把标志绑在协程上下文上，切线程时自动搬过去。
     *
     * 手动导入（UI 按钮）**不设 bypass**，保持只许加强。
     */
    object AdminBypass : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> get() = Key

        object Key : CoroutineContext.Key<AdminBypass>

        /** 线程级实际标志，由 [element] 在协程切线程时搬运。 */
        private val threadFlag = ThreadLocal<Boolean>()

        val active: Boolean get() = threadFlag.get() == true

        /** 把它 `withContext(SupervisionGate.AdminBypass.element())` 包在要放行的写入外面。 */
        fun element(): CoroutineContext.Element = BypassElement

        private object BypassElement : ThreadContextElement<Boolean?>, CoroutineContext.Element {
            override val key: CoroutineContext.Key<*> get() = Key

            override fun updateThreadContext(context: CoroutineContext): Boolean? {
                val previous = threadFlag.get()
                threadFlag.set(true)
                return previous
            }

            override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean?) {
                if (oldState == null) threadFlag.remove() else threadFlag.set(oldState)
            }
        }
    }

    /**
     * 监督期内的写入闸门。
     *
     * ## 阶段 B 变更：删掉 `isSyncPull` 分支（v2 §3.4）
     *
     * 旧签名有个 `isSyncPull` 参数，同步下拉时走 `strengthenWith`「加强」监督配置。
     * 动机是对的（怕在另一台设备改弱后同步回来解锁），但实现有个致命洞：
     *
     * ```
     * 平板解锁（带 AdminBypass）→ 写入成功
     * 手机推来旧锁态 → isSyncPull=true → 拿不到 AdminBypass → strengthenWith
     *   → 把锁「加强」回去，还顺手 updatedAt = maxOf(...)
     *   → 两台设备互相投喂同一把锁，永生 💀（2026-08-23 21:12 实测）
     * ```
     *
     * v2 之后同步下拉搬运的是**事件**而不是**状态**，锁态由
     * [SupervisionSettings.eventLog] fold 决定，因此这里不再需要区分来源：
     *
     * - 锁的放松由**产生层** `SupervisionEventFactory` 把关（守门员 / 冷却 / bypass）
     * - 合并层只搬运事件，不可能凭空产生一次解锁
     * - 所以「同步能不能解锁」这个问题不存在了：它只能搬来一个**已经被授权过**的解锁事件
     *
     * 本函数因此只保留**本地写入**的加严职责（助手、MCP、监督配置字段）。
     */
    fun enforceDuringLock(
        old: Settings,
        incoming: Settings,
    ): Settings {
        if (old.init || !old.supervision.isActiveNow()) return incoming
        // 监督管理工具的 import_settings / 用户本人确认解锁：整闸放行。
        //
        // 这里不再排除同步路径：远端**状态**不再经由本函数被「加强」，
        // 远端**事件**走 SupervisionSettings.strengthenWith 里的 eventLog.merge，
        // 而事件的合法性在产生它的那台设备上就已经校验过了（§3.4 产生层）。
        if (AdminBypass.active) return incoming

        // 入口先洗掉「上个时段批准、已失效」的解锁记录，否则它会被当成合法旧状态，
        // 把守门员新登记的 PENDING 吃掉（APPROVED → 其他一律拒绝）。
        val base = old.copy(supervision = old.supervision.clearStaleUnlock())

        var result = incoming

        // 1) 助手层：禁止新建；白名单助手关键字段回滚
        result = sanitizeAssistants(base, result)

        // 2) MCP 层：禁止新增 server；禁止重新启用已关闭的工具
        result = sanitizeMcpServers(base, result)

        // 3) 监督配置本身：只许加强
        result = sanitizeSupervision(base, result)

        return result
    }

    private fun sanitizeAssistants(old: Settings, incoming: Settings): Settings {
        val oldById = old.assistants.associateBy { it.id }
        val lockedIds = old.supervision.allowedAssistantIds

        val sanitizedList = incoming.assistants.mapNotNull { new ->
            val oldA = oldById[new.id]
            when {
                oldA == null -> null
                lockedIds.isNotEmpty() && new.id in lockedIds ->
                    rollbackLockedAssistant(
                        oldA = oldA,
                        new = new,
                        lockSkills = old.supervision.lockSkills,
                        // 助手上的 MCP 挂载集合是「能力开关」，不是结构性配置：
                        // 只有显式开了某个 MCP 锁才回滚（2026-08-18 死锁修复，见下方注释）。
                        lockMcpMounts = old.supervision.lockMcpServers ||
                            old.supervision.lockMcpToolToggles,
                    )
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

        private fun rollbackLockedAssistant(
        oldA: Assistant,
        new: Assistant,
        lockSkills: Boolean,
        lockMcpMounts: Boolean,
    ): Assistant = new.copy(
        systemPrompt = oldA.systemPrompt,
        presetMessages = oldA.presetMessages,
        messageTemplate = oldA.messageTemplate,
        // 注意（2026-08-18 对话级重构）：这里回滚的是**助手默认值**。
        // 能力开关已下沉到 Conversation，用户在对话里仍可自行开关工具 ——
        // 监督期真正的能力收口是 ChatService 里的 localToolFilter /
        // workspaceToolFilter / mcpToolFilter（黑白名单在最终工具集上过滤），
        // 那层不受本次重构影响，仍是唯一有效边界。本行只防「改助手默认值绕过」。
        localTools = mergeLocalToolsAllowingAdminBit(oldA.localTools, new.localTools),
        // 2026-08-18 死锁修复：这一行原来是**无条件**回滚，于是
        // 「监督设置里两个 MCP 锁都关着」的情况下，白名单助手依然永远挂不上
        // 任何 MCP server（助手页开关一按就被弹回），连 doubaosearch 这种
        // 纯查资料的搜索 MCP 都开不了 —— 而 UI 上没有任何东西显示它被锁了。
        // 语义上 assistant.mcpServers 只是「这个助手挂哪些 server」= 能力开关，
        // 真正的能力收口在 ChatService 的 mcpToolFilter；所以这里改为跟随
        // lockMcpServers / lockMcpToolToggles，两个都关 = 允许挂载。
        mcpServers = if (lockMcpMounts) oldA.mcpServers else new.mcpServers,
        // skill 默认不锁（原实现无条件回滚等于监督期 skill 系统整体失效）
        enabledSkills = if (lockSkills) oldA.enabledSkills else new.enabledSkills,
        customHeaders = oldA.customHeaders,
        customBodies = oldA.customBodies,
        // 学习相关字段不锁 — 用户需要在监督期改思维链讲题、开关网页搜索、换模型等
        // enableWebSearch / chatModelId / temperature / topP / maxTokens / reasoningLevel /
        // allowConversationSystemPrompt / allowConversationPromptInjection 保留 incoming 值
    )

    /**
     * localTools 回滚的唯一例外：[LocalToolOption.SupervisionAdmin] 这一位放行增删，
     * 其余全部回滚为旧值（PLAN_SUPERVISION_ADMIN_TOOL §6 洞①）。
     *
     * 不这么做的后果：监督期内用户永远打不开这个开关（一写就被 Gate 回滚），
     * 而开关又是监督期自救的唯一入口 —— 整套设计直接死掉。
     * 逆向（关掉）也放行：关掉是加严，没道理拦。
     */
    private fun mergeLocalToolsAllowingAdminBit(
        old: List<LocalToolOption>,
        incoming: List<LocalToolOption>,
    ): List<LocalToolOption> {
        val adminWanted = LocalToolOption.SupervisionAdmin in incoming
        val base = old.filterNot { it == LocalToolOption.SupervisionAdmin }
        return if (adminWanted) base + LocalToolOption.SupervisionAdmin else base
    }

    /**
     * MCP 层加严（2026-08-11 重做）：
     * - [SupervisionSettings.lockMcpServers]：只锁**结构性**变更 —— 禁止新增 server、
     *   禁止删除已有 server（删除会被补回）、禁止改地址 / headers / OAuth。
     *   **不再锁 enable 开关**：真正的能力管控已由 `mcpToolFilter` 在 ChatService 收口，
     *   Gate 这层再锁 enable 是重复上锁，还导致"监督期已挂载的 MCP 关不掉也开不了"。
     * - [SupervisionSettings.lockMcpToolToggles]：显式开启时才回滚 server.enable 与工具 enable
     *   （老行为，只许关不许开）。
     */
    private fun sanitizeMcpServers(old: Settings, incoming: Settings): Settings {
        val sup = old.supervision
        if (!sup.lockMcpServers && !sup.lockMcpToolToggles) return incoming
        val oldById = old.mcpServers.associateBy { it.id }
        val incomingById = incoming.mcpServers.associateBy { it.id }

        val guarded: List<McpServerConfig> = incoming.mcpServers.mapNotNull { server ->
            // lockMcpServers：incoming 里凭空多出来的 server（= 新增）丢弃
            val oldServer = oldById[server.id]
                ?: return@mapNotNull if (sup.lockMcpServers) null else server

            // lockMcpServers：地址 / headers / OAuth 等结构性字段回滚为旧值
            val structural = if (sup.lockMcpServers) {
                oldServer.clone(commonOptions = oldServer.commonOptions.copy(
                    enable = server.commonOptions.enable,
                    tools = server.commonOptions.tools,
                    updatedAt = server.commonOptions.updatedAt,
                ))
            } else server

            if (!sup.lockMcpToolToggles) return@mapNotNull structural

            // lockMcpToolToggles：server 与工具的 enable 都只许关不许开
            val oldToolsByName = oldServer.commonOptions.tools.associateBy { it.name }
            val guardedTools = structural.commonOptions.tools.map { tool ->
                val wasEnabled = oldToolsByName[tool.name]?.enable ?: false
                tool.copy(enable = tool.enable && wasEnabled)
            }
            structural.clone(
                commonOptions = structural.commonOptions.copy(
                    enable = structural.commonOptions.enable && oldServer.commonOptions.enable,
                    tools = guardedTools,
                ),
            )
        }

        // lockMcpServers：被删掉的 server 补回（原实现锁住了"开"却放过了"删"，方向反了）
        val restored = if (sup.lockMcpServers) {
            guarded + old.mcpServers.filter { it.id !in incomingById.keys }
        } else guarded

        return incoming.copy(mcpServers = restored)
    }

    private fun sanitizeSupervision(
        old: Settings,
        incoming: Settings,
    ): Settings {
        // 不再按来源分叉：一律走本地加严规则。
        // 远端事件的搬运在 SupervisionSettings.strengthenWith 里完成，不经过这里。
        val base = strengthenLocalSupervision(old.supervision, incoming.supervision)
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
            lockMcpToolToggles = incoming.lockMcpToolToggles || old.lockMcpToolToggles,
            lockSkills = incoming.lockSkills || old.lockSkills,
            // 定时任务总闸：监督期内只许开、不许关（与 lockMcpServers 同款语义，PLAN_SCHEDULE_AGENTS §5.1）
            scheduleAgentsEnabledDuringSupervision =
                incoming.scheduleAgentsEnabledDuringSupervision || old.scheduleAgentsEnabledDuringSupervision,
            unlockGrantorAssistantId = safeGrantor,
            cooldownMinutes = maxOf(incoming.cooldownMinutes, old.cooldownMinutes),
            pendingUnlock = sanitizePendingUnlock(old.pendingUnlock, incoming.pendingUnlock, old),
            // 锁集合只许加锁：移除必须走 AdminBypass（enforceDuringLock 入口已整闸放行）。
            // 注意 §6 洞④：陈旧条目的清理由「非监督时段可自由编辑」+ 管理工具兜住，
            // 不引入自动清理，避免出现「锁得进出不来」。
            lockedConversationIds = old.lockedConversationIds + incoming.lockedConversationIds,
            lockedWorkspacePaths = old.lockedWorkspacePaths + incoming.lockedWorkspacePaths,
            adminScheduleAgentIds = old.adminScheduleAgentIds + incoming.adminScheduleAgentIds,
            // 申诉三参数：**变小 = 更严**（与 cooldownMinutes 方向相反）。
            // 单向 min 的棘轮效应是刻意的，UI 上必须提示「监督期内只能调小」。
            appealCountdownSeconds = minOf(incoming.appealCountdownSeconds, old.appealCountdownSeconds),
            appealMaxExtensions = minOf(incoming.appealMaxExtensions, old.appealMaxExtensions),
            appealExtensionSeconds = minOf(incoming.appealExtensionSeconds, old.appealExtensionSeconds),
            // 延后生效只许变小（0 = 不延后 = 最严）。注意：deferUntil 生效期间
            // isActiveNow() 本就为 false、Gate 不会进来，所以真锁上之后写这个字段一律被清零。
            deferUntil = if (incoming.deferUntil == 0L || old.deferUntil == 0L) 0L
            else minOf(incoming.deferUntil, old.deferUntil),
            // 事件日志：只增并集。日志本身是单调的（可安全合并），
            // 但它 fold 出来的锁态**可以变弱** —— 这就是解锁能落地的原因。
            // 这里绝不能改成「取 old 的日志」：那等于把本机刚产生的解锁事件丢掉。
            eventLog = old.eventLog.merge(incoming.eventLog),
            updatedAt = maxOf(incoming.updatedAt, old.updatedAt, System.currentTimeMillis()),
        ).let { merged ->
            // ★ 日志有话说时以 fold 结果为准，覆盖上面那两个并集出来的锁集合。
            //
            // 上面 `lockedConversationIds = old + incoming` 的并集只是「日志为空」
            // 时的兼容兜底（升级窗口期对端还是旧版本，只推裸锁态）。
            // 一旦存在事件，applyEventLog 会整体覆盖，并集的值不会残留，
            // 因此那把「解不开的锁」在这条路径上也被解除了。
            merged.applyEventLog()
        }
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
        oldSupervision: SupervisionSettings,
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
            // 已批准：本时段内不许改动；但**时段已过**的陈旧记录必须允许清除，
            // 否则守门员工具永久不再挂载（= 一辈子只能解锁一次）。
            PendingUnlock.Status.APPROVED ->
                if (incoming == null && oldSupervision.isUnlockStale(nowMs)) null else old
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
        // 空白名单 = 未配置（见 ToolFilter.allows），不构成任何限制，
        // 因此不能拿它当「更严」的旧值把用户锁死（2026-08-17 死锁事故）。
        if (old.mode == ToolFilter.Mode.WHITELIST && old.items.isEmpty()) return incoming
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
