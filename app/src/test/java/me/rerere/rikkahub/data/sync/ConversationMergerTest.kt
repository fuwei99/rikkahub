package me.rerere.rikkahub.data.sync

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.core.ConversationMerger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 前缀快进合并的回归锁。
 *
 * 这套逻辑取代了原来的会话互斥锁：核心承诺是「一台设备多发了几条消息」
 * 必须走快进、绝不产生分支，只有真分叉才分支。一旦这里退化，
 * 用户的表现就是对话被莫名复制成一堆 xxx-k70。
 */
class ConversationMergerTest {

    private val assistantId = Uuid.random()

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

    private fun conv(nodes: List<MessageNode>, title: String = "t"): Conversation =
        Conversation(
            id = Uuid.random(),
            assistantId = assistantId,
            title = title,
            messageNodes = nodes,
        )

    @Test
    fun `远端是本地前缀时保留本地`() {
        val shared = listOf(node("a"), node("b"))
        val local = conv(shared + node("c"))
        val remote = conv(shared)

        val r = ConversationMerger.resolve(local, remote, "k70#1", "matepad#2")
        assertEquals(ConversationMerger.Resolution.KeepLocal, r)
    }

    @Test
    fun `本地是远端前缀时快进采纳远端`() {
        val shared = listOf(node("a"), node("b"))
        val local = conv(shared)
        val remote = conv(shared + node("c") + node("d"))

        val r = ConversationMerger.resolve(local, remote, "k70#1", "matepad#2")
        assertEquals(ConversationMerger.Resolution.TakeRemote, r)
    }

    @Test
    fun `内容与元数据全同判定为等价`() {
        val shared = listOf(node("a"), node("b"))
        val local = conv(shared, title = "same")
        val remote = conv(shared, title = "same")

        val r = ConversationMerger.resolve(local, remote, "k70#1", "matepad#2")
        assertEquals(ConversationMerger.Resolution.Identical, r)
    }

    @Test
    fun `节点相同但标题不同时保留本地改名`() {
        val shared = listOf(node("a"))
        val local = conv(shared, title = "renamed")
        val remote = conv(shared, title = "old")

        val r = ConversationMerger.resolve(local, remote, "k70#1", "matepad#2")
        assertEquals(ConversationMerger.Resolution.KeepLocal, r)
    }

    @Test
    fun `真分叉才产生分支且裁决键大者保留原id`() {
        val shared = listOf(node("a"))
        val local = conv(shared + node("local-branch"))
        val remote = conv(shared + node("remote-branch"))

        val winner = ConversationMerger.resolve(local, remote, "matepad#2", "k70#1")
        assertTrue(winner is ConversationMerger.Resolution.Fork)
        assertEquals(1, (winner as ConversationMerger.Resolution.Fork).commonPrefixLength)
        assertTrue(winner.localKeepsId)

        // 对端视角必须得出相反结论，否则两台设备会来回抢同一个 id
        val loser = ConversationMerger.resolve(local, remote, "k70#1", "matepad#2")
        assertTrue(loser is ConversationMerger.Resolution.Fork)
        assertTrue(!(loser as ConversationMerger.Resolution.Fork).localKeepsId)
    }

    @Test
    fun `旧客户端写入的远端行没有裁决键时本地保留原id`() {
        val shared = listOf(node("a"))
        val local = conv(shared + node("local-branch"))
        val remote = conv(shared + node("remote-branch"))

        val r = ConversationMerger.resolve(local, remote, "k70#1", null)
        assertTrue(r is ConversationMerger.Resolution.Fork)
        assertTrue((r as ConversationMerger.Resolution.Fork).localKeepsId)
    }

    @Test
    fun `同id节点内容被对端编辑过不算公共前缀`() {
        val sharedId = Uuid.random()
        val local = conv(listOf(node("original", sharedId)))
        val remote = conv(listOf(node("edited", sharedId)))

        val r = ConversationMerger.resolve(local, remote, "matepad#2", "k70#1")
        assertTrue(r is ConversationMerger.Resolution.Fork)
        assertEquals(0, (r as ConversationMerger.Resolution.Fork).commonPrefixLength)
    }

    @Test
    fun `分支标题幂等不会叠加后缀`() {
        assertEquals("聊天-k70", ConversationMerger.forkTitle("聊天", "k70"))
        assertEquals("聊天-k70", ConversationMerger.forkTitle("聊天-k70", "k70"))
        assertEquals("对话-k70", ConversationMerger.forkTitle("", "k70"))
    }
}
