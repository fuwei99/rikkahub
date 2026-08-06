package me.rerere.rikkahub.data.ai

import me.rerere.common.android.AiWireLog
import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // 两套日志各自独立开关：Logging 供 UI 列表（内存），AiWireLog 供完整落盘排查
        val uiLogging = Logging.isRequestLoggingEnabled()
        val wireLogging = AiWireLog.isEnabled()
        if (!uiLogging && !wireLogging) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody = request.body?.let { body ->
            runCatching {
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }.getOrNull()
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            if (uiLogging) {
                Logging.logRequest(
                    LogEntry.RequestLog(
                        tag = "HTTP",
                        url = request.url.toString(),
                        method = request.method,
                        requestHeaders = requestHeaders,
                        requestBody = requestBody,
                        error = error
                    )
                )
            }
            if (wireLogging) {
                AiWireLog.logExchange(
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    responseCode = null,
                    responseHeaders = emptyMap(),
                    responseBody = null,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = error,
                )
            }
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        if (uiLogging) {
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.toString(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    responseCode = response.code,
                    responseHeaders = responseHeaders,
                    durationMs = durationMs,
                    error = error
                )
            )
        }

        if (!wireLogging) return response

        // 响应体处理：非流式直接 peek；流式(SSE)不能在此消费，否则会阻塞/破坏流，
        // 只记录请求侧，响应侧交由上层（若需要）另行记录。
        val contentType = response.header("Content-Type").orEmpty()
        val isStream = contentType.contains("event-stream", ignoreCase = true)
        val responseBody = when {
            !AiWireLog.shouldLogResponseBody() -> null
            isStream -> "<streaming response: not captured at interceptor level>"
            else -> runCatching { response.peekBody(MAX_PEEK_BYTES).string() }.getOrNull()
        }

        AiWireLog.logExchange(
            url = request.url.toString(),
            method = request.method,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseCode = response.code,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            durationMs = durationMs,
            error = error,
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { get(it) ?: "" }
    }

    companion object {
        /** peek 上限，避免大响应整体驻留内存 */
        private const val MAX_PEEK_BYTES = 4L * 1024 * 1024
    }
}
