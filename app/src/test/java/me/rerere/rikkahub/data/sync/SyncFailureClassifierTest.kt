package me.rerere.rikkahub.data.sync

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.sync.core.SyncFailureClassifier
import me.rerere.rikkahub.data.sync.core.SyncFailureClassifier.Verdict
import me.rerere.rikkahub.data.sync.d1.D1Exception
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * 2026-08-08 故障的回归锁。
 *
 * 现场：sync_outbox 两条记录 retry_count=5 永久卡死，错误分别是
 * `Unable to resolve host "api.cloudflare.com"` 与 `StandaloneCoroutine was cancelled`。
 * 二者都不是数据问题，却被当成"重复失败"判进隔离区。
 * 这里锁住：瞬时网络错误与协程取消**永不**被判成 PERMANENT。
 */
class SyncFailureClassifierTest {

    @Test
    fun `DNS 解析失败判为瞬时`() {
        val e = UnknownHostException("Unable to resolve host \"api.cloudflare.com\": No address associated with hostname")
        assertEquals(Verdict.TRANSIENT, SyncFailureClassifier.classify(e))
    }

    @Test
    fun `连接失败判为瞬时`() {
        assertEquals(Verdict.TRANSIENT, SyncFailureClassifier.classify(ConnectException("failed to connect")))
    }

    @Test
    fun `协程取消判为取消而非失败`() {
        assertEquals(Verdict.CANCELLED, SyncFailureClassifier.classify(CancellationException("StandaloneCoroutine was cancelled")))
    }

    @Test
    fun `包在 cause 里的取消同样识别`() {
        val wrapped = IllegalStateException("push failed", CancellationException("job cancelled"))
        assertEquals(Verdict.CANCELLED, SyncFailureClassifier.classify(wrapped))
    }

    @Test
    fun `嵌套在 cause 链里的网络错误判为瞬时`() {
        val wrapped = IllegalStateException("outer", IOException("inner", UnknownHostException("no dns")))
        assertEquals(Verdict.TRANSIENT, SyncFailureClassifier.classify(wrapped))
    }

    @Test
    fun `D1 返回 7400 判为永久`() {
        val e = D1Exception("D1 API error: [7400] params with multiple statements is not supported")
        assertEquals(Verdict.PERMANENT, SyncFailureClassifier.classify(e))
    }

    @Test
    fun `D1 HTTP 400 判为永久`() {
        assertEquals(Verdict.PERMANENT, SyncFailureClassifier.classify(D1Exception("D1 HTTP 400 Bad Request: bad sql")))
    }

    @Test
    fun `D1 HTTP 429 限流判为瞬时`() {
        assertEquals(Verdict.TRANSIENT, SyncFailureClassifier.classify(D1Exception("D1 HTTP 429 Too Many Requests")))
    }

    @Test
    fun `D1 HTTP 500 判为瞬时`() {
        assertEquals(Verdict.TRANSIENT, SyncFailureClassifier.classify(D1Exception("D1 HTTP 500 Internal Server Error")))
    }

    @Test
    fun `未知错误保守判为瞬时`() {
        // 宁可多重试，也不要把用户数据永久卡在隔离区
        assertEquals(Verdict.TRANSIENT, SyncFailureClassifier.classify(RuntimeException("something odd")))
    }

    @Test
    fun `退避时长单调递增且有上限`() {
        val a = SyncFailureClassifier.backoffMs(0)
        val b = SyncFailureClassifier.backoffMs(1)
        val c = SyncFailureClassifier.backoffMs(2)
        assertTrue("$a < $b", a < b)
        assertTrue("$b < $c", b < c)
        val capped = SyncFailureClassifier.backoffMs(100)
        assertEquals(15 * 60 * 1000L, capped)
        assertTrue(SyncFailureClassifier.backoffMs(0) >= 1000L)
    }
}
