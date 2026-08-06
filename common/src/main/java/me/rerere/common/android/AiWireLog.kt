package me.rerere.common.android

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AI 请求线路日志（纯旁路，不影响任何业务逻辑）。
 *
 * 输出到 `<filesDir>/logs/ai_wire.log`，应用 files 目录随 /rikkahub-data 挂载到工作区，
 * 直接读文件即可看到**实际发给 LLM 的完整内容**：URL、请求头、请求 payload、响应头、响应体。
 *
 * 与 [Logging] 的区别：[Logging] 的 RequestLog 只把摘要行写盘（body 仅存内存 100 条供 UI 看），
 * 这里是完整落盘，专门用于排查 payload 层面的问题（如图片以 URL 还是 base64 发送）。
 *
 * 策略全部由 configure() 从设置注入（日志设置页 → 请求日志），不硬编码：
 * - enabled：总开关，默认关（payload 含密钥与图片数据，不能默认写盘）；
 * - maxAgeHours：超过 N 小时的日志自动清除，默认 1 小时；
 * - maxBodyChars：单条 body 截断上限，防 base64 图片撑爆日志；
 * - includeResponseBody：是否记录响应体。
 */
object AiWireLog {
    private const val TAG = "AiWireLog"

    private val lock = ReentrantLock()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var maxAgeHours: Int = 1

    @Volatile
    private var maxBodyChars: Int = 200_000

    @Volatile
    private var includeResponseBody: Boolean = true

    /** 按小时清理的节流时间戳（避免每次写都扫目录） */
    private var lastCleanupAt = 0L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 需要脱敏的请求头（只保留末 4 位，便于确认用的是哪把 key 而不泄露全量） */
    private val SENSITIVE_HEADERS = setOf(
        "authorization", "x-api-key", "x-goog-api-key", "api-key", "cookie", "set-cookie",
    )

    fun init(filesDir: File) {
        val file = File(filesDir, "logs/ai_wire.log")
        file.parentFile?.mkdirs()
        logFile = file
    }

    fun configure(
        enabled: Boolean,
        maxAgeHours: Int,
        maxBodyChars: Int,
        includeResponseBody: Boolean,
    ) {
        lock.withLock {
            this.enabled = enabled
            this.maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30)
            this.maxBodyChars = maxBodyChars.coerceIn(1_000, 5_000_000)
            this.includeResponseBody = includeResponseBody
        }
    }

    fun isEnabled(): Boolean = enabled

    fun shouldLogResponseBody(): Boolean = enabled && includeResponseBody

    /** 清空日志（含轮转备份）。日志设置页「清空日志」按钮调用。 */
    fun clear() {
        lock.withLock {
            runCatching {
                val file = logFile ?: return@runCatching
                val parent = file.parentFile ?: return@runCatching
                parent.listFiles { f -> f.isFile && f.name.startsWith(file.name) }
                    ?.forEach { it.delete() }
            }.onFailure { Log.w(TAG, "clear failed: ${it.message}") }
        }
    }

    /**
     * 记录一次完整的 API 交互。
     *
     * @param requestBody 实际发出的 payload（未压缩前的 UTF-8 文本）
     * @param responseBody 响应体；流式请求可传拼接后的完整 SSE，或 null
     */
    fun logExchange(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        requestBody: String?,
        responseCode: Int?,
        responseHeaders: Map<String, String>,
        responseBody: String?,
        durationMs: Long?,
        error: String?,
    ) {
        if (!enabled) return
        val file = logFile ?: return

        val text = buildString {
            append("\n===== [").append(timeFormat.format(Date())).append("] ")
            append(method).append(' ').append(url).append('\n')
            append("-- status: ").append(responseCode ?: "N/A")
            durationMs?.let { append(" (").append(it).append("ms)") }
            append('\n')
            error?.let { append("-- error: ").append(it).append('\n') }

            append("-- request headers --\n")
            requestHeaders.forEach { (k, v) -> append("  ").append(k).append(": ").append(mask(k, v)).append('\n') }

            append("-- request body --\n")
            append(truncate(requestBody)).append('\n')

            if (responseHeaders.isNotEmpty()) {
                append("-- response headers --\n")
                responseHeaders.forEach { (k, v) -> append("  ").append(k).append(": ").append(mask(k, v)).append('\n') }
            }

            if (includeResponseBody && responseBody != null) {
                append("-- response body --\n")
                append(truncate(responseBody)).append('\n')
            }
            append("===== end =====\n")
        }

        lock.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                cleanupExpired(file)
                file.appendText(text)
            }.onFailure { Log.w(TAG, "write failed: ${it.message}") }
        }
    }

    private fun mask(name: String, value: String): String {
        if (name.lowercase() !in SENSITIVE_HEADERS) return value
        val tail = value.takeLast(4)
        return "***(len=${value.length}, tail=$tail)"
    }

    private fun truncate(body: String?): String {
        if (body == null) return "<null>"
        if (body.length <= maxBodyChars) return body
        return body.take(maxBodyChars) + "\n...<truncated ${body.length - maxBodyChars} chars of ${body.length}>"
    }

    /** 删除超过 maxAgeHours 的日志文件。一分钟节流一次。 */
    private fun cleanupExpired(file: File) {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < 60_000L) return
        lastCleanupAt = now
        val cutoff = now - maxAgeHours * 3_600_000L
        // 主日志按「首行写入时间」无法判断，直接用 lastModified：超期即整体清掉，
        // 排查场景下等价于「只保留最近 N 小时」。
        val parent = file.parentFile ?: return
        parent.listFiles { f -> f.isFile && f.name.startsWith(file.name) }?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }
}
