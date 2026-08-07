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
}
