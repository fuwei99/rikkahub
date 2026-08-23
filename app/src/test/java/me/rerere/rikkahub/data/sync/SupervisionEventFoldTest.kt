package me.rerere.rikkahub.data.sync

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.rikkahub.data.model.SupervisionEvent
import me.rerere.rikkahub.data.model.SupervisionEventLog
import me.rerere.rikkahub.data.model.SupervisionSchedule
import me.rerere.rikkahub.data.model.SupervisionSettings
import me.rerere.rikkahub.data.model.SupervisionWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 监督事件日志回归（大统一重构 v2 §3）。
 *
 * 两条核心性质：
 * 1. **解锁必须能跨设备传播**（旧 `strengthenWith` 并集做不到，这是 2026-08-23 的事故）
 * 2. **解锁不得跨时段复活**（否则一次解锁 = 永久解锁，监督系统报废）
 */
class SupervisionEventFoldTest {

    private val convA = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val convB = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private val w1 = "sched-1:1000"
    private val w2 = "sched-1:2000"

    private fun lock(conv: Uuid, hlc: Long, window: String) = SupervisionEvent(
        id = "lock-$conv-$hlc",
        kind = SupervisionEvent.Kind.LOCK_CONVERSATION,
        hlc = hlc,
        actor = SupervisionEvent.Actor.SCHEDULE_AGENT,
        windowId = window,
        target = conv.toString(),
    )

    private fun unlock(conv: Uuid, hlc: Long, window: String) = SupervisionEvent(
        id = "unlock-$conv-$hlc",
        kind = SupervisionEvent.Kind.UNLOCK_CONVERSATION,
        hlc = hlc,
        actor = SupervisionEvent.Actor.GRANTOR,
        windowId = window,
        target = conv.toString(),
    )

    // ---------------- 核心：解锁能跨设备传播 ----------------

    @Test
    fun `unlock with larger hlc wins over lock`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 200, w1)))
        val r = log.fold(currentWindowId = w1)
        assertTrue(
            "解锁事件 hlc 更大就必须生效 —— 旧 strengthenWith 并集永远做不到这件事",
            convA !in r.lockedConversationIds
        )
    }

    @Test
    fun `relock after unlock wins again`() {
        val log = SupervisionEventLog(
            listOf(lock(convA, 100, w1), unlock(convA, 200, w1), lock(convA, 300, w1))
        )
        assertTrue("最后一个事件是加锁 → 锁上", convA in log.fold(w1).lockedConversationIds)
    }

    @Test
    fun `unlock survives merge from the still-locked peer`() {
        // 复现 2026-08-23 事故：平板解锁，手机还持有旧锁态。
        // 旧实现里手机的锁态会把解锁「加强」回去；事件日志下必须解锁胜出。
        val tablet = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 200, w1)))
        val phone = SupervisionEventLog(listOf(lock(convA, 100, w1)))

        val onTablet = tablet.merge(phone).fold(w1)
        val onPhone = phone.merge(tablet).fold(w1)

        assertTrue("平板侧必须保持解锁", convA !in onTablet.lockedConversationIds)
        assertTrue("手机侧同步后也必须变成解锁", convA !in onPhone.lockedConversationIds)
        assertEquals("两端必须收敛到同一状态", onTablet, onPhone)
    }

    // ---------------- 核心：解锁不得跨时段复活（v1 方案漏掉的安全洞） ----------------

    @Test
    fun `unlock does not leak into the next window`() {
        // 周日 21:12 在 w1 解锁；周一 08:30 进入 w2。
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 200, w1)))

        val inNextWindow = log.fold(currentWindowId = w2)
        assertTrue(
            "上个时段的解锁事件不得在新时段生效，否则一次解锁 = 永久解锁",
            inNextWindow.lockedConversationIds.isEmpty()
        )
        // 注意：新窗口里该会话既没有 lock 也没有 unlock 生效 → 干净状态，
        // 由新窗口内的查岗任务重新决定要不要锁。
    }

    @Test
    fun `lock from previous window also does not leak`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1)))
        assertTrue(
            "窗口级锁同样只在本窗口生效（与 isActiveAt 的既有语义一致）",
            log.fold(w2).lockedConversationIds.isEmpty()
        )
    }

    @Test
    fun `stale events are retained in the log for audit and convergence`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 200, w1)))
        assertEquals(
            "过期事件必须留在日志里：删掉会让两端裁剪结果不同，事件从对端复活",
            2,
            log.events.size
        )
    }

    @Test
    fun `no window means window scoped locks are inactive`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1)))
        assertTrue(
            "不在任何监督时段时窗口级锁不生效",
            log.fold(currentWindowId = null).lockedConversationIds.isEmpty()
        )
    }

    // ---------------- 配置级事件 ----------------

    @Test
    fun `enable disable are global and survive window changes`() {
        val log = SupervisionEventLog(
            listOf(
                SupervisionEvent(
                    id = "e1",
                    kind = SupervisionEvent.Kind.ENABLE,
                    hlc = 100,
                    actor = SupervisionEvent.Actor.USER,
                    windowId = SupervisionEvent.WINDOW_GLOBAL,
                )
            )
        )
        assertEquals(true, log.fold(w1).enabledOverride)
        assertEquals("配置级事件不受窗口约束", true, log.fold(w2).enabledOverride)
        assertEquals(true, log.fold(null).enabledOverride)
    }

    @Test
    fun `later disable beats earlier enable`() {
        fun ev(kind: SupervisionEvent.Kind, hlc: Long) = SupervisionEvent(
            id = "e$hlc", kind = kind, hlc = hlc,
            actor = SupervisionEvent.Actor.USER,
            windowId = SupervisionEvent.WINDOW_GLOBAL,
        )
        val log = SupervisionEventLog(
            listOf(ev(SupervisionEvent.Kind.ENABLE, 100), ev(SupervisionEvent.Kind.DISABLE, 200))
        )
        assertEquals(false, log.fold(w1).enabledOverride)
    }

    @Test
    fun `empty log makes no assertion about enabled`() {
        assertNull(
            "空日志不得表态，否则会把 enabled 强行改成默认值",
            SupervisionEventLog().fold(w1).enabledOverride
        )
    }

    // ---------------- 合并的 CRDT 性质 ----------------

    @Test
    fun `merge is idempotent`() {
        val a = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 200, w1)))
        assertEquals(a.merge(a).events.size, a.events.size)
    }

    @Test
    fun `merge is commutative in resulting state`() {
        val a = SupervisionEventLog(listOf(lock(convA, 100, w1)))
        val b = SupervisionEventLog(listOf(unlock(convA, 200, w1), lock(convB, 150, w1)))
        assertEquals(a.merge(b).fold(w1), b.merge(a).fold(w1))
    }

    @Test
    fun `merge dedupes by event id`() {
        val e = lock(convA, 100, w1)
        val merged = SupervisionEventLog(listOf(e)).merge(SupervisionEventLog(listOf(e)))
        assertEquals(1, merged.events.size)
    }

    @Test
    fun `same hlc different id yields deterministic order on both sides`() {
        val e1 = lock(convA, 100, w1).copy(id = "aaa")
        val e2 = unlock(convA, 100, w1).copy(id = "bbb")
        val onA = SupervisionEventLog(listOf(e1, e2)).fold(w1)
        val onB = SupervisionEventLog(listOf(e2, e1)).fold(w1)
        assertEquals("同 hlc 必须按 id 定序，否则两端 fold 出不同状态", onA, onB)
    }

    // ---------------- 压缩安全性 ----------------

    @Test
    fun `compact without watermark keeps everything`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 200, w1)))
        assertEquals(
            "拿不到全设备 ack 时绝不压缩，否则被裁的事件会从对端复活",
            log.events.size,
            log.compact(stableWatermark = 0L, activeWindowIds = emptySet()).events.size
        )
    }

    @Test
    fun `compact keeps events above watermark`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1), unlock(convA, 500, w1)))
        val out = log.compact(stableWatermark = 300L, activeWindowIds = emptySet())
        assertEquals(listOf(500L), out.events.map { it.hlc })
    }

    @Test
    fun `compact keeps events of active windows`() {
        val log = SupervisionEventLog(listOf(lock(convA, 100, w1)))
        val out = log.compact(stableWatermark = 1000L, activeWindowIds = setOf(w1))
        assertEquals("活跃窗口的事件不得压掉", 1, out.events.size)
    }

    @Test
    fun `compact never drops config level events`() {
        val log = SupervisionEventLog(
            listOf(
                SupervisionEvent(
                    id = "cfg", kind = SupervisionEvent.Kind.DISABLE, hlc = 1,
                    actor = SupervisionEvent.Actor.USER,
                    windowId = SupervisionEvent.WINDOW_GLOBAL,
                )
            )
        )
        val out = log.compact(stableWatermark = Long.MAX_VALUE, activeWindowIds = emptySet())
        assertEquals("配置级事件是终态来源，压掉会让 enabled 回退", 1, out.events.size)
    }

    // ---------------- windowId 生成 ----------------

    @Test
    fun `window id is stable within one session and changes across sessions`() {
        // 周一 (dow=1) 08:00-12:00
        val schedule = SupervisionSchedule(
            id = Uuid.parse("33333333-3333-3333-3333-333333333333"),
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            startMinute = 8 * 60,
            endMinute = 12 * 60,
        )
        val settings = SupervisionSettings(enabled = true, schedules = listOf(schedule))

        // 2026-08-24 是周一。09:00 与 11:00 应属同一窗口
        val nineAm = isoMs("2026-08-24T09:00:00")
        val elevenAm = isoMs("2026-08-24T11:00:00")
        val nextDayNine = isoMs("2026-08-25T09:00:00")

        val idA = SupervisionWindow.idAt(settings, nineAm)
        val idB = SupervisionWindow.idAt(settings, elevenAm)
        val idC = SupervisionWindow.idAt(settings, nextDayNine)

        assertTrue("时段内必须能算出窗口 id", idA != null)
        assertEquals("同一次时段内任意时刻算出的 id 必须相同", idA, idB)
        assertTrue("不同日期的时段必须是不同窗口", idA != idC)
    }

    @Test
    fun `window id is null outside schedule`() {
        val schedule = SupervisionSchedule(
            daysOfWeek = setOf(1),
            startMinute = 8 * 60,
            endMinute = 12 * 60,
        )
        val settings = SupervisionSettings(enabled = true, schedules = listOf(schedule))
        // 周一 20:00，不在 08:00-12:00 内
        assertNull(SupervisionWindow.idAt(settings, isoMs("2026-08-24T20:00:00")))
    }

    @Test
    fun `no schedules means no window`() {
        assertNull(SupervisionWindow.idAt(SupervisionSettings(enabled = true), 0L))
    }

    /** 用本地时区解析，与 activationSessionEndAt 的 TimeZone.currentSystemDefault 对齐 */
    private fun isoMs(local: String): Long =
        LocalDateTime.parse(local)
            .toInstant(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
}
