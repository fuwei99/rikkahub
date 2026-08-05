package me.rerere.common.android

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 记忆图链路专项调试日志（纯旁路，不影响任何业务逻辑）。
 *
 * 输出到 <filesDir>/logs/memory_graph_debug.log，应用 files 目录会随
 * /rikkahub-data 挂载到工作区，直接读文件即可定位。
 *
 * 用法：`MemoryGraphDebugLog.d(tag, "message")`，与 Logcat 风格一致。
 */
object MemoryGraphDebugLog {
    private val lock = ReentrantLock()
    @Volatile
    private var logDir: File? = null
    @Volatile
    private var logFile: File? = null

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 由 App 启动时初始化（AppPaths.filesDir 就绪后调用一次即可）。 */
    fun init(dir: File) {
        logDir = dir
        logFile = File(dir, "logs/memory_graph_debug.log")
        logFile?.parentFile?.mkdirs()
    }

    fun d(tag: String, message: String) {
        Log.d("MGraphDebug/$tag", message)
        writeLine("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i("MGraphDebug/$tag", message)
        writeLine("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w("MGraphDebug/$tag", message)
        writeLine("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e("MGraphDebug/$tag", message, throwable)
        writeLine("E", tag, message + (throwable?.let { "\n" + it.stackTraceToString() } ?: ""))
    }

    private fun writeLine(level: String, tag: String, message: String) {
        val file = logFile ?: return
        val line = "[${timeFormat.format(Date())}] [$level] [$tag] $message\n"
        lock.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
            }.onFailure { e ->
                Log.w("MGraphDebug", "write log failed: ${e.message}")
            }
        }
    }
}
