package me.rerere.rikkahub.data.sync.core

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import me.rerere.rikkahub.data.sync.d1.D1Exception
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * outbox 推送失败的错误分类。
 *
 * 历史故障（2026-08-08）：`sync_outbox` 只有一个只增不减的 `retry_count`，
 * 把「暂时没网」和「数据永久有毒」判了同一个刑。一次离线期间连续几轮 flush
 * 就能把 retry_count 顶到上限，此后 `pending()`（WHERE retry_count < 5）
 * 永久捞不到它，网络恢复也不会自愈 —— 除非用户恰好再改一次那条会话。
 * 现场抓到的两条正是 `UnknownHostException` 与 `CancellationException`。
 *
 * 因此把失败分成三类，只有 [Verdict.PERMANENT] 才累加重试计数：
 * - [Verdict.TRANSIENT]：网络/超时/5xx/限流 —— 不计数，只推迟下次尝试
 * - [Verdict.PERMANENT]：D1 明确拒绝语句（4xx，如 7400 参数不合法）、
 *   序列化失败等「重试一万次也一样」的错误 —— 计数，最终进隔离区
 * - [Verdict.CANCELLED]：协程取消（切后台/杀进程）—— 正常生命周期事件，
 *   既不计数也不该记为业务失败，调用方应原样 rethrow
 */
object SyncFailureClassifier {

    enum class Verdict { TRANSIENT, PERMANENT, CANCELLED }

    /** D1 侧「数据有毒」的错误码：这类重试无意义 */
    private val PERMANENT_D1_CODES = setOf(
        7400, // A prepared SQL statement must contain only one statement / 参数不合法
        7500, // 多语句解析失败
        7501,
    )

    fun classify(e: Throwable): Verdict {
        // 协程取消：kotlin.coroutines.cancellation.CancellationException 在 JVM 上
        // 就是 java.util.concurrent.CancellationException，用 kotlin 侧类型即可覆盖。
        if (e is kotlinx.coroutines.CancellationException) return Verdict.CANCELLED
        // runCatching 可能把取消包在 cause 里（如 "StandaloneCoroutine was cancelled"）
        if (e.causeChain().any { it is kotlinx.coroutines.CancellationException }) {
            return Verdict.CANCELLED
        }

        if (e.causeChain().any { it.isTransientNetwork() }) return Verdict.TRANSIENT

        val d1 = e.causeChain().filterIsInstance<D1Exception>().firstOrNull()
        if (d1 != null) return classifyD1(d1)

        // 未知错误保守当瞬时：宁可多重试几次，也不要把用户数据永久卡在隔离区。
        return Verdict.TRANSIENT
    }

    private fun classifyD1(e: D1Exception): Verdict {
        val msg = e.message ?: return Verdict.TRANSIENT
        if (PERMANENT_D1_CODES.any { msg.contains("[$it]") }) return Verdict.PERMANENT
        // "D1 HTTP 4xx"：请求本身不合法（除 408 超时 / 429 限流外不可重试）
        HTTP_STATUS.find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { code ->
            if (code == 408 || code == 429) return Verdict.TRANSIENT
            if (code in 400..499) return Verdict.PERMANENT
            return Verdict.TRANSIENT // 5xx / 网关抖动
        }
        // 解析失败：响应体结构对不上，多半是网关返回的 HTML 错误页 → 瞬时
        if (msg.contains("Failed to parse D1 response")) return Verdict.TRANSIENT
        // 语句本身被 D1 判失败（statement failed）：数据有毒
        if (msg.contains("D1 statement failed")) return Verdict.PERMANENT
        return Verdict.TRANSIENT
    }

    private val HTTP_STATUS = Regex("""D1 HTTP (\d{3})""")

    private fun Throwable.isTransientNetwork(): Boolean = when (this) {
        is UnknownHostException,      // 没网 / DNS 未就绪（现场故障 #1）
        is ConnectException,
        is NoRouteToHostException,
        is SocketException,
        is SocketTimeoutException,
        is ConnectTimeoutException,
        is HttpRequestTimeoutException,
        is SSLException,
        -> true
        is IOException -> true        // 兜底：okhttp/ktor 的各种流中断
        else -> false
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
        var cur: Throwable? = this@causeChain
        var guard = 0
        while (cur != null && guard++ < 16) {
            yield(cur)
            cur = cur.cause.takeIf { it !== cur }
        }
    }

    /**
     * 指数退避：1s → 4s → 16s → 64s → …，上限 15 分钟。
     * 瞬时错误不推进 retryCount，所以用一个独立的 transientAttempt 计数驱动。
     */
    fun backoffMs(attempt: Int): Long {
        val capped = attempt.coerceIn(0, 8)
        val ms = 1000L * (1L shl (capped * 2).coerceAtMost(62))
        return ms.coerceAtMost(15 * 60 * 1000L)
    }
}
