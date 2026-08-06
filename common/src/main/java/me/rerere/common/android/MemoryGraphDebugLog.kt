package me.rerere.common.android

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 记忆链路专项调试日志（纯旁路，不影响任何业务逻辑）。
 *
 * 输出到 <filesDir>/logs/memory_graph_debug.log，应用 files 目录会随
 * /rikkahub-data 挂载到工作区，直接读文件即可定位。
 * 用法：`MemoryGraphDebugLog.d(tag, "message")`，与 Logcat 风格一致。
 *
 * 清理/轮转策略全部由 configure() 从设置注入（记忆日志设置页），不硬编码：
 * - enabled：总开关，排查完成后可关掉，release 不再写盘；
 * - maxAgeHours：超过 N 小时的文件（含轮转备份）自动清除；
 * - maxLines：主日志行数超过 N 时滚动（当前 → .1，依次后移）；
 * - keepBackups：轮转保留的备份份数。
 */
object MemoryGraphDebugLog {
    private val lock = ReentrantLock()
    @Volatile
    private var logDir: File? = null
    @Volatile
    private var logFile: File? = null

    @Volatile
    private var enabled: Boolean = true
    @Volatile
    private var maxAgeHours: Int = 24
    @Volatile
    private var maxLines: Int = 5000
    @Volatile
    private var keepBackups: Int = 3

    /** 主日志当前行数（内存计数，写时自增、轮转后归零） */
    private var lineCount = 0
    /** 按小时清理的节流时间戳（避免每次写行都扫目录） */
    private var lastCleanupAt = 0L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 同步设置（App 启动后由 SettingsStore 订阅调用，或设置页修改后调用）。
     * 参数做合法区间收口，防止脏配置把日志打爆/清空。
     */
    fun configure(enabled: Boolean, maxAgeHours: Int, maxLines: Int, keepBackups: Int) {
        lock.withLock {
            this.enabled = enabled
            this.maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30)
            this.maxLines = maxLines.coerceIn(100, 100_000)
            this.keepBackups = keepBackups.coerceIn(1, 10)
        }
    }

    /** 由 App 启动时初始化（AppPaths.filesDir 就绪后调用一次即可）。 */
    fun init(dir: File) {
        logDir = dir
        logFile = File(dir, "logs/memory_graph_debug.log")
        logFile?.parentFile?.mkdirs()
        lineCount = runCatching { logFile?.readLines()?.size ?: 0 }.getOrDefault(0)
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
        if (!enabled) return
        val file = logFile ?: return
        val line = "[${timeFormat.format(Date())}] [$level] [$tag] $message\n"
        lock.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                cleanupExpired(file)
                if (lineCount >= maxLines) rotate(file)
                file.appendText(line)
                lineCount++
            }.onFailure { e ->
                Log.w("MGraphDebug", "write log failed: ${e.message}")
            }
        }
    }

    /** 按小时清理：删除超过 maxAgeHours 的日志文件（含轮转备份）。一分钟节流一次。 */
    private fun cleanupExpired(file: File) {
        val now = System.currentTimeMillis()
        if (now - lastCleanupAt < 60_000L) return
        lastCleanupAt = now
        val cutoff = now - maxAgeHours * 3_600_000L
        val parent = file.parentFile ?: return
        parent.listFiles { f -> f.isFile && f.name.startsWith(file.name) }?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    /** 按条数滚动：当前 → .1，.1 → .2 ……，只保留 [keepBackups] 份备份。 */
    private fun rotate(file: File) {
        val parent = file.parentFile ?: return
        // 先删最旧（第 keepBackups+1 份）
        File(parent, file.name + "." + (keepBackups + 1)).delete()
        // 从后往前依次后移
        for (i in keepBackups downTo 1) {
            val from = if (i == 1) file else File(parent, file.name + "." + (i - 1))
            val to = File(parent, file.name + "." + i)
            if (from.exists()) from.renameTo(to)
        }
        lineCount = 0
    }
}
