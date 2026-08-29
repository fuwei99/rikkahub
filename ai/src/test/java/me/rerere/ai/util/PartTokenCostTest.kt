package me.rerere.ai.util

import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * part 级 token 记账的回归测试（2026-08-30）。
 *
 * 这套东西全是「整数总额按权重摊到若干 part」，最容易出的错不是崩，而是**账不平**：
 * 少摊一点、多摊一点、或者同一段内容被记两遍，都不会抛异常，只会让压缩刀口悄悄跑偏。
 * 08-28 的教训是这类静默偏差能活半个月，所以这里全部用「总和是否严格等于服务端账单」
 * 来断言，而不是去比某个 part 的具体数字。
 */
class PartTokenCostTest {

    private fun text(s: String) = UIMessagePart.Text(s)

    private fun tool(name: String, input: String, output: String?) = UIMessagePart.Tool(
        toolCallId = "call_$name",
        toolName = name,
        input = input,
        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList(),
    )

    private fun user(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.USER, parts = parts.toList())

    private fun assistant(
        vararg parts: UIMessagePart,
        prompt: Int,
        completion: Int,
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
        usage = TokenUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = prompt + completion,
        ),
    )

    // ---- distributeByWeight 的核心契约：总和必须严格等于 total ----

    @Test
    fun `completion tokens are fully distributed with no loss`() {
        val a = text("hello world this is a fairly long answer")
        val b = text("短")
        val msg = assistant(a, b, prompt = 1000, completion = 997)

        msg.attachRealUsage(msg.usage)

        val sum = msg.parts.sumOf { it.recordedTokenCost() ?: 0L }
        // 997 是质数，能整除的概率为零 —— 专门用来验末位补齐有没有把余数丢掉
        assertEquals(997L, sum)
    }

    @Test
    fun `single part takes the whole completion`() {
        val only = text("only one")
        val msg = assistant(only, prompt = 500, completion = 123)

        msg.attachRealUsage(msg.usage)

        assertEquals(123L, only.recordedTokenCost() ?: 0L)
        assertTrue(only.hasRealTokenCost())
    }

    @Test
    fun `identical parts must not share or double count`() {
        // UIMessagePart 是 data class：两个内容相同的 part 会 equals。
        // 早期实现拿 part 当 map key，两笔份额被合并后给两边各写一遍 = 凭空翻倍。
        val p1 = text("重复的一句话")
        val p2 = text("重复的一句话")
        val msg = assistant(p1, p2, prompt = 100, completion = 100)

        msg.attachRealUsage(msg.usage)

        assertEquals(100L, msg.parts.sumOf { it.recordedTokenCost() ?: 0L })
    }

    // ---- promptTokens 差分 ----

    @Test
    fun `prompt delta is attributed to non generated content`() {
        // 轮 1：user 提问 → assistant 回答（prompt 含 80k 恒定开销）
        val q1 = text("第一个问题")
        val a1 = text("第一个回答")
        // 轮 2：prompt 涨了 500，其中 completion(轮1)=60 已被单独认领，
        //       剩下 440 属于 user 的新消息
        val q2 = text("第二个问题，比较长一点点")
        val a2 = text("第二个回答")

        val list = listOf(
            user(q1),
            assistant(a1, prompt = 80_000, completion = 60),
            user(q2),
            assistant(a2, prompt = 80_500, completion = 70),
        )

        val report = list.calibrateTokenCostsFromUsage()

        // 第一条 assistant 的正文 = 那轮的 completionTokens，真数
        assertEquals(60L, a1.recordedTokenCost() ?: 0L)
        assertTrue(a1.hasRealTokenCost())
        // 第二条同理
        assertEquals(70L, a2.recordedTokenCost() ?: 0L)
        // q2 拿到差分：80500 - 80000 - 60 = 440，全部归它（q1/a1 已被 completion 覆盖）
        assertEquals(440L, q2.recordedTokenCost() ?: 0L)
        assertTrue(q2.hasRealTokenCost())
        // 恒定的 80k 开销在做差时被约掉了，绝不能出现在任何 part 上
        assertTrue(list.flatMap { it.parts }.all { (it.recordedTokenCost() ?: 0L) < 1000 })
        assertTrue(report.segments > 0)
    }

    @Test
    fun `first message with usage only establishes baseline`() {
        // 没有基线时做 P1 - 0 会把 80k 常数全摊到开头，是纯污染
        val q = text("问题")
        val a = text("回答")
        listOf(user(q), assistant(a, prompt = 80_000, completion = 50)).calibrateTokenCostsFromUsage()

        assertEquals(50L, a.recordedTokenCost() ?: 0L)
        // user 那条只能回落估算，绝不该拿到 80000
        assertTrue((q.recordedTokenCost() ?: 0L) < 100)
    }

    @Test
    fun `tool output is charged via prompt delta not completion`() {
        val t = tool("read_file", """{"path":"a.kt"}""", "x".repeat(4000))
        val next = text("基于结果的回答")
        val list = listOf(
            assistant(t, prompt = 10_000, completion = 30),
            // 下一轮 prompt 涨了 1030，扣掉上轮 completion 30，剩 1000 = 工具 output 的真实成本
            assistant(next, prompt = 11_030, completion = 40),
        )

        list.calibrateTokenCostsFromUsage()

        // 工具 part 拿到「入参份额 30 + output 份额 1000」
        assertEquals(1030L, t.recordedTokenCost() ?: 0L)
        assertEquals(40L, next.recordedTokenCost() ?: 0L)
    }

    @Test
    fun `negative delta is skipped and falls back to estimate`() {
        // 换模型 / 开新会话 / 上下文被折叠 → prompt 反而变小，绝不能灌负数
        val q = text("问题")
        val a1 = text("回答一")
        val a2 = text("回答二")
        val list = listOf(
            assistant(a1, prompt = 90_000, completion = 20),
            user(q),
            assistant(a2, prompt = 5_000, completion = 30),
        )

        list.calibrateTokenCostsFromUsage()

        list.flatMap { it.parts }.forEach {
            val v = it.recordedTokenCost()
            assertNotNull(v as Any?)
            assertTrue("token cost must never be negative, got $v", v!! >= 0)
        }
        // q 落在一段被跳过的差分里，只能是估算
        assertEquals(
            TOKEN_SOURCE_ESTIMATE,
            q.metadata?.get(PART_TOKEN_SOURCE_KEY)?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `conversation without any usage degrades to estimation`() {
        val a = text("纯本地消息")
        val b = text("另一条")
        val list = listOf(user(a), user(b))

        val report = list.calibrateTokenCostsFromUsage()

        assertEquals(0L, report.segments.toLong())
        assertEquals(0L, report.realParts.toLong())
        assertEquals(2L, report.estimatedParts.toLong())
        // 值必须等于旧口径，保证零回归
        assertEquals(a.estimateSelf(), a.recordedTokenCost() ?: 0L)
    }

    @Test
    fun `calibration is idempotent`() {
        val q = text("问题内容")
        val a1 = text("回答内容")
        val a2 = text("第二个回答")
        val list = listOf(
            assistant(a1, prompt = 1_000, completion = 100),
            user(q),
            assistant(a2, prompt = 1_600, completion = 200),
        )

        list.calibrateTokenCostsFromUsage()
        val first = list.flatMap { it.parts }.map { it.recordedTokenCost() }
        list.calibrateTokenCostsFromUsage()
        list.calibrateTokenCostsFromUsage()
        val third = list.flatMap { it.parts }.map { it.recordedTokenCost() }

        // 反复压缩会反复调用它，逐 part 累加式的实现会越调越大
        assertEquals(first, third)
    }

    @Test
    fun `existing real value is never downgraded to estimate`() {
        // 对话尾巴那条可能拿不到任何差分分配（没有「下一次 promptTokens」），
        // 但它在流式落库时已由 attachRealUsage 打上真值，重算不得把它降级。
        val tail = text("最新一条回答")
        tail.withTokenCost(88L, TOKEN_SOURCE_REAL)
        // completion = 0 → 本轮不会给它任何分配，恰好走到「保留旧 real」那条分支
        val msg = assistant(tail, prompt = 2_000, completion = 0)

        val report = listOf(msg).calibrateTokenCostsFromUsage()

        assertTrue(tail.hasRealTokenCost())
        assertEquals(88L, tail.recordedTokenCost() ?: 0L)
        assertEquals(1L, report.realParts.toLong())
    }

    // ---- tokenCost 三级取数 ----

    @Test
    fun `tokenCost caches estimate only when asked`() {
        val p = text("一段没有任何记录的文本")
        assertNull(p.recordedTokenCost() as Any?)

        assertEquals(p.estimateSelf(), p.tokenCost(cacheIfMissing = false))
        assertNull("不该在只读取时写缓存", p.recordedTokenCost() as Any?)

        p.tokenCost(cacheIfMissing = true)
        assertEquals(p.estimateSelf(), p.recordedTokenCost() ?: 0L)
    }

    // ---- 图片按分辨率 ----

    @Test
    fun `image cost scales with resolution`() {
        val thumb = imageTokenCost(64, 64)
        val hd = imageTokenCost(1920, 1080)
        val huge = imageTokenCost(8000, 8000)

        assertTrue("大图必须比缩略图贵", hd > thumb)
        // 长边超 2048 会先被等比缩放，不能算出天文数字
        assertTrue("超大图要先缩放再分块", huge < 4000)
        // 拿不到尺寸时回落常数，保持旧行为
        assertEquals(800L, imageTokenCost(0, 0))
    }
}
