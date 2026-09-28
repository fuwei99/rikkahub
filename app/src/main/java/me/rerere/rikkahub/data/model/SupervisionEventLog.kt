package me.rerere.rikkahub.data.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 监督事件日志与 fold 引擎（大统一重构 v2 §3，阶段 B）。
 *
 * 设计要点见 [SupervisionEvent] 的类注释。这里只放三件事：
 * 1. [SupervisionWindow.idAt]：由当前时间算出所属窗口 id
 * 2. [merge]：两端事件集合的 CRDT 合并（OR-Set，按 id 去重）
 * 3. [fold]：事件序列 → 锁状态
 */
object SupervisionWindow {

    /**
     * 返回 [nowMs] 所处**监督日**的窗口 id，不在任何时段内返回 null。
     *
     * ## 为什么窗口是「天」而不是「一节课」
     *
     * 旧实现用 `<scheduleId>:<本段结束时刻>` 做窗口 id —— 也就是**每个 schedule 条目
     * 一个窗口**。而真实时段表是被切成一小节一小节的（08:30-09:50 / 10:00-10:50 /
     * 11:00-11:50 / 12:30-14:50 …，中间全是 10 分钟课间），于是
     *
     * ```
     * 08:35  锁上对话（windowId = 4a15f86e:09:50）
     * 09:50  下课铃一响 → 窗口换掉
     * 10:00  新窗口，08:35 那条锁事件 windowId 不匹配 → 被 fold 直接跳过
     *        → 锁没了 💀（一次课间都熬不过去）
     * ```
     *
     * 用户实测原话：「锁定还是会在一次监督时段后失效」。根因就是**把「课表切块」这个
     * 纯调度产物当成了锁的生命周期**：课间是放风的、监督本来就关着，锁要不要续上跟
     * 课表怎么切片毫无关系，只跟「今天这一整天的监督还没结束」有关。
     *
     * 所以窗口粒度抬到**监督日**：一天之内（含课间、午休）算同一个窗口，锁/解锁都跨
     * 小节生效；跨天自动重来。`SupervisionEvent.windowId` 那条「不绑窗口的解锁 = 永久
     * 解锁」的安全属性依然成立 —— 一天一清，不是永不失效。
     *
     * ## 换日点 06:00（不是 00:00）
     *
     * 时段表里有 01:00-06:00 的夜间（熄灯断电）段，它属于**前一天**的尾巴：
     * 「周一凌晨 01:30」在语义上是周一的熬夜，不是新的一天。若按 00:00 换日，00:00
     * 一到当天的锁全部蒸发、然后 01:00 夜间段又是一个新窗口，来回抽搐。所以换日点定在
     * 06:00：`[今天 06:00, 明天 06:00)` 为一个监督日。
     *
     * ## 格式
     *
     * `day:<监督日起始时刻 epoch ms>`，例如 `day:1790570400000`。
     * 带绝对时间戳是为了让 [matches] 能在不依赖时区重算的前提下做「旧格式兼容」判定。
     */
    fun idAt(settings: SupervisionSettings, nowMs: Long): String? {
        // 必须真的身处某个监督时段内，否则没有「窗口」可言 ——
        // 锁/解锁事件本来就不该在时段外产生（见 SupervisionEventFactory.NotInWindow）。
        val inside = settings.schedules.any { it.activationSessionEndAt(nowMs) != null }
        if (!inside) return null
        return "$DAY_PREFIX${dayStartMs(nowMs)}"
    }

    /** 便捷重载 */
    fun idAt(settings: SupervisionSettings, instant: Instant): String? =
        idAt(settings, instant.toEpochMilliseconds())

    /** [nowMs] 所属监督日的起始时刻（本地 06:00；凌晨 0-6 点算前一天）。 */
    fun dayStartMs(nowMs: Long): Long {
        val tz = TimeZone.currentSystemDefault()
        val dt = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)
        // 先取「今天 06:00」，没过就退一天。
        // 刻意不用 LocalDate.plus/minus 那套：0.8.0 里它的可见性随版本变过，
        // 这里只用 LocalDateTime + toInstant（本文件/本仓已有用法），少一个编译风险点。
        val todaySix = LocalDateTime(
            year = dt.year,
            monthNumber = dt.monthNumber,
            dayOfMonth = dt.day,
            hour = DAY_ROLLOVER_HOUR,
            minute = 0,
        ).toInstant(tz).toEpochMilliseconds()
        return if (nowMs >= todaySix) todaySix else todaySix - DAY_MS
    }

    /**
     * 事件自带的 windowId 是否落在 [currentWindowId] 这个窗口里。
     *
     * 直接比较之外，还兼容**旧格式** `<scheduleId>:<本段结束时刻>`：那个时刻只要落在
     * 当前监督日内，就算同一窗口。作用有两个：
     * 1. 升级窗口期：设备上还躺着当天早些时候产生的旧格式锁事件，不能一升级就全废；
     * 2. 时区/时段表微调：把 09:50 改成 09:45 不会把当天的锁整批打成无效。
     *
     * ⚠️ 新格式（`day:` 前缀）**不走**兼容分支：否则「昨天 day:X」会被当成
     * 「今天某个时刻」，隔夜锁复活。
     */
    fun matches(eventWindowId: String, currentWindowId: String?): Boolean {
        if (eventWindowId == currentWindowId) return true
        if (currentWindowId == null || !currentWindowId.startsWith(DAY_PREFIX)) return false
        if (eventWindowId.startsWith(DAY_PREFIX)) return false
        val dayStart = currentWindowId.removePrefix(DAY_PREFIX).toLongOrNull() ?: return false
        val legacyEnd = eventWindowId.substringAfterLast(':', "").toLongOrNull() ?: return false
        return legacyEnd >= dayStart && legacyEnd < dayStart + DAY_MS
    }

    /** 监督日换日时刻（本地小时）。凌晨 0-6 点算前一天的尾巴。 */
    private const val DAY_ROLLOVER_HOUR = 6
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val DAY_PREFIX = "day:"
}

/**
 * 监督事件集合。作为一个整体存进 `settings.supervision` 分片。
 *
 * @param events 事件列表；顺序不重要，[fold] 会按 hlc 排序
 */
@Serializable
data class SupervisionEventLog(
    val events: List<SupervisionEvent> = emptyList(),
) {

    /**
     * OR-Set 合并：按事件 id 取并集。
     *
     * 为什么是并集：事件是**不可变的历史事实**，两端都该看到全部事实。
     * 状态的「减弱」由 [fold] 时的 hlc 顺序表达，不靠删事件实现。
     *
     * 同 id 冲突（理论上不该发生，除非 uuid 碰撞或有人改了事件）取 hlc 较大者，
     * 保证两端得出同一结果。
     */
    fun merge(other: SupervisionEventLog): SupervisionEventLog {
        if (other.events.isEmpty()) return this
        if (events.isEmpty()) return other
        val byId = LinkedHashMap<String, SupervisionEvent>(events.size + other.events.size)
        (events + other.events).forEach { e ->
            val existing = byId[e.id]
            if (existing == null || e.hlc > existing.hlc) byId[e.id] = e
        }
        return SupervisionEventLog(byId.values.sortedBy { it.hlc })
    }

    fun append(event: SupervisionEvent): SupervisionEventLog =
        SupervisionEventLog(events + event)

    /**
     * 把事件序列折叠成锁状态。
     *
     * @param currentWindowId 当前所处窗口（[SupervisionWindow.idAt] 的结果，**监督日**粒度）。
     *   为 null 表示当前不在任何监督时段 —— 此时**不带 expireAt 的**窗口级锁一律不生效
     *   （与现有 `isActiveAt` 的语义一致：这把锁不是「永久封存对话」的工具）。
     *   带 expireAt 的锁不看这个参数，只认自己的绝对截止时刻。
     *
     *   窗口比较一律走 [SupervisionWindow.matches]（含旧格式兼容），别在这里写 ==。
     *
     * @param nowMs 用于判定事件自带的 [SupervisionEvent.expireAt] 是否已到。
     *   传当前时刻即可；测试里可以传假时间。
     *
     * @return 折叠出的锁集合与 enabled 覆盖值
     */
    fun fold(currentWindowId: String?, nowMs: Long = System.currentTimeMillis()): FoldResult {
        // 按 hlc 全序重放。同 hlc 时按 id 定序，保证两端结果一致
        val ordered = events.sortedWith(compareBy({ it.hlc }, { it.id }))

        val lockedConversations = mutableSetOf<Uuid>()
        val lockedPaths = mutableSetOf<String>()
        var enabledOverride: Boolean? = null

        ordered.forEach { e ->
            // ★ 窗口级事件只在其所属窗口（= 监督日）内参与计算 —— **除非它显式带了 expireAt**。
            //
            // 带了 expireAt = 「绝对截止」语义：跨天、跨窗口一直有效，到点才解。
            //
            // 没带 expireAt（= 0）走「监督日」语义：当天所有小节（含课间、午休）都算同一
            // 窗口，第二天 06:00 换日才失效。2026-09-28 修复 —— 旧实现窗口 = 单个 schedule
            // 条目，课间一打铃就换窗口，锁连一次课间都熬不过去。
            //
            // ⚠️ 解锁事件**永远不带 expireAt**（工具侧只给 lock_* 传，见 SupervisionAdminTool），
            // 所以「一次解锁 = 永久解锁」那个洞没有被这个改动重新打开：解锁最多管到当天 06:00。
            if (e.kind.isWindowScoped && e.expireAt <= 0L &&
                !SupervisionWindow.matches(e.windowId, currentWindowId)
            ) return@forEach

            // ★ 自带到期时刻的事件：过了点就当它不存在（等价于从没锁过）。
            // 同样**不删事件** —— 删了会被对端同步回来（OR-Set 复活），
            // 过期只是 fold 时的判定，那条历史事实永远留在日志里供审计。
            if (e.expireAt > 0L && nowMs >= e.expireAt) return@forEach

            when (e.kind) {
                SupervisionEvent.Kind.LOCK_CONVERSATION ->
                    runCatching { Uuid.parse(e.target) }.getOrNull()?.let { lockedConversations += it }

                SupervisionEvent.Kind.UNLOCK_CONVERSATION ->
                    runCatching { Uuid.parse(e.target) }.getOrNull()?.let { lockedConversations -= it }

                SupervisionEvent.Kind.LOCK_PATH -> if (e.target.isNotBlank()) lockedPaths += e.target
                SupervisionEvent.Kind.UNLOCK_PATH -> lockedPaths -= e.target

                SupervisionEvent.Kind.ENABLE -> enabledOverride = true
                SupervisionEvent.Kind.DISABLE -> enabledOverride = false
            }
        }

        return FoldResult(
            lockedConversationIds = lockedConversations,
            lockedWorkspacePaths = lockedPaths,
            enabledOverride = enabledOverride,
        )
    }

    /**
     * 压缩（v2 §3.5）。
     *
     * ⚠️ **不能简单「保留最近 N 条」**：A 与 B 各自独立裁剪，裁掉的集合不同，
     * A 裁掉的事件会从 B 那儿同步回来 → **事件复活**，与 2026-08-18 那个
     * pendingUnlock 复活是同一个机制。
     *
     * 正确条件（两条都满足才丢）：
     * 1. 该事件所属窗口**已经结束**（`windowId` 不再是任何活跃窗口）
     * 2. `hlc < stableWatermark` —— 所有已知设备都确认拉过这个位置
     *
     * ⚠️ 例外：**还没到期的跨窗口锁**（`expireAt > nowMs`）必须保留。它的 windowId
     * 早就不是活跃窗口了，但它本人才是当前锁态的来源；按窗口判活会把它当垃圾清掉，
     * 然后被对端同步回来 —— 来回抽搐。到期之后照常参与压缩。
     *
     * @param stableWatermark 所有设备 ack 的 hlc 最小值；拿不到就传 0（= 不压缩）
     * @param activeWindowIds 当前仍可能生效的窗口 id 集合
     * @param nowMs 判定「还没到期」用的当前时刻；测试里可以传假时间
     */
    fun compact(
        stableWatermark: Long,
        activeWindowIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): SupervisionEventLog {
        if (stableWatermark <= 0L) return this // 拿不到全设备 ack 就不压缩，事件很小，不急
        val kept = events.filter { e ->
            when {
                e.hlc >= stableWatermark -> true
                !e.kind.isWindowScoped -> true // 配置级事件是终态来源，永久保留
                e.expireAt > nowMs -> true // 未到期的跨窗口锁，压掉就等于丢锁
                e.windowId in activeWindowIds -> true
                else -> false
            }
        }
        return if (kept.size == events.size) this else SupervisionEventLog(kept)
    }

    data class FoldResult(
        val lockedConversationIds: Set<Uuid>,
        val lockedWorkspacePaths: Set<String>,
        /** null = 事件日志未表态，沿用配置里的 enabled */
        val enabledOverride: Boolean?,
    )
}
