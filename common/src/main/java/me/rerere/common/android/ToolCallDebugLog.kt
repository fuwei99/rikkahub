package me.rerere.common.android

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 工具调用链路专项调试日志（纯旁路，不影响任何业务逻辑）。
 *
 * 输出到 <filesDir>/logs/tool_call_debug.log，应用 files 目录会随
 * /rikkahub-data 挂载到工作区，直接读文件即可定位。
 *
 * 设计成「总开关 + 分通道子开关」：以后要给别的工具加全过程日志，
 * 只需新增一个 channel 常量 + 设置里一个子开关，不动这里的写盘逻辑。
 * 当前通道：
 * - [CHANNEL_ASK_USER]：ask_user 全过程（Pending 事件 → 弹窗 → 提交 → 回投
 *   handleToolApproval → patch 落库 → 续跑生成 → 超时兜底），
 *   用于排查「弹窗填了提交后答案不见了」这类只能靠时序还原的问题。
 *
 * 清理/轮转策略全部由 configure() 从设置注入（日志设置页），不硬编码。
 */
object ToolCallDebugLog {
    const val CHANNEL_ASK_USER = "ask_user"

    private val lock = ReentrantLock()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var enabled: Boolean = false

    /** 已开启的通道子开关（总开关关掉时全部无效） */
    @Volatile
    private var channels: Set<String> = emptySet()

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
    fun configure(
        enabled: Boolean,
        channels: Set<String>,
        maxAgeHours: Int,
        maxLines: Int,
        keepBackups: Int,
    ) {
        lock.withLock {
            this.enabled = enabled
            this.channels = channels
            this.maxAgeHours = maxAgeHours.coerceIn(1, 24 * 30)
            this.maxLines = maxLines.coerceIn(100, 100_000)
            this.keepBackups = keepBackups.coerceIn(1, 10)
        }
    }

    /** 由 App 启动时初始化（AppPaths.filesDir 就绪后调用一次即可）。 */
    fun init(dir: File) {
        logFile = File(dir, "logs/tool_call_debug.log")
        logFile?.parentFile?.mkdirs()
        lineCount = runCatching { logFile?.readLines()?.size ?: 0 }.getOrDefault(0)
    }

    /** 通道是否开启：调用方可用它跳过昂贵的字符串拼接。 */
    fun isChannelEnabled(channel: String): Boolean = enabled && channel in channels

    /**
     * 记录一条通道日志。
     *
     * @param channel 通道名（如 [CHANNEL_ASK_USER]），通道未开启直接丢弃
     * @param stage 阶段标签，建议用「组件.动作」，方便 grep 出单条链路
     * @param message 详情
     */
    fun log(channel: String, stage: String, message: String) {
        if (!isChannelEnabled(channel)) return
        Log.i("ToolDebug/$channel", "$stage | $message")
        writeLine(channel, stage, message)
    }

    /** ask_user 通道快捷方法。 */
    fun askUser(stage: String, message: String) = log(CHANNEL_ASK_USER, stage, message)

    /** ask_user 通道快捷方法（惰性拼串：通道关掉时连 message 都不构造）。 */
    inline fun askUserLazy(stage: String, message: () -> String) {
        if (isChannelEnabled(CHANNEL_ASK_USER)) askUser(stage, message())
    }

    private fun writeLine(channel: String, stage: String, message: String) {
        val file = logFile ?: return
        val line = "[${timeFormat.format(Date())}] [$channel] [$stage] $message\n"
        lock.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                cleanupExpired(file)
                if (lineCount >= maxLines) rotate(file)
                file.appendText(line)
                lineCount++
            }.onFailure { e ->
                Log.w("ToolDebug", "write log failed: ${e.message}")
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
        File(parent, file.name + "." + (keepBackups + 1)).delete()
        for (i in keepBackups downTo 1) {
            val from = if (i == 1) file else File(parent, file.name + "." + (i - 1))
            val to = File(parent, file.name + "." + i)
            if (from.exists()) from.renameTo(to)
        }
        lineCount = 0
    }

    /** 清空主日志与轮转备份（日志设置页「清空日志」用）。 */
    fun clear() {
        val file = logFile ?: return
        lock.withLock {
            runCatching {
                val parent = file.parentFile
                parent?.listFiles { f -> f.isFile && f.name.startsWith(file.name) }?.forEach { it.delete() }
                lineCount = 0
            }
        }
    }
}
