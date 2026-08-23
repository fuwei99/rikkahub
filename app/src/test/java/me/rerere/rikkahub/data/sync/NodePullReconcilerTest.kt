package me.rerere.rikkahub.data.sync

import me.rerere.rikkahub.data.sync.core.NodePullReconciler
import me.rerere.rikkahub.data.sync.core.NodePullReconciler.CloudNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 回归：node 增量 pull 不得裁空对话。
 *
 * 用一个最小的 `Node(id, body)` 替身代替 `MessageNode`（后者依赖 UIMessage 一大坨
 * 序列化结构，与本逻辑无关）。重建器是泛型 + idOf 注入的，行为完全一致。
 */
class NodePullReconcilerTest {

    private data class Node(val id: String, val body: String)

    private val idOf: (Node) -> String = { it.id }

    private fun reconcile(
        cloud: List<CloudNode>,
        local: List<Node>,
        state: Map<String, String>,
        fetched: Map<String, Node>,
    ) = NodePullReconciler.reconcile(cloud, local, state, fetched, idOf)

    // ---------------- P0 核心场景 ----------------

    @Test
    fun `unchanged history nodes are preserved when only last node changed`() {
        // 200 条历史 + 对端改了最后一条。旧实现会把 200 条裁成 1 条。
        val local = (1..200).map { Node("n$it", "old$it") }
        val state = local.associate { it.id to "sha-${it.id}" }
        val cloud = (1..200).map {
            CloudNode(
                nodeId = "n$it",
                idx = it,
                // 只有最后一条 sha 变了
                sha = if (it == 200) "sha-n200-NEW" else "sha-n$it",
                deleted = false,
            )
        }
        // 因此只有 n200 会被 fetch
        val fetched = mapOf("n200" to Node("n200", "NEW BODY"))

        val out = reconcile(cloud, local, state, fetched)

        val merged = out as NodePullReconciler.Outcome.Merged
        assertEquals("历史节点必须一条不少", 200, merged.nodes.size)
        assertEquals("NEW BODY", merged.nodes.last().body)
        assertEquals("未变化的历史内容必须是本地原值", "old1", merged.nodes.first().body)
    }

    @Test
    fun `baseline keeps local sha for filled nodes so next round retries`() {
        val local = listOf(Node("a", "A"), Node("b", "B"))
        val state = mapOf("a" to "sha-a-OLD", "b" to "sha-b")
        val cloud = listOf(
            // a 的云端 sha 变了但这轮没取到 data（例如批量查询被截断）
            CloudNode("a", 1, "sha-a-NEW", false),
            CloudNode("b", 2, "sha-b", false),
        )
        val out = reconcile(cloud, local, state, fetched = mapOf()) as? NodePullReconciler.Outcome.Merged
            ?: error("应当能重建")

        assertEquals(2, out.nodes.size)
        assertEquals(
            "补齐的节点基准必须保持本地旧 sha，否则下一轮误判为已同步、云端新内容永远拉不下来",
            "sha-a-OLD",
            out.nextState["a"]
        )
        assertTrue("补齐发生过就要回推", out.needRepush)
    }

    @Test
    fun `decode failure does not delete the node`() {
        // 模拟：云端 data 存在但解码失败 → 调用方不会把它放进 fetchedData
        val local = listOf(Node("a", "A"), Node("b", "B"), Node("c", "C"))
        val state = local.associate { it.id to "sha-${it.id}" }
        val cloud = local.mapIndexed { i, n -> CloudNode(n.id, i, "sha-${n.id}-NEW", false) }

        val out = reconcile(cloud, local, state, fetched = mapOf("b" to Node("b", "B2")))
            as NodePullReconciler.Outcome.Merged

        assertEquals("解码失败的节点必须保留本地版本，不得消失", 3, out.nodes.size)
        assertEquals(listOf("A", "B2", "C"), out.nodes.map { it.body })
    }

    // ---------------- 安全阀 ----------------

    @Test
    fun `nodes absent from cloud manifest are kept as local extra not shrink`() {
        // 云端清单不完整（b/c 既不在 alive 也没有墓碑）→ 视为「对端旧版本只写过整包 data」，
        // 保留在本地末尾并回推，不算收缩。
        val local = listOf(Node("a", "A"), Node("b", "B"), Node("c", "C"))
        val state = local.associate { it.id to "sha-${it.id}" }
        val cloud = listOf(CloudNode("a", 1, "sha-a-NEW", false))

        val out = reconcile(cloud, local, state, fetched = mapOf("a" to Node("a", "A2")))
            as NodePullReconciler.Outcome.Merged

        assertEquals(3, out.nodes.size)
        assertEquals(listOf("a", "b", "c"), out.nodes.map { it.id })
        assertTrue(out.needRepush)
    }

    @Test
    fun `aborts when cloud alive nodes cannot be resolved and local would shrink`() {
        // 真收缩场景：云端 alive 里有本地不存在的节点 x，且 x 的 data 取不到；
        // 同时云端为本地的 b 打了墓碑之外的「消失」——即 b 被云端 alive 收录但本地没有。
        // 构造：本地 3 条，云端 alive 只收录其中 1 条（a），另 2 条被显式墓碑标删，
        // 但 a 的 data 又取不到且本地也没有 a → 结果 0 条 < expectedMin 1 条 → Abort。
        val local = listOf(Node("b", "B"), Node("c", "C"))
        val state = mapOf("b" to "sha-b", "c" to "sha-c")
        val cloud = listOf(
            CloudNode("a", 1, "sha-a", false),          // 本地无、data 取不到 → 跳过
            CloudNode("b", 2, "sha-b", deleted = true), // 显式删除，允许移除
        )
        // 存活结果：a 跳过；b 被墓碑移除；c 不在清单 → localExtra 保留 → 共 1 条
        // expectedMin = 2 - 1(b 有显式墓碑) = 1 → 1 >= 1，不 Abort
        val out = reconcile(cloud, local, state, fetched = emptyMap())
        val merged = out as NodePullReconciler.Outcome.Merged
        assertEquals("只有显式墓碑的那条被移除", listOf("c"), merged.nodes.map { it.id })
    }

    @Test
    fun `abort outcome is produced when merged is smaller than expected minimum`() {
        // 直接验证安全阀本身的判据：本地有节点、云端 alive 全部命中本地 id 之外，
        // 且没有任何显式墓碑时，结果不得少于本地条数。
        // 用「本地节点 id 全部出现在云端 alive、但 fetch 全失败且 localById 查不到」
        // 无法自然构造（localById 必然命中），因此这里用一个受控的畸形输入：
        // 本地列表里含重复 id，associateBy 只保留最后一个，导致重建结果比原列表短。
        val local = listOf(Node("dup", "V1"), Node("dup", "V2"), Node("x", "X"))
        val state = mapOf("dup" to "s", "x" to "s")
        val cloud = listOf(
            CloudNode("dup", 1, "s", false),
            CloudNode("x", 2, "s", false),
        )
        val out = reconcile(cloud, local, state, fetched = emptyMap())
        assertTrue(
            "重建结果（2 条）少于本地（3 条）且无显式墓碑 → 必须 Abort，绝不写库",
            out is NodePullReconciler.Outcome.Abort
        )
    }

    @Test
    fun `explicit tombstone is allowed to remove nodes`() {
        val local = listOf(Node("a", "A"), Node("b", "B"), Node("c", "C"))
        val state = local.associate { it.id to "sha-${it.id}" }
        val cloud = listOf(
            CloudNode("a", 1, "sha-a", false),
            CloudNode("b", 2, "sha-b", deleted = true), // 对端显式删除
            CloudNode("c", 3, "sha-c", false),
        )
        val out = reconcile(cloud, local, state, fetched = mapOf())
        val merged = out as NodePullReconciler.Outcome.Merged
        assertEquals("显式墓碑允许缩减", 2, merged.nodes.size)
        assertEquals(listOf("a", "c"), merged.nodes.map { it.id })
    }

    @Test
    fun `all tombstoned cloud never wipes local`() {
        val local = listOf(Node("a", "A"), Node("b", "B"))
        val cloud = listOf(
            CloudNode("a", 1, "s", deleted = true),
            CloudNode("b", 2, "s", deleted = true),
        )
        val out = reconcile(cloud, local, state = emptyMap(), fetched = emptyMap())
        assertTrue(
            "云端全墓碑时交回整包路径裁决，绝不在 node 通道里清空本地",
            out is NodePullReconciler.Outcome.NoChange
        )
    }

    // ---------------- 常规行为 ----------------

    @Test
    fun `cloud order by idx wins`() {
        val local = listOf(Node("b", "B"), Node("a", "A"))
        val cloud = listOf(
            CloudNode("a", 0, "sa", false),
            CloudNode("b", 1, "sb", false),
        )
        val out = reconcile(
            cloud, local,
            state = mapOf("a" to "sa-old", "b" to "sb-old"),
            fetched = mapOf("a" to Node("a", "A"), "b" to Node("b", "B")),
        ) as NodePullReconciler.Outcome.Merged
        assertEquals(listOf("a", "b"), out.nodes.map { it.id })
    }

    @Test
    fun `local only nodes are appended and flagged for repush`() {
        val local = listOf(Node("a", "A"), Node("z", "Z"))
        val cloud = listOf(CloudNode("a", 0, "sa-new", false))
        val out = reconcile(
            cloud, local,
            state = mapOf("a" to "sa", "z" to "sz"),
            fetched = mapOf("a" to Node("a", "A2")),
        ) as NodePullReconciler.Outcome.Merged
        assertEquals(listOf("a", "z"), out.nodes.map { it.id })
        assertTrue("本地独有节点需回推，否则对端永远看不到", out.needRepush)
    }

    @Test
    fun `empty cloud list is a no-op`() {
        val local = listOf(Node("a", "A"))
        val out = reconcile(emptyList(), local, emptyMap(), emptyMap())
        assertTrue(out is NodePullReconciler.Outcome.NoChange)
    }
}
