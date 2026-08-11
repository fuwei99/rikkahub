package me.rerere.rikkahub.data.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 专注监督锁配置（方案 PLAN_SUPERVISION_LOCK）。
 *
 * - [schedules]：周历时间段，命中任一即「监督中」；
 * - [allowedAssistantIds]：监督期内只允许使用的学习助手白名单（空 = 不限制）；
 * - 三个 [ToolFilter]：分别管控本地工具 / 工作区工具 / MCP 工具，黑名单或白名单用户自选；
 * - [lockMcpServers]：监督期禁止新增 / 删除 MCP server、禁止改地址与 headers（enable 开关不锁，
 *   要锁请另开 [lockMcpToolToggles]）；
 * - 紧急解锁：由 [unlockGrantorAssistantId] 指定的「守门员学习助手」通过
 *   `supervision_request_unlock` 工具发起，记录在 [pendingUnlock] 里，
 *   用户在 [cooldownMinutes] 冷却期内可在 UI 确认/拒绝；
 * - [updatedAt]：LWW 时间戳，云同步合并用（监督配置跨设备同步）。
 *
 * 非安全沙箱：用户清除应用数据 / 卸载重装仍能绕过，这是自律工具。
 */
@Serializable
data class SupervisionSettings(
    val enabled: Boolean = false,
    val schedules: List<SupervisionSchedule> = emptyList(),
    val allowedAssistantIds: Set<Uuid> = emptySet(),
    val localToolFilter: ToolFilter = ToolFilter.DEFAULT,
    val workspaceToolFilter: ToolFilter = ToolFilter.DEFAULT,
    val mcpToolFilter: ToolFilter = ToolFilter.DEFAULT,
    val lockMcpServers: Boolean = true,

    /**
     * 监督期内是否锁定 MCP 的 enable 开关（server 级 + 工具级），默认 **false = 不锁**。
     *
     * 2026-08-11 修复：原先由 [lockMcpServers] 一并锁死 enable，导致监督期内
     * 已挂载的 MCP 既开不了也关不掉。真正的能力管控由 [mcpToolFilter] 在
     * ChatService 收口，Gate 这层默认不再重复上锁；只有显式打开这个开关才恢复
     * 「只许关不许开」的老行为。
     */
    val lockMcpToolToggles: Boolean = false,

    /**
     * 监督期内是否锁定白名单助手的 `enabledSkills`，默认 **false = 不锁**。
     *
     * 原实现无条件回滚 enabledSkills，等于监督期整个 skill 系统失效
     * （createSkillTools 依赖 enabledSkills，空集合连 use_skill 都不注册）。
     */
    val lockSkills: Boolean = false,

    /**
     * 监督期内是否运行定时任务（Schedule Agents，PLAN_SCHEDULE_AGENTS §5.1）。
     *
     * 默认 true：监督期内查岗等任务照常跑（定时任务是监督的一部分）。
     * 监督期内 Gate 只许 true→true（开启）；尝试 false（关闭）会被回滚成开启。
     * 与模板的 `onlyDuringSupervision` 区别：总闸管**所有** schedule agent，
     * `onlyDuringSupervision` 只管单个任务只在监督期内跑。
     */
    val scheduleAgentsEnabledDuringSupervision: Boolean = true,

    /**
     * 监督期内允许调用解锁工具的「守门员助手」id。必须是白名单中的一个。
     * null / 不在白名单 → 监督期完全不可解锁（最严）。
     */
    val unlockGrantorAssistantId: Uuid? = null,

    /**
     * 解锁冷却（分钟）。守门员工具调用成功后写入 [pendingUnlock]，
     * 需等这么多分钟，且用户在 UI 点「确认解锁」后才真正生效。
     * 0 = 工具调用后用户确认即可立即解锁（不推荐，但允许）。
     */
    val cooldownMinutes: Int = 5,

    /** 待处理 / 已生效的解锁请求；null = 没有。 */
    val pendingUnlock: PendingUnlock? = null,

    /**
     * 「延后生效」截止时刻（epoch millis），0 = 无延后。
     *
     * 场景：用户在 UI 上编辑时段并点「保存」时，若新配置恰好**此刻已命中**某个时段，
     * 会被问一句「是否立即开始监督」。选「否」时把本次时段的结束时刻写进这里，
     * [isActiveAt] 在 `now < deferUntil` 期间一律返回 false —— 也就是
     * 「本段跳过，下一段再锁」，避免时段还没配完就把自己铐上（见 SettingSupervisionPage 草稿模式）。
     *
     * 加严方向是**变小**：监督期内 Gate 只许 `min(old, incoming)`，
     * 所以已经锁上之后无法靠写这个字段给自己放假。过期后自然失效，无需清理。
     */
    val deferUntil: Long = 0L,

    val updatedAt: Long = 0L,
) {
    /**
     * 取本配置与 [other] 的「更严」并集，用于云同步下来时在监督期内加强本机配置
     * （见 PLAN_SUPERVISION_LOCK §3.6）。
     */
    fun strengthenWith(other: SupervisionSettings): SupervisionSettings {
        // 守门员 id：更严的一侧是 null（无人能解锁）；两边都非空时取本机值
        // （不允许通过同步把守门员换成另一台设备上不被信任的助手）
        val mergedGrantor = unlockGrantorAssistantId ?: other.unlockGrantorAssistantId

        // pendingUnlock：任一侧为空（无待处理）就取空（保持锁定）；
        // 都非空时取较晚的 expiresAt（更晚解锁 = 更严）
        val mergedPending = when {
            pendingUnlock == null || other.pendingUnlock == null ->
                pendingUnlock ?: other.pendingUnlock
            else ->
                if (pendingUnlock.expiresAt >= other.pendingUnlock.expiresAt) pendingUnlock
                else other.pendingUnlock
        }

        return SupervisionSettings(
            enabled = enabled || other.enabled,
            schedules = (schedules + other.schedules).distinctBy { it.id },
            allowedAssistantIds = if (allowedAssistantIds.isEmpty() || other.allowedAssistantIds.isEmpty()) {
                if (allowedAssistantIds.isEmpty()) other.allowedAssistantIds else allowedAssistantIds
            } else {
                allowedAssistantIds intersect other.allowedAssistantIds
            },
            localToolFilter = localToolFilter.strengthenWith(other.localToolFilter),
            workspaceToolFilter = workspaceToolFilter.strengthenWith(other.workspaceToolFilter),
            mcpToolFilter = mcpToolFilter.strengthenWith(other.mcpToolFilter),
            lockMcpServers = lockMcpServers || other.lockMcpServers,
            lockMcpToolToggles = lockMcpToolToggles || other.lockMcpToolToggles,
            lockSkills = lockSkills || other.lockSkills,
            scheduleAgentsEnabledDuringSupervision =
                scheduleAgentsEnabledDuringSupervision || other.scheduleAgentsEnabledDuringSupervision,
            unlockGrantorAssistantId = mergedGrantor,
            cooldownMinutes = maxOf(cooldownMinutes, other.cooldownMinutes),
            pendingUnlock = mergedPending,
            // 延后生效：更严 = 更早结束宽限。任一侧为 0（无延后）即取 0
            deferUntil = if (deferUntil == 0L || other.deferUntil == 0L) 0L
            else minOf(deferUntil, other.deferUntil),
            updatedAt = maxOf(updatedAt, other.updatedAt),
        )
    }
}

/**
 * 一次由守门员学习助手发起的紧急解锁请求。
 *
 * - [requestedAt]：AI 调用工具的时间
 * - [expiresAt]：cooldown 结束、用户可以点「确认解锁」的最早时间
 * - [reason]：AI 给出的解锁理由（展示给用户确认）
 * - [status]：[PENDING]（冷却中，可取消）→ [READY]（冷却结束，可确认/拒绝）
 *   → [APPROVED]（已生效，[SupervisionSettings.isActiveAt] 据此返回 false）→ [REJECTED]/[CANCELLED]
 */
@Serializable
data class PendingUnlock(
    val requestedAt: Long,
    val expiresAt: Long,
    val reason: String = "",
    val grantedByAssistantId: Uuid? = null,
    val conversationId: Uuid? = null,
    val status: Status = Status.PENDING,
) {
    @Serializable
    enum class Status { PENDING, READY, APPROVED, REJECTED, CANCELLED }
}

@Serializable
data class SupervisionSchedule(
    val id: Uuid = Uuid.random(),
    /** 1..7 对应周一..周日（与 [DayOfWeek.isoDayNumber] 对齐）。 */
    val daysOfWeek: Set<Int> = emptySet(),
    /** 起始分钟（0..1440）。 */
    val startMinute: Int = 0,
    /** 结束分钟（0..1440）。允许 [startMinute] > [endMinute]，表示跨夜区间。 */
    val endMinute: Int = 0,
) {
    fun contains(minuteOfDay: Int, dow: Int): Boolean {
        if (dow !in daysOfWeek) return false
        return if (startMinute <= endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            // 跨夜：例如 22:00(1320) - 06:00(360)
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    /**
     * 返回包含 [epochMillis] 的本次监督会话的**结束时刻**（epoch millis）。
     * 若该时刻不在本 schedule 的激活区间内则返回 null。
     *
     * 用途：紧急解锁 APPROVED 后，只在「发起解锁的这次会话」内有效；
     * 下次会话开始自动重新锁定。
     */
    fun activationSessionEndAt(epochMillis: Long): Long? {
        val tz = TimeZone.currentSystemDefault()
        val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(tz)
        val dow = dt.dayOfWeek.isoDayNumber
        if (dow !in daysOfWeek) return null
        val minute = dt.hour * 60 + dt.minute

        val endDate: kotlinx.datetime.LocalDate
        if (startMinute <= endMinute) {
            if (minute !in startMinute until endMinute) return null
            endDate = dt.date
        } else {
            // 跨夜会话：昨天 start ~ 今天 end（[0, endMinute) 段），或今天 start ~ 明天 end
            if (minute >= startMinute) {
                endDate = dt.date.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
            } else if (minute < endMinute) {
                endDate = dt.date
            } else {
                return null
            }
        }
        val endLocal = kotlinx.datetime.LocalDateTime(
            year = endDate.year,
            monthNumber = endDate.monthNumber,
            dayOfMonth = endDate.day,
            hour = endMinute / 60,
            minute = endMinute % 60,
        )
        return endLocal.toInstant(tz).toEpochMilliseconds()
    }
}

@Serializable
data class ToolFilter(
    val mode: Mode = Mode.BLACKLIST,
    /** BLACKLIST 时为禁用集合；WHITELIST 时为唯一允许集合。 */
    val items: Set<String> = emptySet(),
) {
    @Serializable
    enum class Mode {
        @SerialName("blacklist")
        BLACKLIST,

        @SerialName("whitelist")
        WHITELIST,
    }

    fun allows(toolName: String): Boolean = when (mode) {
        Mode.BLACKLIST -> toolName !in items
        Mode.WHITELIST -> toolName in items
    }

    /**
     * 两个过滤器的「更严」合并：
     * - 黑 ∪ 黑：items 并集；
     * - 白 ∩ 白：items 交集；
     * - 黑 + 白：白名单更严，但要再被黑名单过滤一次（白名单 ∩ 黑名单的补集）；
     * - 白 + 黑：同上。
     */
    fun strengthenWith(other: ToolFilter): ToolFilter {
        if (this.mode == Mode.BLACKLIST && other.mode == Mode.BLACKLIST) {
            return ToolFilter(Mode.BLACKLIST, items union other.items)
        }
        if (this.mode == Mode.WHITELIST && other.mode == Mode.WHITELIST) {
            // 空白名单 = 全禁（最严）；交集时任一为空即全禁
            val intersected = if (items.isEmpty() || other.items.isEmpty()) {
                emptySet()
            } else {
                items intersect other.items
            }
            return ToolFilter(Mode.WHITELIST, intersected)
        }
        val (white, black) = if (mode == Mode.WHITELIST) this to other else other to this
        // 白名单 ∩ 黑名单补集
        val tightened = white.items.filterNot { it in black.items }.toSet()
        return ToolFilter(Mode.WHITELIST, tightened)
    }

    companion object {
        val DEFAULT = ToolFilter()
    }
}

/**
 * 判断某个时刻是否处于监督时段。
 *
 * - [SupervisionSettings.pendingUnlock] 状态为 [PendingUnlock.Status.APPROVED] 时视为已解锁（返回 false）；
 *   但该解锁只对「发起请求的本次时段」有效——下一次时段开始后自动重新锁定
 *   （防止一次解锁永久生效）。
 * - 处于 PENDING/READY 期间监督仍生效（要等用户最后点确认）；
 * - REJECTED/CANCELLED 等同于无 pending。
 */
fun SupervisionSettings.isActiveAt(instant: Instant): Boolean {
    if (!enabled) return false
    val nowMs = instant.toEpochMilliseconds()
    // 用户保存新时段时选了「下一段再开始」：本段宽限，不锁
    if (deferUntil > 0L && nowMs < deferUntil) return false
    val pending = pendingUnlock
    if (pending?.status == PendingUnlock.Status.APPROVED) {
        // 检查这次解锁是否仍在「发起请求的那次时段」内
        val stillSameSession = schedules.any { schedule ->
            val sessionEnd = schedule.activationSessionEndAt(pending.requestedAt) ?: return@any false
            nowMs < sessionEnd
        }
        if (stillSameSession) return false
        // 时段已切换：忽略过期解锁，继续按当前时间判断
    }
    if (schedules.isEmpty()) return false
    val dt: LocalDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val minuteOfDay = dt.hour * 60 + dt.minute
    val dow = dt.dayOfWeek.isoDayNumber
    return schedules.any { it.contains(minuteOfDay, dow) }
}

/** PendingUnlock 在冷却结束后应自动从 PENDING 变成 READY；这里返回该用哪个状态。 */
fun PendingUnlock.effectiveStatus(nowMs: Long = System.currentTimeMillis()): PendingUnlock.Status = when {
    status == PendingUnlock.Status.PENDING && nowMs >= expiresAt -> PendingUnlock.Status.READY
    else -> status
}

fun SupervisionSettings.isActiveNow(): Boolean =
    isActiveAt(Instant.fromEpochMilliseconds(System.currentTimeMillis()))

/**
 * 若 [nowMs] 此刻**命中**任一时段（纯按 [schedules] 判断，不看 enabled / 解锁 / 延后状态），
 * 返回本次时段的结束时刻（多个时段重叠时取最晚的那个）；不命中则返回 null。
 *
 * 用途：UI 保存时段时判断「新配置是不是马上就要锁」，以及计算
 * [SupervisionSettings.deferUntil] 该写到几点（= 跳过本段）。
 */
fun SupervisionSettings.currentSessionEndAt(
    nowMs: Long = System.currentTimeMillis(),
): Long? = schedules.mapNotNull { it.activationSessionEndAt(nowMs) }.maxOrNull()

/** 工具方法：「若 [condition] 为 true 则把 [key] 加入集合」，用于 UI 层快速加严。 */
fun ToolFilter.withItem(key: String, included: Boolean): ToolFilter {
    val next = if (included) items + key else items - key
    return copy(items = next)
}
