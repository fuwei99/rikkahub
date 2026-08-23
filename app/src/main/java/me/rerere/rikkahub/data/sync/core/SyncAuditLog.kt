package me.rerere.rikkahub.data.sync.core

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.files.AppPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步审计日志（大统一方案 v2 §6）。
 *
 * ## 为什么需要
 *
 * 2026-08-23 的两个事故（supervision 解锁被同步覆盖、pullNodeIncremental 裁空对话）
 * 都属于「同步机制静默地做了一个错误裁决」。这类问题在 logcat 里几乎无法回溯：
 * 覆盖行为本身不报错，等用户发现设置变了、消息没了，现场早就没了。
 *
 * 因此凡是**本地数据被云端改写**或**触发安全阀**的时刻，一律留一行结构化审计，
 * 落盘到 `files/logs/sync-audit.log`，供开发者页「同步诊断」直接查看。
 *
 * ## 记什么
 *
 * - `field-overwrite`：某个 settings 字段的本地值被云端值取代（含两边 hlc 与裁决依据）
 * - `node-pull-abort`：node 增量重建触发「永不减员」安全阀
 * - `fork`：会话分叉裁决
 * - `supervision-event`：监督锁事件的产生与应用
 *
 * 只追加、按大小滚动，单文件上限 512KB，滚动保留 1 个历史文件，
 * 避免长期运行把用户存储写爆。
 */
object SyncAuditLog {

    private const val TAG = "SyncAuditLog"
    private const val RELATIVE_PATH = "logs/sync-audit.log"
    private const val MAX_BYTES = 512 * 1024L

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var enabled: Boolean = true

    fun write(context: Context, category: String, message: String) {
        if (!enabled) return
        runCatching {
            val file = File(AppPaths.filesDir(context), RELATIVE_PATH)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) {
                val backup = File(file.parentFile, "sync-audit.log.1")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            file.appendText("${stamp.format(Date())} [$category] $message\n")
        }.onFailure {
            Log.w(TAG, "audit write failed: $category", it)
        }
    }

    /** 读取最近 N 行，供开发者页诊断展示 */
    fun tail(context: Context, lines: Int = 200): List<String> = runCatching {
        val file = File(AppPaths.filesDir(context), RELATIVE_PATH)
        if (!file.isFile) return emptyList()
        file.readLines().takeLast(lines)
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching {
            File(AppPaths.filesDir(context), RELATIVE_PATH).delete()
            File(AppPaths.filesDir(context), "logs/sync-audit.log.1").delete()
        }
    }
}
