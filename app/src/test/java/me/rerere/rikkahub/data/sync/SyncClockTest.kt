package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.core.SyncClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {

    private class MemStore(var value: Long = 0L) : SyncClock.Store {
        var saves = 0
        override fun load(): Long = value
        override fun save(packed: Long) {
            value = packed
            saves++
        }
    }

    private fun clockAt(vararg walls: Long): Pair<SyncClock, MemStore> {
        val store = MemStore()
        var i = 0
        val clock = SyncClock(store) { walls[minOf(i++, walls.size - 1)] }
        return clock to store
    }

    // ---------------- 打包 ----------------

    @Test
    fun `pack and unpack roundtrip`() {
        val packed = SyncClock.pack(1_787_000_000_000L, 42)
        assertEquals(1_787_000_000_000L, SyncClock.wallOf(packed))
        assertEquals(42, SyncClock.counterOf(packed))
    }

    @Test
    fun `packed order matches wall order`() {
        val a = SyncClock.pack(1000L, 65535)
        val b = SyncClock.pack(1001L, 0)
        assertTrue("wall 优先于 counter，否则同毫秒高 counter 会压过下一毫秒", b > a)
    }

    // ---------------- 单调性 ----------------

    @Test
    fun `now is strictly increasing within same millisecond`() {
        val (clock, _) = clockAt(1000L)
        val a = clock.now()
        val b = clock.now()
        val c = clock.now()
        assertTrue(b > a)
        assertTrue(c > b)
        assertEquals(1000L, SyncClock.wallOf(c))
        assertEquals(2, SyncClock.counterOf(c))
    }

    @Test
    fun `now never goes backwards when system clock jumps back`() {
        // 墙钟从 5000 倒退到 3000（用户改时间 / NTP 校正）
        val store = MemStore()
        val walls = longArrayOf(5000L, 3000L, 3000L)
        var i = 0
        val clock = SyncClock(store) { walls[minOf(i++, walls.size - 1)] }

        val a = clock.now()
        val b = clock.now()
        val c = clock.now()
        assertTrue("时钟倒退时 HLC 必须只增不减", b > a)
        assertTrue(c > b)
        assertEquals("倒退期间沿用旧 wall，靠 counter 维持单调", 5000L, SyncClock.wallOf(c))
    }

    @Test
    fun `counter overflow rolls into next millisecond`() {
        val store = MemStore(SyncClock.pack(1000L, 65535))
        val clock = SyncClock(store) { 1000L }
        val next = clock.now()
        assertEquals(1001L, SyncClock.wallOf(next))
        assertEquals(0, SyncClock.counterOf(next))
    }

    @Test
    fun `persists every tick so restart does not regress`() {
        val store = MemStore()
        val clock = SyncClock(store) { 1000L }
        val last = clock.now().let { clock.now() }
        assertEquals("每次 now 都必须落盘，否则崩溃重启会倒退", last, store.value)

        // 模拟进程重启：新实例从 store 恢复
        val revived = SyncClock(store) { 1000L }
        assertTrue("重启后必须继续大于重启前", revived.now() > last)
    }

    // ---------------- 因果 observe ----------------

    @Test
    fun `observe advances local clock past remote`() {
        val store = MemStore()
        val clock = SyncClock(store) { 1000L }
        val remote = SyncClock.pack(9000L, 3)
        clock.observe(remote)
        val next = clock.now()
        assertTrue("observe 后本机产生的戳必须大于远端戳", next > remote)
    }

    @Test
    fun `observe ignores unknown zero`() {
        val store = MemStore()
        val clock = SyncClock(store) { 1000L }
        val before = clock.now()
        clock.observe(0L)
        assertEquals("hlc=0 是 unknown，不得推进时钟", before, clock.peek())
    }

    @Test
    fun `observe of older remote is a no-op`() {
        val store = MemStore()
        val clock = SyncClock(store) { 9000L }
        val mine = clock.now()
        clock.observe(SyncClock.pack(1000L, 0))
        assertEquals(mine, clock.peek())
    }

    @Test
    fun `two devices converge after exchanging timestamps`() {
        // 设备 A 时钟快 5 分钟，B 慢；互相 observe 后必须能分出因果先后
        val fast = SyncClock(MemStore()) { 1_000_000L + 300_000L }
        val slow = SyncClock(MemStore()) { 1_000_000L }

        val fromFast = fast.now()
        slow.observe(fromFast)
        val fromSlowAfter = slow.now()

        assertTrue(
            "慢钟设备 observe 之后写入的事件必须被判定为更晚，哪怕它物理时钟落后",
            fromSlowAfter > fromFast
        )
    }

    // ---------------- 裁决 ----------------

    @Test
    fun `decide prefers larger hlc`() {
        assertEquals(
            SyncClock.Winner.REMOTE,
            SyncClock.decide(localHlc = 10, remoteHlc = 20, localSha = "a", remoteSha = "b")
        )
        assertEquals(
            SyncClock.Winner.LOCAL,
            SyncClock.decide(localHlc = 30, remoteHlc = 20, localSha = "a", remoteSha = "b")
        )
    }

    @Test
    fun `decide breaks tie by sha deterministically`() {
        // 关键性质：两端各自计算必须得出**同一个赢家**，否则会互相回推形成永动机
        val onDeviceA = SyncClock.decide(10, 10, localSha = "aaa", remoteSha = "bbb")
        val onDeviceB = SyncClock.decide(10, 10, localSha = "bbb", remoteSha = "aaa")
        assertEquals(SyncClock.Winner.REMOTE, onDeviceA) // A 看：远端 bbb 更大 → 取远端
        assertEquals(SyncClock.Winner.LOCAL, onDeviceB)  // B 看：本地 bbb 更大 → 保本地
        // 双方都选中了 "bbb"，收敛
    }

    @Test
    fun `decide with identical content keeps local to avoid ui flicker`() {
        assertEquals(
            SyncClock.Winner.LOCAL,
            SyncClock.decide(10, 10, localSha = "same", remoteSha = "same")
        )
    }

    // ---------------- unknown 语义（方案 §2.5，最高危区） ----------------

    @Test
    fun `unknown local yields to known remote`() {
        assertEquals(
            SyncClock.Winner.REMOTE,
            SyncClock.decide(SyncClock.UNKNOWN, 20, "a", "b")
        )
    }

    @Test
    fun `known local beats unknown remote`() {
        assertEquals(
            "hlc=0 必须是 unknown 而不是「最古老」，否则老客户端一上线其配置会被全量抹掉",
            SyncClock.Winner.LOCAL,
            SyncClock.decide(20, SyncClock.UNKNOWN, "a", "b")
        )
    }

    @Test
    fun `both unknown keeps local without asserting a fact`() {
        val w = SyncClock.decide(SyncClock.UNKNOWN, SyncClock.UNKNOWN, "a", "b")
        assertEquals(SyncClock.Winner.LOCAL_UNKNOWN, w)
        assertTrue("LOCAL_UNKNOWN 不得被当成远端胜出", !w.takesRemote)
    }
}
