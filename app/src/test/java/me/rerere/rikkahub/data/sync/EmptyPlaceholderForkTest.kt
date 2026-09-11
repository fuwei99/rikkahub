package me.rerere.rikkahub.data.sync

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.sync.core.ConversationMerger
import me.rerere.rikkahub.data.sync.core.ForkCircuitBreaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 2026-09-11 无限分支增殖事故的回归锁。
 *
 * 事故链条：生成中断留下 text="" 的空 assistant 节点 → 推上云 → 对端拓扑对不上
 * → 判 Fork → 另存新会话 → 新会话又上云 → 对端又判分叉……自激增殖。
 * 现场表现为同一个 createAt 在两小时内派生出 6 个副本，全部只含一条空消息。
 *
 * 更恶心的是 Fork 时「谁保留原 ID」只看 tieBreak 字典序、不看谁有内容，
 * 于是空壳那端可能反客为主，把真实内容撵去挂后缀 —— 用户看到的就是「主对话变空了」。
 *
 * 这里锁住两件事：空节点不参与拓扑判断，以及熔断器能兜住漏网之鱼。
 */
class EmptyPlaceholderForkTest {

    @Before
    fun setUp() = ForkCircuitBreaker.clear()

    private fun textNode(text: String, id: Uuid = Uuid.random()) = MessageNode(
        id = id,
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
        ),
    )

    /** 生成被中断留下的残骸：一条 text 全空的 assistant */
    private fun emptyNode(id: Uuid = Uuid.random()) = MessageNode(
        id = id,
        messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")))
        ),
    )

    private fun conv(vararg nodes: MessageNode) = Conversation(
        id = CONV_ID,
        assistantId = ASSISTANT_ID,
        title = "矩阵特征值计算",
        messageNodes = nodes.toList(),
    )

    @Test
    fun `一端多出空占位节点时不得判为分叉`() {
        val shared = textNode("帮我算特征值")
        val local = conv(shared)
        // 手机端生成被中断，多出一个空壳
        val remote = conv(shared, emptyNode())

        val r = ConversationMerger.resolve(local, remote, "k70#aaa", "matepad#bbb")

        assertFalse(
            "空占位节点不得触发 Fork（这正是无限增殖的起点）",
            r is ConversationMerger.Resolution.Fork
        )
    }

    @Test
    fun `两端各有不同的空占位节点也不得分叉`() {
        val shared = textNode("帮我算特征值")
        val local = conv(shared, emptyNode())
        val remote = conv(shared, emptyNode())

        val r = ConversationMerger.resolve(local, remote, "k70#aaa", "matepad#bbb")

        assertFalse(
            "两端各自的中断残骸互不相干，不是真冲突",
            r is ConversationMerger.Resolution.Fork
        )
    }

    @Test
    fun `真实内容冲突仍然必须能正常分叉`() {
        val shared = textNode("帮我算特征值")
        val conflictId = Uuid.random()
        // 同一个 node id，两端写了不同的真实内容 —— 这才是该 Fork 的场景
        val local = conv(shared, textNode("本地写的内容", id = conflictId))
        val remote = conv(shared, textNode("远端写的内容", id = conflictId))

        val r = ConversationMerger.resolve(local, remote, "k70#aaa", "matepad#bbb")

        assertTrue(
            "别矫枉过正：真冲突还是要分叉的",
            r is ConversationMerger.Resolution.Fork
        )
    }

    @Test
    fun `带工具调用或图片的节点不算空壳`() {
        val shared = textNode("看图")
        // 文本为空但带了图片，属于真实内容，不能被当成残骸滤掉
        val imageNode = MessageNode(
            id = Uuid.random(),
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(""), UIMessagePart.Image("file://x.png")),
                )
            ),
        )
        val local = conv(shared)
        val remote = conv(shared, imageNode)

        val r = ConversationMerger.resolve(local, remote, "k70#aaa", "matepad#bbb")

        assertTrue(
            "空文本 + 图片是真实内容，应当被正常快进而非忽略",
            r is ConversationMerger.Resolution.TakeRemote
        )
    }

    // ---- 熔断器 ----

    @Test
    fun `同一会话短时间内反复分叉会被熔断`() {
        val id = CONV_ID.toString()
        assertTrue("第 1 次放行", ForkCircuitBreaker.allow(id))
        assertTrue("第 2 次放行", ForkCircuitBreaker.allow(id))
        assertFalse("第 3 次必须熔断，否则会话列表会被副本刷爆", ForkCircuitBreaker.allow(id))
    }

    @Test
    fun `熔断按会话隔离互不影响`() {
        val a = Uuid.random().toString()
        val b = Uuid.random().toString()
        ForkCircuitBreaker.allow(a)
        ForkCircuitBreaker.allow(a)
        assertFalse(a, ForkCircuitBreaker.allow(a))
        assertTrue("另一个会话不该被连坐", ForkCircuitBreaker.allow(b))
    }

    private companion object {
        val CONV_ID: Uuid = Uuid.random()
        val ASSISTANT_ID: Uuid = Uuid.random()
    }
}
