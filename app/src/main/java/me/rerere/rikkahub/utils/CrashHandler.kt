package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.files.AppPaths
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"

// Compose 应用主线程栈随便就 200+ 帧，8k 装不下顶层栈就把 Caused by 截没了。
// 拉到 64k：SharedPreferences 存 String 没硬上限，几十 KB 完全 OK。
private const val MAX_STACKTRACE_LENGTH = 64_000

// 落盘到 files/crash_logs/ 下，方便外部工具（如 workspace mount 到 /rikkahub/files/crash_logs/）
// 直接 grep，不用进安全模式手动复制。保留最近 20 份，够回溯又不至于塞爆。
private const val CRASH_DIR = "crash_logs"
private const val MAX_CRASH_FILES = 20

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val now = Date()
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine("Time: $now")
            // 先把根因（Caused by 链末端）单独写到最上面，避免被顶层长栈挤出上限。
            val root = generateSequence(throwable) { it.cause }.lastOrNull() ?: throwable
            if (root !== throwable) {
                appendLine("== Root cause ==")
                appendLine(root.stackTraceToString())
                appendLine("== Full trace ==")
            }
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完

        // 同步落盘一份带时间戳的文件；失败不影响主流程（Toast/Log 都不能用，进程正在死）。
        runCatching { writeCrashFile(context, now, stackTrace) }
            .onFailure { Log.w(TAG, "write crash file failed", it) }
    }

    private fun writeCrashFile(context: Context, time: Date, content: String) {
        val dir = File(AppPaths.filesDir(context), CRASH_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(time)
        File(dir, "crash_$stamp.log").writeText(content)
        // 按修改时间倒序，超过 MAX_CRASH_FILES 的老文件删掉
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES)
            ?.forEach { runCatching { it.delete() } }
    }
}
