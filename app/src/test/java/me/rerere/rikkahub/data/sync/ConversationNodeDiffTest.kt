package me.rerere.rikkahub.data.sync

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.core.ConversationNodeDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P3 node 级增量（S2）的回归锁。
 *
 * 核心承诺：长会话追加一条消息只产生 1 条 UPSERT 语句（≤ 几 KB），
 * 不再整包重传；本地删除节点必须 tombstone；无变化必须零语句。
 */
class ConversationNodeDiffTest {

    private val convId = Uuid.random().toString()

    private fun node(text: String, id: Uuid = Uuid.random()): MessageNode =
        MessageNode(
            id = id,
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(text)),
                )
            ),
        )

    private fun nodeWithSelect(text: String, selectIndex: Int, id: Uuid = Uuid.random()): MessageNode =
        node(text, id).copy(selectIndex = selectIndex)

    @Test
    fun `首次推送时全部节点上推`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val r = ConversationNodeDiff.compute(convId, nodes, emptyMap(), "k70#1", 1000L)
        assertEquals(3, r.statements.size)
        assertEquals(nodes.size, r.newState.size)
        assertTrue(r.statements.all { it.sql.contains("INSERT INTO conv_nodes") })
    }

    @Test
    fun `追加一条消息只推新节点`() {
        val a = node("a")
        val b = node("b")
        val first = ConversationNodeDiff.compute(convId, listOf(a, b), emptyMap(), "k70#1", 1000L)

        val c = node("c")
        val second = ConversationNodeDiff.compute(convId, listOf(a, b, c), first.newState, "k70#1", 2000L)
        assertEquals(1, second.statements.size)
        assertTrue(second.statements[0].sql.contains("INSERT INTO conv_nodes"))
        assertEquals(c.id.toString(), second.statements[0].params[1])
        assertEquals(3, second.newState.size)
    }

    @Test
    fun `无变化时零语句`() {
        val nodes = listOf(node("a"), node("b"))
        val first = ConversationNodeDiff.compute(convId, nodes, emptyMap(), "k70#1", 1000L)
        val second = ConversationNodeDiff.compute(convId, nodes, first.newState, "k70#1", 2000L)
        assertTrue(second.isEmpty)
        assertEquals(first.newState, second.newState)
    }

    @Test
    fun `删除节点产生 tombstone 且不进新状态`() {
        val a = node("a")
        val b = node("b")
        val first = ConversationNodeDiff.compute(convId, listOf(a, b), emptyMap(), "k70#1", 1000L)

        val second = ConversationNodeDiff.compute(convId, listOf(a), first.newState, "k70#1", 2000L)
        assertEquals(1, second.statements.size)
        assertTrue(second.statements[0].sql.contains("deleted = 1"))
        assertEquals(listOf(a.id.toString()), second.newState.keys.toList())
    }

    @Test
    fun `同节点内容变化触发重推`() {
        val id = Uuid.random()
        val original = node("hello", id)
        val first = ConversationNodeDiff.compute(convId, listOf(original), emptyMap(), "k70#1", 1000L)

        val edited = node("hello edited", id)
        val second = ConversationNodeDiff.compute(convId, listOf(edited), first.newState, "k70#1", 2000L)
        assertEquals(1, second.statements.size)
        assertEquals(id.toString(), second.statements[0].params[1])
    }

    @Test
    fun `selectIndex 变化视为节点变化重推`() {
        val id = Uuid.random()
        val v1 = nodeWithSelect("x", 0, id)
        val first = ConversationNodeDiff.compute(convId, listOf(v1), emptyMap(), "k70#1", 1000L)

        val v2 = nodeWithSelect("x", 2, id)
        val second = ConversationNodeDiff.compute(convId, listOf(v2), first.newState, "k70#1", 2000L)
        assertEquals(1, second.statements.size)
    }

    @Test
    fun `长会话追加一条只产生一条语句`() {
        val base = (1..100).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "k70#1", 1000L)
        assertEquals(100, first.statements.size)

        val extra = node("msg-101")
        val second = ConversationNodeDiff.compute(convId, base + extra, first.newState, "k70#1", 2000L)
        assertEquals(1, second.statements.size)
    }

    // ────────────────────────────────────────────────────────────────
    // 批量删除安全阀（2026-09-11 数据丢失事故回归锁）
    //
    // 事故复现：Fork 熔断退化为 TakeRemote → 云端空整包覆盖本地 → 本地只剩残骸
    // → 下一轮 diff 看见「几十个节点消失」→ 全部 tombstone → 云端历史同步归零。
    // 一秒内 51 条被标删。
    //
    // 这几个测试钉死的承诺：diff **永远不会**因为本地突然变空而批量删云端。
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `本地被清空时拒绝批量 tombstone`() {
        val base = (1..74).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "matepad#1", 1000L)

        // 模拟事故：本地被空整包覆盖，只剩 23 条（正是现场数字）
        val survivors = base.take(23)
        val second = ConversationNodeDiff.compute(
            convId, survivors, first.newState, "matepad#1", 2000L
        )

        assertTrue("必须拦下批量删除", second.suppressedDeletion != null)
        assertTrue(
            "拦截后不得生成任何 tombstone 语句",
            second.statements.none { it.sql.contains("deleted = 1") }
        )
    }

    @Test
    fun `拦截后基准保留消失节点 下一轮仍可重试`() {
        val base = (1..74).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "matepad#1", 1000L)
        val second = ConversationNodeDiff.compute(
            convId, base.take(23), first.newState, "matepad#1", 2000L
        )

        // 关键：消失节点的旧 sha 必须留在 state 里。
        // 若被丢弃，下一轮 diff 就「看不见」它们了 —— 删除会被永久吞掉，
        // 而 pull 补回节点后也无法正确对比。
        assertEquals(74, second.newState.size)

        // 数据被 pull 补回后，一切恢复正常、零语句
        val third = ConversationNodeDiff.compute(convId, base, second.newState, "matepad#1", 3000L)
        assertTrue("补回后应无变化", third.isEmpty)
        assertEquals(null, third.suppressedDeletion)
    }

    @Test
    fun `正常删少量消息不受安全阀影响`() {
        val base = (1..40).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "k70#1", 1000L)

        // 用户手动删了 3 条：远低于阈值，必须照常 tombstone
        val second = ConversationNodeDiff.compute(
            convId, base.dropLast(3), first.newState, "k70#1", 2000L
        )
        assertEquals(null, second.suppressedDeletion)
        assertEquals(3, second.statements.count { it.sql.contains("deleted = 1") })
    }

    @Test
    fun `本地被清成全空时必须拦截`() {
        // 事故 conv e3157067：本地被空整包覆盖成 0 条，云端 112 条全灭。
        // 这是最危险的形态，绝不能因为 alive==0 让比例计算失效而漏判。
        val base = (1..112).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "matepad#1", 1000L)

        val second = ConversationNodeDiff.compute(convId, emptyList(), first.newState, "matepad#1", 2000L)
        assertTrue("本地全空必须拦下", second.suppressedDeletion != null)
        assertTrue(second.statements.none { it.sql.contains("deleted = 1") })
    }

    @Test
    fun `中段历史被挖走时拦截`() {
        // 事故 conv 3aefe0b5：110 条里中间连续 31 条被标删（28%）。
        val base = (1..110).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "matepad#1", 1000L)

        val survivors = base.take(40) + base.drop(71)
        val second = ConversationNodeDiff.compute(convId, survivors, first.newState, "matepad#1", 2000L)
        assertTrue("中段挖空必须拦下", second.suppressedDeletion != null)
    }

    @Test
    fun `长会话正常批量整理仍放行`() {
        // 200 条会话删 15 条（7.5%）：低于比例阀，属正常清理，必须照常 tombstone。
        val base = (1..200).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "k70#1", 1000L)

        val second = ConversationNodeDiff.compute(convId, base.dropLast(15), first.newState, "k70#1", 2000L)
        assertEquals(null, second.suppressedDeletion)
        assertEquals(15, second.statements.count { it.sql.contains("deleted = 1") })
    }

    @Test
    fun `短会话整个清空仍允许 不被比例阀误伤`() {
        // 5 条的小会话全删：条数没过 MAX_TOMBSTONES_PER_ROUND，应放行。
        // 安全阀要求「条数 AND 比例」双超标，避免把小会话的正常清空判成故障。
        val base = (1..5).map { node("msg-$it") }
        val first = ConversationNodeDiff.compute(convId, base, emptyMap(), "k70#1", 1000L)

        val second = ConversationNodeDiff.compute(convId, emptyList(), first.newState, "k70#1", 2000L)
        assertEquals(null, second.suppressedDeletion)
        assertEquals(5, second.statements.count { it.sql.contains("deleted = 1") })
    }

    // ────────────────────────────────────────────────────────────────
    // 2026-09-18 结构分叉 / 旧盖新丢数据回归锁
    //
    // 三个现场：① 同一 idx 挂多个 node（ecf452b1 裂 68 个）；
    // ② 对端半截快照覆盖本端完整版（用户实测丢消息）；
    // ③ tombstone 无时间保护，慢时钟设备能误删新数据。
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `UPSERT 不再覆盖 idx`() {
        val nodes = listOf(node("a"), node("b"))
        val r = ConversationNodeDiff.compute(convId, nodes, emptyMap(), "k70#1", 1000L)
        assertTrue(
            "idx 是推送方本地下标（位置量），冲突时绝不能再覆盖，否则两端互相踩",
            r.statements.none { it.sql.contains("idx = excluded.idx") }
        )
    }

    @Test
    fun `UPSERT 带 LWW 仲裁条件`() {
        val nodes = listOf(node("a"))
        val r = ConversationNodeDiff.compute(convId, nodes, emptyMap(), "k70#1", 1000L)
        val sql = r.statements.first().sql
        assertTrue(
            "必须有 updated_at 新旧比较，否则旧快照能盖掉新内容",
            sql.contains("excluded.updated_at > conv_nodes.updated_at")
        )
        assertTrue(
            "同毫秒必须用 last_device 兜底定序，保证两端算出同一个赢家",
            sql.contains("excluded.last_device > conv_nodes.last_device")
        )
    }

    @Test
    fun `tombstone 带 LWW 保护`() {
        val a = node("a")
        val b = node("b")
        val first = ConversationNodeDiff.compute(convId, listOf(a, b), emptyMap(), "k70#1", 1000L)
        val second = ConversationNodeDiff.compute(convId, listOf(a), first.newState, "k70#1", 2000L)
        val tomb = second.statements.first { it.sql.contains("deleted = 1") }
        assertTrue("删除不可逆，必须拒绝慢时钟设备的误删", tomb.sql.contains("updated_at < ?"))
        assertEquals(4, tomb.params.size)
    }

    @Test
    fun `首次 INSERT 仍然写入 idx`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val r = ConversationNodeDiff.compute(convId, nodes, emptyMap(), "k70#1", 1000L)
        assertTrue(
            "idx 是 NOT NULL 列，首次插入必须带值（只是之后不再更新）",
            r.statements.all { it.sql.contains("INSERT INTO conv_nodes(conv_id, node_id, idx,") }
        )
        assertEquals(listOf(0, 1, 2), r.statements.map { it.params[2] })
    }
}
