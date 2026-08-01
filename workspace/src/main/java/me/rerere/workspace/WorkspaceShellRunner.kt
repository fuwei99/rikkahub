package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult

    /**
     * 启动进程但不等待其结束, 供后台任务使用。
     * 环境不可用时抛出 IllegalStateException。
     */
    fun startProcess(context: WorkspaceShellContext): Process
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val maxOutputChars: Int = MAX_OUTPUT_CHARS,
    val stdin: ByteArray? = null,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    /**
     * 是否把 stderr 合并进 stdout。
     * 交互式会话需要开启: 只有交错的单一流才能还原终端上真实的输出顺序。
     */
    val mergeStderr: Boolean = false,
    /**
     * 是否保持 stdin 常开(不写入即关闭)。
     * 交互式会话需要开启, 由调用方通过 [WorkspaceBackgroundProcess.writeStdin] 持续喂数据。
     */
    val keepStdinOpen: Boolean = false,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        return startProcess(context).readResult(context.timeoutMillis, context.stdin, context.maxOutputChars)
    }

    override fun startProcess(context: WorkspaceShellContext): Process =
        ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(context.mergeStderr)
            .start()

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null, maxOutputChars: Int = MAX_OUTPUT_CHARS): WorkspaceCommandResult {
    val stdout = ShellStreamCollector(inputStream, maxOutputChars)
    val stderr = ShellStreamCollector(errorStream, maxOutputChars)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            destroyForcibly()
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

/**
 * 一次游标读取的结果。
 *
 * @param text 本次读到的增量文本
 * @param cursor 下次读取应传入的游标
 * @param dropped 因缓冲区溢出而永久丢失的字符数(发生在上次读取之后)
 */
data class ShellStreamChunk(
    val text: String,
    val cursor: Long,
    val dropped: Long,
)

/**
 * 后台采集进程输出的线程封装, 供同步执行与后台任务共用。
 *
 * 缓冲策略为 **环形(保尾丢头)**: 超过 [maxChars] 时丢弃最旧的内容。
 * 交互式会话关心最新输出, 一次性命令的结论通常也在尾部, 故保尾优于保头。
 * [totalWritten] 单调递增, 作为 [readSince] 的游标基准。
 */
class ShellStreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    /** 累计写入字符数(含已被丢弃的), 单调递增, 作为游标基准 */
    @Volatile
    var totalWritten = 0L
        private set

    /** 累计因溢出丢弃的字符数 */
    @Volatile
    var droppedChars = 0L
        private set

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(builder) {
                        builder.append(buffer, 0, read)
                        totalWritten += read
                        // 环形裁剪: 超限时丢弃最旧内容而非最新内容。
                        // 必须持续读到 EOF, 否则管道写满会阻塞子进程导致其无法退出。
                        val overflow = builder.length - maxChars
                        if (overflow > 0) {
                            builder.delete(0, overflow)
                            droppedChars += overflow
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }

    /** 当前游标位置(即已写入总量), 用于「从此刻起读增量」 */
    fun cursor(): Long = totalWritten

    /**
     * 从 [cursor] 处读取增量。cursor 会被夹到有效区间。
     * 若 cursor 落在已被丢弃的区域, [ShellStreamChunk.dropped] 说明丢了多少字符。
     */
    fun readSince(cursor: Long): ShellStreamChunk = synchronized(builder) {
        val total = totalWritten
        val bufferStart = total - builder.length
        val from = cursor.coerceIn(0L, total)
        val dropped = (bufferStart - from).coerceAtLeast(0L)
        val offset = (from + dropped - bufferStart).toInt().coerceIn(0, builder.length)
        ShellStreamChunk(
            text = builder.substring(offset),
            cursor = total,
            dropped = dropped,
        )
    }
}
