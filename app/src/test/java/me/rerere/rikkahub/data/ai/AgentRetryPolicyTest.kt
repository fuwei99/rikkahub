package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.AgentRetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * 重试分类器的行为回归（PLAN_AGENT_RETRY_FALLBACK §2.3）。
 *
 * 最重要的一条：CancellationException 永远 FATAL。
 * 把取消当可重试错误会让用户取消不掉、任务变僵尸还继续烧 token。
 */
class AgentRetryPolicyTest {

    @Test
    fun `取消永远不重试`() {
        assertEquals(Decision.FATAL, AgentRetryPolicy.classify(CancellationException("user cancelled")))
    }

    @Test
    fun `网络异常一律重试同一个模型`() {
        assertEquals(Decision.RETRY_SAME_MODEL, AgentRetryPolicy.classify(SocketTimeoutException()))
        assertEquals(Decision.RETRY_SAME_MODEL, AgentRetryPolicy.classify(UnknownHostException("api.example.com")))
        assertEquals(Decision.RETRY_SAME_MODEL, AgentRetryPolicy.classify(IOException("connection reset by peer")))
    }

    @Test
    fun `限流与5xx重试`() {
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            assertEquals(
                "HTTP $code should retry",
                Decision.RETRY_SAME_MODEL,
                AgentRetryPolicy.classify(RuntimeException("HTTP $code upstream error")),
            )
        }
        assertEquals(Decision.RETRY_SAME_MODEL, AgentRetryPolicy.classify(RuntimeException("Rate limit exceeded")))
        assertEquals(Decision.RETRY_SAME_MODEL, AgentRetryPolicy.classify(RuntimeException("model is overloaded")))
    }

    @Test
    fun `鉴权与余额问题不重试`() {
        listOf(400, 401, 402, 403).forEach { code ->
            assertEquals(
                "HTTP $code should be fatal",
                Decision.FATAL,
                AgentRetryPolicy.classify(RuntimeException("HTTP $code request rejected")),
            )
        }
        assertEquals(Decision.FATAL, AgentRetryPolicy.classify(RuntimeException("Invalid API key provided")))
        assertEquals(Decision.FATAL, AgentRetryPolicy.classify(RuntimeException("insufficient quota / billing")))
    }

    @Test
    fun `模型不存在或上下文超限要换模型`() {
        assertEquals(Decision.SWITCH_MODEL, AgentRetryPolicy.classify(RuntimeException("HTTP 404 model not found")))
        assertEquals(Decision.SWITCH_MODEL, AgentRetryPolicy.classify(RuntimeException("The model does not exist")))
        assertEquals(
            Decision.SWITCH_MODEL,
            AgentRetryPolicy.classify(RuntimeException("This model's maximum context length is 8192 tokens")),
        )
    }

    @Test
    fun `token预算耗尽是业务失败不重试`() {
        assertEquals(
            Decision.FATAL,
            AgentRetryPolicy.classify(IllegalStateException("Subagent token budget exceeded (70000/64000)")),
        )
    }

    @Test
    fun `认不出来的异常给一次重试机会`() {
        assertEquals(Decision.RETRY_SAME_MODEL, AgentRetryPolicy.classify(RuntimeException("weird parse glitch")))
    }

    @Test
    fun `退避递增且带抖动但不为负`() {
        val rnd = Random(42)
        val first = AgentRetryPolicy.backoffMillis(1, rnd)
        val second = AgentRetryPolicy.backoffMillis(2, rnd)
        val third = AgentRetryPolicy.backoffMillis(3, rnd)
        assertTrue("first=$first", first in 1_600..2_400)
        assertTrue("second=$second", second in 6_400..9_600)
        assertTrue("third=$third", third in 16_000..24_000)
        // 超出档位的 attempt 复用最后一档，不会爆炸增长
        assertTrue(AgentRetryPolicy.backoffMillis(99, rnd) in 16_000..24_000)
    }
}
