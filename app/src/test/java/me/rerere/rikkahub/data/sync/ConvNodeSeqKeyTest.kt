package me.rerere.rikkahub.data.sync

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.core.ConversationNodeDiff
import me.rerere.rikkahub.data.sync.core.NodePullReconciler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 方案 B 回归锁：node 通道的排序基准必须**跨端恒等**。
 *
 * 原本用 `idx`（推送方本地下标）排序，两端各自在第 N 条后追加都会写 idx=N，
 * `sortedBy` 对相等 key 不保证顺序 → 两台设备重建出不同的消息序列 → 幽灵分支。
 *
 * seq_key 只依赖节点自身（UTC 时间戳 + nodeId），因此：
 * - 同一节点在任何设备上算出的 key 恒等
 * - 云端行序、写入先后、同步轮数都不影响最终重建结果
 *
 * 这两条一旦破了，多端就会重新开始产生 `(云端分支)`，所以锁在这里。
 */
class ConvNodeSeqKeyTest {

    private fun node(
        text: String,
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int,
        id: Uuid = Uuid.random(),
    ): MessageNode = MessageNode(
        id = id,
        messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(text)),
                createdAt = LocalDateTime(year, month, day, hour, minute, second),
            )
        ),
    )

    @Test
    fun `seqKey 定长且字典序等于时间序`() {
        val early = node("early", 2026, 9, 11, 10, 0, 0)
        val late = node("late", 2026, 9, 11, 10, 0, 1)

        val kEarly = ConversationNodeDiff.seqKeyOf(early)
        val kLate = ConversationNodeDiff.seqKeyOf(late)

        // 时间戳段定长 16 位，保证字符串比较 == 数值比较
        assertEquals(16, kEarly.substringBefore(':').length)
        assertTrue("字典序必须与时间序一致", kEarly < kLate)
    }

    @Test
    fun `同一节点在任何设备上算出的 seqKey 恒等`() {
        val id = Uuid.random()
        val onDeviceA = node("hello", 2026, 9, 11, 10, 0, 0, id = id)
        val onDeviceB = node("hello", 2026, 9, 11, 10, 0, 0, id = id)

        assertEquals(
            ConversationNodeDiff.seqKeyOf(onDeviceA),
            ConversationNodeDiff.seqKeyOf(onDeviceB),
        )
    }

    @Test
    fun `同毫秒不同节点靠 nodeId 兜底定序且稳定`() {
        val a = node("a", 2026, 9, 11, 10, 0, 0)
        val b = node("b", 2026, 9, 11, 10, 0, 0)

        val ka = ConversationNodeDiff.seqKeyOf(a)
        val kb = ConversationNodeDiff.seqKeyOf(b)

        assertTrue("同毫秒必须仍能定出唯一顺序", ka != kb)
        // 顺序由 nodeId 决定，且多次计算恒定
        assertEquals(ka < kb, ConversationNodeDiff.seqKeyOf(a) < ConversationNodeDiff.seqKeyOf(b))
    }

    // ---- Reconciler 侧：云端行序不影响重建结果 ----

    private data class N(val id: String)

    private fun cloud(id: String, idx: Int, seqKey: String, sha: String = "sha-$id") =
        NodePullReconciler.CloudNode(nodeId = id, idx = idx, sha = sha, deleted = false, seqKey = seqKey)

    @Test
    fun `重复 idx 时按 seqKey 定序而非行序`() {
        // 两端各自在第 1 条后追加 → 双方都写 idx=1，这正是幽灵分支的原始现场
        val base = cloud("n0", 0, "0000000000000001:n0")
        val fromA = cloud("na", 1, "0000000000000010:na")
        val fromB = cloud("nb", 1, "0000000000000020:nb")

        val fetched = mapOf("n0" to N("n0"), "na" to N("na"), "nb" to N("nb"))

        // 云端返回行序完全颠倒，也必须重建出同一序列
        val forward = NodePullReconciler.reconcile(
            cloud = listOf(base, fromA, fromB),
            localNodes = listOf(N("n0")),
            localState = mapOf("n0" to "sha-n0"),
            fetchedData = fetched,
        ) { it.id }

        val reversed = NodePullReconciler.reconcile(
            cloud = listOf(fromB, fromA, base),
            localNodes = listOf(N("n0")),
            localState = mapOf("n0" to "sha-n0"),
            fetchedData = fetched,
        ) { it.id }

        val a = forward as NodePullReconciler.Outcome.Merged
        val b = reversed as NodePullReconciler.Outcome.Merged

        assertEquals(listOf("n0", "na", "nb"), a.nodes.map { it.id })
        assertEquals("云端行序不得影响重建结果", a.nodes.map { it.id }, b.nodes.map { it.id })
    }

    @Test
    fun `旧行 seqKey 为空时回退 idx 排序且不与新行错位`() {
        // 混合场景：老客户端写的行没有 seq_key，新客户端写的有
        val legacy0 = cloud("l0", 0, "")
        val legacy1 = cloud("l1", 1, "")
        val fresh = cloud("f2", 2, "0000000000009999:f2")

        val outcome = NodePullReconciler.reconcile(
            cloud = listOf(fresh, legacy1, legacy0),
            localNodes = listOf(N("l0")),
            localState = mapOf("l0" to "sha-l0"),
            fetchedData = mapOf("l0" to N("l0"), "l1" to N("l1"), "f2" to N("f2")),
        ) { it.id }

        val merged = outcome as NodePullReconciler.Outcome.Merged
        assertEquals(listOf("l0", "l1", "f2"), merged.nodes.map { it.id })
    }
}
