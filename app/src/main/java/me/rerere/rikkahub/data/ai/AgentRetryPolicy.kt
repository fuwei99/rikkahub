package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * subagent / schedule agent 的失败分类与重试节奏（PLAN_AGENT_RETRY_FALLBACK）。
 *
 * 背景：这两类 agent 原本**一次报错就整任务失败**，没有重试也没有备用模型
 * （全仓 grep retry 在 subagent/ 与 schedule/ 下零命中）。
 *
 * 这里只做「判断 + 算退避」，不碰生成逻辑，方便单测钉死每一类错误的走向。
 */
object AgentRetryPolicy {

    /** 失败后该怎么走。 */
    enum class Decision {
        /** 同一个模型再试（网络抖动、限流、5xx）。 */
        RETRY_SAME_MODEL,

        /** 这个模型没救了，换备用模型（模型不存在 / 不支持 / 上下文超限）。 */
        SWITCH_MODEL,

        /** 重试毫无意义，直接失败（鉴权、余额、请求非法、业务性失败）。 */
        FATAL,
    }

    /** 每个模型最多尝试几次。 */
    const val MAX_ATTEMPTS_PER_MODEL = 3

    /** 主模型 + 备用模型合计最多用几个。 */
    const val MAX_MODELS = 4

    /** 备用模型最多配几个（UI 与模板校验共用）。 */
    const val MAX_FALLBACK_MODELS = 3

    /**
     * 第 [attempt] 次失败后该等多久（attempt 从 1 开始）。
     *
     * 2s / 8s / 20s，带 ±20% 抖动：多个 agent 常常被同一次网络故障一起打挂，
     * 不抖动就会在同一毫秒一起重试，把上游再打一遍。
     */
    fun backoffMillis(attempt: Int, random: Random = Random.Default): Long {
        val base = when (attempt.coerceAtLeast(1)) {
            1 -> 2_000L
            2 -> 8_000L
            else -> 20_000L
        }
        val jitter = (base * 0.2).toLong()
        return base + random.nextLong(-jitter, jitter + 1)
    }

    /**
     * 判断一个异常该怎么处理。
     *
     * **[CancellationException] 绝不在此分类** —— 调用方必须先 rethrow。
     * 一旦把取消当可重试错误，用户点❌取消不掉、任务变僵尸还继续烧 token。
     * 这里也做一次兜底判断（返回 FATAL），但真正的保证在调用侧。
     */
    fun classify(error: Throwable): Decision {
        if (error is CancellationException) return Decision.FATAL

        // 业务性失败：预算耗尽 / 步数耗尽，重试只是再烧一遍钱
        val message = (error.message ?: "")
        if (message.contains("token budget exceeded", ignoreCase = true)) return Decision.FATAL

        // 网络层抖动：一律可重试
        if (error is SocketTimeoutException) return Decision.RETRY_SAME_MODEL
        if (error is UnknownHostException) return Decision.RETRY_SAME_MODEL
        if (error is IOException) return Decision.RETRY_SAME_MODEL

        httpStatusOf(message)?.let { status ->
            return when (status) {
                // 限流 / 过载 / 网关抖动
                408, 409, 425, 429, 500, 502, 503, 504 -> Decision.RETRY_SAME_MODEL
                // 模型不存在 / 无权访问该模型 / 请求实体过大（上下文超限）→ 换模型才有意义
                404, 413 -> Decision.SWITCH_MODEL
                // 鉴权、余额、请求非法：换模型和重试都救不了
                400, 401, 402, 403 -> Decision.FATAL
                else -> if (status >= 500) Decision.RETRY_SAME_MODEL else Decision.FATAL
            }
        }

        // 没有状态码时看文案（各家网关的错误体格式五花八门）
        return when {
            message.containsAny("rate limit", "ratelimit", "too many requests", "overloaded",
                "capacity", "server_error", "try again", "timeout", "timed out",
                "connection reset", "temporarily unavailable") -> Decision.RETRY_SAME_MODEL

            message.containsAny("model not found", "unknown model", "does not exist",
                "not supported", "context length", "maximum context",
                "too many tokens") -> Decision.SWITCH_MODEL

            message.containsAny("invalid api key", "incorrect api key", "unauthorized",
                "permission denied", "insufficient", "quota", "billing",
                "arrearage", "invalid_request") -> Decision.FATAL

            // 认不出来的异常：给一次重试的机会，但别切模型（可能是偶发解析错误）
            else -> Decision.RETRY_SAME_MODEL
        }
    }

    /** 从错误文案里抠 HTTP 状态码（各 provider 都会把它写进 message）。 */
    private fun httpStatusOf(message: String): Int? {
        if (message.isBlank()) return null
        val patterns = listOf(
            Regex("""\bHTTP\s+(\d{3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bstatus[ _-]?code["'\s:=]+(\d{3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcode[=:\s]+(\d{3})\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{3})\s+(?:Bad Request|Unauthorized|Forbidden|Not Found|Payload Too Large|Too Many Requests|Internal Server Error|Bad Gateway|Service Unavailable|Gateway Time-?out)\b""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val v = p.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (v != null && v in 100..599) return v
        }
        return null
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it, ignoreCase = true) }
}
