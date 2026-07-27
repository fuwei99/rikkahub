package me.rerere.common.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private const val MAX_RECENT_LOGS = 100

@Serializable
sealed class LogEntry {
    abstract val id: Uuid
    abstract val timestamp: Long
    abstract val tag: String

    @Serializable
    data class TextLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val message: String
    ) : LogEntry()

    @Serializable
    data class RequestLog(
        override val id: Uuid = Uuid.random(),
        override val timestamp: Long = System.currentTimeMillis(),
        override val tag: String,
        val url: String,
        val method: String,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requestBody: String? = null,
        val responseCode: Int? = null,
        val responseHeaders: Map<String, String> = emptyMap(),
        val durationMs: Long? = null,
        val error: String? = null
    ) : LogEntry()
}

object Logging {
    private val recentLogs = arrayListOf<LogEntry>()
    @Volatile
    private var requestLoggingEnabled = false

    private val logScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var logDir: File? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun initLogDir(filesDir: File) {
        val dir = File(filesDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logDir = dir
        cleanOldLogs()
    }

    fun log(tag: String, message: String) {
        val entry = LogEntry.TextLog(tag = tag, message = message)
        addLog(entry)
        appendToFile(entry)
    }

    fun logRequest(entry: LogEntry.RequestLog) {
        if (!requestLoggingEnabled) return
        addLog(entry)
        appendToFile(entry)
    }

    fun isRequestLoggingEnabled(): Boolean = requestLoggingEnabled

    fun setRequestLoggingEnabled(enabled: Boolean) {
        requestLoggingEnabled = enabled
    }

    private fun addLog(entry: LogEntry) {
        synchronized(recentLogs) {
            recentLogs.add(0, entry)
            if (recentLogs.size > MAX_RECENT_LOGS) {
                recentLogs.removeLastOrNull()
            }
        }
    }

    private fun appendToFile(entry: LogEntry) {
        val dir = logDir ?: return
        logScope.launch {
            try {
                val dateStr = fileDateFormat.format(Date(entry.timestamp))
                val timeStr = dateFormat.format(Date(entry.timestamp))
                val logFile = File(dir, "app_$dateStr.log")
                
                val logLine = when (entry) {
                    is LogEntry.TextLog -> "[$timeStr] [${entry.tag}] ${entry.message}\n"
                    is LogEntry.RequestLog -> "[$timeStr] [${entry.tag}] [HTTP ${entry.method}] ${entry.url} -> Code:${entry.responseCode ?: "N/A"} (${entry.durationMs ?: 0}ms) ${entry.error ?: ""}\n"
                }
                
                logFile.appendText(logLine)
            } catch (_: Exception) {
                // Ignore log file write failure
            }
        }
    }

    private fun cleanOldLogs() {
        val dir = logDir ?: return
        logScope.launch {
            try {
                val now = System.currentTimeMillis()
                val sevenDaysMs = 7L * 24 * 3600 * 1000
                dir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("app_") && file.name.endsWith(".log")) {
                        if (now - file.lastModified() > sevenDaysMs) {
                            file.delete()
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore clean error
            }
        }
    }

    fun getRecentLogs(): List<LogEntry> {
        synchronized(recentLogs) {
            return recentLogs.toList()
        }
    }

    fun getTextLogs(): List<LogEntry.TextLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.TextLog>()
        }
    }

    fun getRequestLogs(): List<LogEntry.RequestLog> {
        synchronized(recentLogs) {
            return recentLogs.filterIsInstance<LogEntry.RequestLog>()
        }
    }

    fun clear() {
        synchronized(recentLogs) {
            recentLogs.clear()
        }
    }
}

