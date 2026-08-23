package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.core.SyncClock
import me.rerere.rikkahub.data.sync.core.SyncCrdt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 同步模拟器（大统一重构 v2 §5.4）。
 *
 * ## 为什么必须有这个
 *
 * CRDT 的性质（收敛、单调、不丢数据）无法靠「看代码觉得对」保证。手工用例只能覆盖
 * 想到的场景，而同步事故恰恰都发生在想不到的时序上 —— 2026-08-23 的两个 bug
 * 都是「单看每段代码都合理，组合起来吃数据」。
 *
 * 本模拟器用**双虚拟设备 + 偏斜时钟 + 随机算子序列**跑上千轮，断言三条铁律：
 *
 * 1. **收敛**：全设备跑到静默后状态完全相等
 * 2. **单调**：字段不会回退到更早的值
 * 3. **不丢数据**：集合类只在显式删除时缩减
 *
 * 纯 JVM、无 Android 依赖、无网络，秒级跑完。
 *
 * ## 模型简化说明
 *
 * 这里模拟的是「带 hlc 的字段级 LWW」这一核心机制，用 `Map<String, Cell>` 代表
 * 一台设备的设置。不引入真实 `Settings`（84 字段 + 嵌套序列化）是有意为之：
 * 要验证的是**合并规则**，不是序列化。规则错了在这里必然暴露。
 */
class SyncSimulatorTest {

    /** 一个字段的值 + 它的因果时间戳 */
    private data class Cell(val value: String, val hlc: Long)

    /** 云端：字段 -> Cell（对应 bundles 的一个 shard envelope） */
    private class FakeCloud {
        val fields = mutableMapOf<String, Cell>()
        fun snapshot(): Map<String, Cell> = fields.toMap()
    }

    private class VirtualDevice(
        val name: String,
        /** 时钟偏斜（毫秒），模拟两台设备系统时间不一致 */
        val skewMs: Long,
        var wall: Long = 1_000_000L,
    ) {
        private var lastPacked = 0L
        val fields = mutableMapOf<String, Cell>()

        /** 本机 HLC：wall 取 max，同 wall 用 counter 递增（与 SyncClock 一致） */
        fun tick(): Long {
            val sys = wall + skewMs
            val lastWall = SyncClock.wallOf(lastPacked)
            lastPacked = if (sys > lastWall) {
                SyncClock.pack(sys, 0)
            } else {
                SyncClock.pack(lastWall, SyncClock.counterOf(lastPacked) + 1)
            }
            return lastPacked
        }

        fun observe(remote: Long) {
            if (remote <= lastPacked) return
            lastPacked = remote
        }

        fun write(field: String, value: String) {
            fields[field] = Cell(value, tick())
        }

        /** pull：逐字段按全序裁决，取代「整包 remote.copy」 */
        fun pull(cloud: FakeCloud) {
            cloud.snapshot().forEach { (field, remote) ->
                observe(remote.hlc)
                val local = fields[field]
                if (local == null) {
                    fields[field] = remote
                    return@forEach
                }
                val d = SyncCrdt.decideScalar(
                    field = field,
                    localHlc = local.hlc,
                    remoteHlc = remote.hlc,
                    localSha = local.value,   // 用值本身充当 sha（单射即可）
                    remoteSha = remote.value,
                )
                if (d.takeRemote) fields[field] = remote
            }
        }

        /** push：同样逐字段裁决，胜者写云端（模拟乐观锁 CAS 后的合并回写） */
        fun push(cloud: FakeCloud) {
            fields.forEach { (field, local) ->
                // ★ §2.5：未打戳的值不得作为「新事实」上推。
                // 否则新设备/刚升级设备的默认值会污染云端真实配置。
                if (local.hlc == SyncClock.UNKNOWN) return@forEach

                val remote = cloud.fields[field]
                if (remote == null) {
                    cloud.fields[field] = local
                    return@forEach
                }
                val d = SyncCrdt.decideScalar(
                    field = field,
                    localHlc = local.hlc,
                    remoteHlc = remote.hlc,
                    localSha = local.value,
                    remoteSha = remote.value,
                )
                if (!d.takeRemote && local != remote) cloud.fields[field] = local
            }
        }

        fun sync(cloud: FakeCloud) {
            pull(cloud)
            push(cloud)
        }
    }

    /** 跑到静默：反复同步直到无人再变化 */
    private fun quiesce(devices: List<VirtualDevice>, cloud: FakeCloud, maxRounds: Int = 50) {
        repeat(maxRounds) {
            val before = devices.map { it.fields.toMap() } to cloud.snapshot()
            devices.forEach { it.sync(cloud) }
            val after = devices.map { it.fields.toMap() } to cloud.snapshot()
            if (before == after) return
        }
    }

    // ---------------- 铁律一：收敛 ----------------

    @Test
    fun `two devices with skewed clocks converge over random operations`() {
        repeat(200) { seed ->
            val rnd = Random(seed)
            val cloud = FakeCloud()
            val a = VirtualDevice("A", skewMs = 300_000L)   // 快 5 分钟
            val b = VirtualDevice("B", skewMs = -300_000L)  // 慢 5 分钟
            val devices = listOf(a, b)
            val fieldNames = listOf("chatModelId", "titlePrompt", "enableSuggestion")

            repeat(40) {
                val dev = devices[rnd.nextInt(devices.size)]
                when (rnd.nextInt(4)) {
                    0, 1 -> {
                        dev.wall += rnd.nextLong(1, 5000)
                        dev.write(fieldNames.random(rnd), "v${rnd.nextInt(100)}")
                    }
                    2 -> dev.sync(cloud)
                    3 -> { // 离线一阵再回来
                        dev.wall += rnd.nextLong(10_000, 120_000)
                    }
                }
            }

            quiesce(devices, cloud)

            assertEquals(
                "seed=$seed 两台设备未收敛：\nA=${a.fields}\nB=${b.fields}",
                a.fields,
                b.fields,
            )
        }
    }

    @Test
    fun `three devices converge`() {
        repeat(100) { seed ->
            val rnd = Random(seed + 10_000)
            val cloud = FakeCloud()
            val devices = listOf(
                VirtualDevice("k70", 0L),
                VirtualDevice("matepad", 120_000L),
                VirtualDevice("k60s", -45_000L),
            )
            repeat(60) {
                val dev = devices[rnd.nextInt(devices.size)]
                if (rnd.nextBoolean()) {
                    dev.wall += rnd.nextLong(1, 3000)
                    dev.write(listOf("f1", "f2", "f3", "f4").random(rnd), "x${rnd.nextInt(50)}")
                } else {
                    dev.sync(cloud)
                }
            }
            quiesce(devices, cloud)
            assertEquals("seed=$seed 设备 0/1 未收敛", devices[0].fields, devices[1].fields)
            assertEquals("seed=$seed 设备 1/2 未收敛", devices[1].fields, devices[2].fields)
        }
    }

    // ---------------- 铁律二：单调（不回退） ----------------

    @Test
    fun `field hlc never regresses on any device`() {
        repeat(100) { seed ->
            val rnd = Random(seed + 20_000)
            val cloud = FakeCloud()
            val a = VirtualDevice("A", 200_000L)
            val b = VirtualDevice("B", -200_000L)
            val seen = mutableMapOf<Pair<String, String>, Long>() // (device, field) -> 最大 hlc

            repeat(80) {
                val dev = if (rnd.nextBoolean()) a else b
                if (rnd.nextBoolean()) {
                    dev.wall += rnd.nextLong(1, 2000)
                    dev.write("f${rnd.nextInt(3)}", "v${rnd.nextInt(20)}")
                } else {
                    dev.sync(cloud)
                }
                listOf(a, b).forEach { d ->
                    d.fields.forEach { (f, cell) ->
                        val key = d.name to f
                        val prev = seen[key]
                        if (prev != null) {
                            assertTrue(
                                "seed=$seed ${d.name}.$f 的 hlc 回退了：$prev -> ${cell.hlc}",
                                cell.hlc >= prev
                            )
                        }
                        seen[key] = cell.hlc
                    }
                }
            }
        }
    }

    // ---------------- 铁律三：不丢数据（字段不会消失） ----------------

    @Test
    fun `no field ever disappears once written`() {
        repeat(100) { seed ->
            val rnd = Random(seed + 30_000)
            val cloud = FakeCloud()
            val a = VirtualDevice("A", 60_000L)
            val b = VirtualDevice("B", -60_000L)
            val everWritten = mutableSetOf<String>()

            repeat(60) {
                val dev = if (rnd.nextBoolean()) a else b
                if (rnd.nextBoolean()) {
                    dev.wall += rnd.nextLong(1, 3000)
                    val f = "f${rnd.nextInt(5)}"
                    everWritten += f
                    dev.write(f, "v${rnd.nextInt(30)}")
                } else {
                    dev.sync(cloud)
                }
            }
            quiesce(listOf(a, b), cloud)

            assertTrue(
                "seed=$seed 有字段在同步后消失了：期望 $everWritten，实得 ${a.fields.keys}",
                a.fields.keys.containsAll(everWritten)
            )
        }
    }

    // ---------------- 回归：旧「平票保本地」会发散 ----------------

    @Test
    fun `tie break by sha prevents the ping-pong divergence of keep-local`() {
        // 复现旧规则的病：两端 hlc 相等时各自「保本地」，于是永远不一致，
        // 且每轮都互相回推。新规则用内容 sha 定序，两端必然选中同一个值。
        val cloud = FakeCloud()
        val a = VirtualDevice("A", 0L)
        val b = VirtualDevice("B", 0L)

        // 人为制造完全相同的 hlc（同一毫秒、同一 counter）
        val sameHlc = SyncClock.pack(1_000_000L, 0)
        a.fields["conflict"] = Cell("apple", sameHlc)
        b.fields["conflict"] = Cell("banana", sameHlc)

        quiesce(listOf(a, b), cloud)

        assertEquals("平票必须收敛到同一值", a.fields["conflict"], b.fields["conflict"])
        assertEquals(
            "两端都应选中 sha 字典序更大的 banana",
            "banana",
            a.fields["conflict"]!!.value,
        )
    }

    // ---------------- 回归：unknown 不得被当成「最古老」 ----------------

    @Test
    fun `device with unstamped fields adopts cloud instead of overwriting it`() {
        // 场景：新设备/刚升级，本地字段全无戳（hlc=0）；云端有真实配置。
        // 必须采纳云端，而不是把本地默认值推上去（§2.5 的最高危场景）。
        val cloud = FakeCloud()
        cloud.fields["chatModelId"] = Cell("real-model", SyncClock.pack(2_000_000L, 0))

        val fresh = VirtualDevice("fresh", 0L)
        fresh.fields["chatModelId"] = Cell("default-random-uuid", SyncClock.UNKNOWN)

        fresh.sync(cloud)

        assertEquals(
            "未打戳的本地默认值必须让位给云端真实配置",
            "real-model",
            fresh.fields["chatModelId"]!!.value,
        )
        assertEquals(
            "云端不得被默认值污染",
            "real-model",
            cloud.fields["chatModelId"]!!.value,
        )
    }

    @Test
    fun `stamped local beats unstamped cloud from legacy client`() {
        // 老客户端写的行 hlc=0；本机有真实改动。必须保住本机，且不被判输。
        val cloud = FakeCloud()
        cloud.fields["titlePrompt"] = Cell("legacy-value", SyncClock.UNKNOWN)

        val dev = VirtualDevice("new", 0L)
        dev.write("titlePrompt", "my-edit")

        dev.sync(cloud)

        assertEquals("my-edit", dev.fields["titlePrompt"]!!.value)
        assertEquals("本机有戳的改动应推上云", "my-edit", cloud.fields["titlePrompt"]!!.value)
    }
}
