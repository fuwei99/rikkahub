package me.rerere.rikkahub.data.ai.schedule

import android.util.Log
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.agent.AgentBridge
import me.rerere.rikkahub.data.ai.agent.AgentLimits
import me.rerere.rikkahub.data.ai.agent.AgentMessage
import me.rerere.rikkahub.data.ai.agent.AgentMessageKind
import me.rerere.rikkahub.data.ai.agent.AgentSenderRole
import me.rerere.rikkahub.data.ai.agent.AgentStatuses
import me.rerere.rikkahub.data.ai.agent.AgentUrgency
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_PROTOCOL_NOTE
import me.rerere.rikkahub.data.ai.agent.SCHEDULE_VIRTUAL_PARENT_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.AgentSessionDAO
import me.rerere.rikkahub.data.model.isActiveNow
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.core.BackgroundSyncKeepAlive
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.applyPlaceholders
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ScheduleAgentRunner"

/**
 * Schedule Agent 触发执行（PLAN_SCHEDULE_AGENTS §3.2）：模拟父节点投递。
 *
 * 流程：
 * 1. 读模板，enabled=false 直接返回（下一次闹钟已排好）；
 * 2. 监督总闸 + 窗口/定时点放行判定（跳过时下一次照常排）；
 * 3. 按 conversationMode 找/建会话（reuse 复用常驻，fresh 每次新建）；
 * 4. **僵死自愈**：DB 里挂着 running/waiting_* 但**内存里没有生成任务**、且已超过
 *    `min(staleRunMinutes, STALE_GRACE_MINUTES)` 无进展 → 判为提前断开 / 协程被取消 /
 *    进程被杀留下的尸体，回收状态后照常派活；
 * 5. **忙时不丢活**：只有内存里**真挂着生成任务**（isGeneratingNow）才算忙，才按
 *    `deliverWhenBusy` 把任务以 SILENT 投进收件箱（不唤醒、不打断），当前轮结束后
 *    onGenerationDone 会自动唤醒它读积压；内存里空着就一律走 MAIL 唤醒 ——
 *    否则信沉在箱底、没有任何回调来收，UI 却一直显示「运行中」；
 * 6. 任务文本占位符展开 → 以 system 署名投递 `[schedule]` 系统消息到收件箱 + 唤醒
 *    （复用 [AgentBridge.deliver]，消息无条件入箱，目标空闲后自动开一轮生成）；
 * 7. 下一次触发由 [ScheduleAgentScheduler.scheduleNext] 在 Receiver 里排好（与执行成败解耦）。
 */
class ScheduleAgentRunner(
    private val manager: ScheduleAgentManager,
    private val bridge: AgentBridge,
    chatService: ChatService,
    private val settingsStore: SettingsStore,
    private val agentSessionDao: AgentSessionDAO,
    private val conversationRepo: ConversationRepository,
) {
    suspend fun run(templateId: String) {
        val template = manager.getTemplate(templateId) ?: run {
            Log.w(TAG, "template not found: $templateId")
            return
        }
        if (!template.enabled) return

        // ChatService 由 Koin 构造注入（chatService 参数）：其 init 里
        // agentBridge.attach(Deps) 已完成——冷启动（闹钟拉起进程）时
        // get(Runner) 即连带创建 ChatService，唤醒一定发得出去。

        val sup = settingsStore.settingsFlow.first().supervision
        // 监督总闸：监督期内总闸关闭 → 所有定时任务跳过（不只查岗）
        if (sup.isActiveNow() && !sup.scheduleAgentsEnabledDuringSupervision) {
            Log.i(TAG, "skip ${template.id}: schedule agents disabled during supervision")
            return
        }
        // 窗口/定时点放行：闹钟被 Doze 推迟到窗口外、或 BOOT 重排踩到奇怪时刻时兜住。
        // 未配 windows/dailyTimes 的模板永远放行（老行为）。
        val trigger = ScheduleTimePlanner.resolveTrigger(template) ?: run {
            Log.i(TAG, "skip ${template.id}: outside all windows / daily times")
            return
        }

        val resolution = resolveSession(template) ?: run {
            Log.e(TAG, "failed to resolve session for ${template.id}")
            return
        }
        val sessionId = resolution.id

        // 模板工具**并集写进对话持久状态**（2026-08-23）：reuse 复用的会话可能是很久前建的，
        // 模板后来加的 allowedMcpTools（如微信 MCP）在这里补齐。只增不减 ——
        // 用户/AI 自己额外开的工具不会被每次触发重置掉。
        bridge.injectTemplateTools(template, sessionId)

        // ---- 会话忙闲判定（2026-08-21 大修：原实现是「阻塞后永久哑掉」的病根）----
        //
        // 老逻辑：DB status 是 running/waiting_* 就整轮 return。问题在于这三个状态
        // 全靠进程内的回调改回来 —— Rikkahub 被杀 / 手机重启时最后一笔写的就是 running，
        // 从此每一次闹钟都撞上它、每一次都 return，任务永久死亡，UI 还显示「运行中」。
        //
        // 新逻辑分三种情况：
        //  a) 内存里真有生成在跑 → 按 deliverWhenBusy 决定「静默入箱」还是跳过；
        //     内存里空着（哪怕还在宽限期内没判死）→ 一律唤醒，绝不静默入箱；
        //  b) 僵死（DB 说忙、内存无生成、且超过宽限）→ 回收状态后照常派活；
        //  c) 空闲 → 走原来的续跑 / 派活路径。
        var busySilent = false
        if (!resolution.created) {
            val row = agentSessionDao.getByChildId(sessionId.toString()) ?: return
            val blocked = row.status == AgentStatuses.RUNNING ||
                row.status == AgentStatuses.WAITING_PARENT ||
                row.status == AgentStatuses.WAITING_APPROVAL
            if (blocked) {
                val stale = staleReason(template, sessionId)
                if (stale == null) {
                    // 情况 a：没判死。但「没判死」≠「真在跑」——只有内存里确实挂着
                    // 生成任务（isGeneratingNow）才算真忙。若内存里空着（只是还没过宽限期），
                    // 静默入箱就是往一个没人收的箱子里塞信：没有 onGenerationDone 就没有
                    // 唤醒，信永远躺着、UI 却一直显示「运行中」。这时必须照常唤醒。
                    val reallyGenerating = bridge.isGeneratingNow(sessionId)
                    if (!template.deliverWhenBusy && reallyGenerating) {
                        Log.i(TAG, "skip ${template.id}: session busy (${row.status})")
                        return
                    }
                    busySilent = reallyGenerating
                    Log.i(
                        TAG,
                        "${template.id}: session ${row.status} generating=$reallyGenerating -> " +
                            if (busySilent) "queue silently" else "deliver + wake",
                    )
                } else {
                    // 情况 b：尸体。回收后重新派活。
                    Log.w(TAG, "${template.id}: stale ${row.status} detected ($stale) -> recover")
                    bridge.recoverStaleSchedule(sessionId, stale)
                }
            }

            if (!busySilent) {
                // 提前结束续跑：上一轮没汇报就收尾 → 先催它继续做完老任务，
                // 但**不能因为催不动就永久跳过**（老实现在达到上限后每轮直接 return，
                // 等于任务从此报废）。到上限就把计数清零、按新一轮派活重新开始。
                val reminderLimit = template.prematureEndReminders.takeIf { it > 0 }
                    ?: AgentLimits.MAX_PREMATURE_END_REMINDERS
                val unfinished = row.prematureEndCount > 0 &&
                    (row.status == AgentStatuses.STOPPED || row.status == AgentStatuses.IDLE)
                if (unfinished && row.prematureEndCount < reminderLimit) {
                    if (bridge.remindScheduleTask(sessionId)) return
                }
                // 新一轮 = 新的提醒额度（DAO resetPrematureEnd 的设计口径）
                runCatching { agentSessionDao.resetPrematureEnd(sessionId.toString()) }
            }
        }

        val taskText = template.taskPrompt.applyPlaceholders(
            "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
            "name" to template.name,
            "window" to trigger.windowName,
            "tag" to trigger.tag,
        )
        val body = buildString {
            append("<from role=\"${AgentSenderRole.SYSTEM}\" title=\"定时任务：${template.name}\">")
            append("\n[schedule] ")
            append(taskText)
            // 协议兜底重申：绑定的助手可能 allowConversationSystemPrompt=false（拿不到
            // customSystemPrompt），且常驻会话的历史会被自动压缩。不重申就会出现
            // 「永远不调 agent_report → 永远判为提前结束」。
            append("\n\n")
            append(SCHEDULE_PROTOCOL_NOTE)
        }

        val err = bridge.deliver(
            AgentMessage(
                target = sessionId,
                text = body,
                kind = AgentMessageKind.SYSTEM,
                senderRole = AgentSenderRole.SYSTEM,
                senderTitle = template.name,
                templateId = template.id,
            ),
            // 忙的时候只入箱不唤醒：当前轮结束后 onGenerationDone → maybeRequestWake
            // 会自动把积压的派活读掉，既不打断也不丢活。
            urgency = if (busySilent) AgentUrgency.SILENT else AgentUrgency.MAIL,
        )
        if (err != null) {
            Log.w(TAG, "deliver failed for ${template.id}: $err")
            return
        }

        // 后台同步保活（2026-09-11）：闹钟能捅穿 Doze 把 CPU 叫醒，同步却在 onStop
        // 里被全砍了，只剩个随缘的 WorkManager。结果就是 Agent 在后台写的东西全堆在
        // outbox 里，等下次开 App 一次性爆发推上云 —— 那正是造 Fork 的高危时刻。
        // 这里派活即顶住同步，本轮生成结束（onGenerationDone）自动释放。
        BackgroundSyncKeepAlive.acquire(sessionId)
    }

    /**
     * 僵死判定：@return null = 没僵死（真在忙）；非 null = 僵死原因（供日志/摘要）。
     *
     * 判定链：
     * 1. 进程里确实没有生成任务在跑（[AgentBridge.isGeneratingNow]）——这是**硬事实**：
     *    生成任务一旦挂上就是内存里的活对象；DB 里的 running 却可能是上一轮提前断开 /
     *    协程被取消 / 忘了 agent_report 留下的尸体，没人写回终态；
     * 2. 会话最后一次落库（`Conversation.updateAt`）距今超过宽限阈值。
     *
     * **宽限为什么不再用整个 staleRunMinutes（2026-11-11 修「信沉收件箱」）**：
     * 查岗每 10 分钟一轮，而 staleRunMinutes 配的是 20 分钟。进程内明明没有生成在跑、
     * DB 却挂着 running 时，若还按 20 分钟等，等于头两轮触发全判「忙」→ 静默入箱 →
     * 没有任何 onGenerationDone 回调来唤醒 → 信永远沉在收件箱里，任务看着「运行中」其实死了。
     * 从 requestWake 到 isGeneratingNow 变 true 只有秒级延迟，[STALE_GRACE_MINUTES] 分钟
     * 足够覆盖，多等一分钟都是在丢轮次。
     */
    private suspend fun staleReason(template: ScheduleAgentTemplate, sessionId: Uuid): String? {
        val staleMinutes = template.staleRunMinutes
        if (staleMinutes <= 0) return null
        // 内存里真有生成任务 = 真在忙，无条件放过（生成中 updateAt 可能长时间不刷新）
        if (bridge.isGeneratingNow(sessionId)) return null
        val conversation = conversationRepo.getConversationById(sessionId) ?: return "会话已不存在"
        val idleMillis = System.currentTimeMillis() - conversation.updateAt.toEpochMilli()
        val threshold = minOf(staleMinutes, STALE_GRACE_MINUTES).toLong() * 60_000L
        if (idleMillis < threshold) return null
        return "进程内无生成任务，且已 ${idleMillis / 60_000L} 分钟无进展（宽限 ${threshold / 60_000L} 分钟）"
    }

    /**
     * reuse：找模板对应的常驻会话（会话被删 / 撞消息数上限则重建）；fresh：每次新建。
     *
     * **轮换**是 reuse 模式能长期跑的关键：常驻会话的 messageNodes 只增不减
     * （自动压缩折叠历史但不删节点），撞 [AgentLimits.MAX_MESSAGE_NODES] 后
     * `deliver` 会永久拒收。所以接近阈值时主动换一条新会话并归档旧的——
     * 上下文连续性让位于「任务永远活着」。
     */
    private data class SessionResolution(
        val id: Uuid,
        val created: Boolean,
    )

    private suspend fun resolveSession(template: ScheduleAgentTemplate): SessionResolution? {
        if (template.reuseConversation) {
            val row = agentSessionDao.getByTemplateId(template.id, SCHEDULE_VIRTUAL_PARENT_ID.toString())
            // reuse 模式的常驻会话：只有 ARCHIVED 才算「这个会话不要了」。
            // done/idle/error 都要复用 —— 查岗每轮跑完都会 agent_report(done=true)
            // 把 status 写成 DONE，若把 DONE 也当弃用就等于每轮新建一个会话。
            if (row != null && row.status != AgentStatuses.ARCHIVED) {
                val id = runCatching { Uuid.parse(row.childId) }.getOrNull()
                val conversation = id?.let { conversationRepo.getConversationById(it) }
                if (id != null && conversation != null) {
                    // ---- 轮换双判据：节点数 OR token，任一撞线就换会话（2026-08-24）----
                    //
                    // 节点数判据（老）：留一轮余量，撞死线前就换。
                    // 但节点数不代表上下文大小 —— 一轮可能只产 2 个节点却吃 3 万 token
                    // （长 tool 输出 / grep 结果 / 屏幕时间明细），拿节点数估上下文
                    // 等于拿鞋码估体重。所以补 token 判据。
                    //
                    // token 判据直接用 template.maxTotalTokens，**不另设阈值**：
                    // 常驻会话不需要「熔断」这个概念（那是一次性 subagent 防烧钱用的，
                    // 见 SubagentRunner，超了就地 error 掐掉）。常驻会话只需要「满了就换」，
                    // 所以同一个数既是容量上限也是轮换线，没有第二个旋钮。
                    //
                    // 口径 = agent_session.totalTokens，由 AgentBridge.latestContextTokens
                    // 写入：单次请求的整个上下文（非逐条累加），自动压缩生效后会真的回落。
                    val nodes = conversation.messageNodes.size
                    val limit = template.effectiveMaxMessageNodes(AgentLimits.MAX_MESSAGE_NODES)
                    val margin = template.effectiveRotateMargin(AgentLimits.MAX_MESSAGE_NODES)
                    val nodesExceeded = nodes >= limit - margin

                    val tokenLimit = template.maxTotalTokens
                    val tokens = row.totalTokens
                    val tokensExceeded = tokenLimit > 0 && tokens >= tokenLimit

                    if (!nodesExceeded && !tokensExceeded) {
                        // 模板的自动压缩配置回灌常驻会话（2026-08-21）：
                        // reuse 模式下会话是 spawn 那一刻建的，之后改模板 JSON 不会自动生效，
                        // 老会话会永远按当初的（通常是 null）配置跑 → 用户改了模板却「没反应」。
                        if (conversation.autoCompressOverride != template.autoCompress) {
                            runCatching {
                                conversationRepo.updateConversation(
                                    conversation.copy(autoCompressOverride = template.autoCompress)
                                )
                            }.onFailure {
                                Log.w(TAG, "sync autoCompress to reused session failed for ${template.id}", it)
                            }
                        }
                        return SessionResolution(id = id, created = false)
                    }
                    val reason = when {
                        nodesExceeded && tokensExceeded -> "nodes+tokens"
                        nodesExceeded -> "nodes"
                        else -> "tokens"
                    }
                    Log.i(
                        TAG,
                        "rotating session for ${template.id} by $reason: " +
                            "nodes=$nodes/${limit - margin} tokens=$tokens/$tokenLimit"
                    )
                    runCatching { bridge.archive(id) }
                        .onFailure { Log.w(TAG, "archive old session failed for ${template.id}", it) }
                }
            }
        }
        return SessionResolution(id = bridge.spawnSchedule(template), created = true)
    }

    private companion object {
        /**
         * 内存里没有生成任务时，判定 DB 的 running 为尸体所需的静默分钟数。
         *
         * 只用来覆盖「刚 requestWake、协程还没挂上 isGeneratingNow」的秒级竞态，
         * 不是给 [ScheduleAgentTemplate.staleRunMinutes] 兜底——那个值管的是
         * 「内存里说有生成、但长时间没进展」的另一类僵死。
         */
        const val STALE_GRACE_MINUTES = 2
    }
}
