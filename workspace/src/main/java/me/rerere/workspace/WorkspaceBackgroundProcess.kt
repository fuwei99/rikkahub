package me.rerere.workspace

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 一个正在后台运行(或已结束但输出未被读取)的 shell 进程。
 * 输出由 [ShellStreamCollector] 持续采集, 可读全量快照或按游标读增量。
 *
 * 两种用途:
 * - **后台任务**([pinned] = false): dev server / 定时脚本, 只读输出, 到期由 reap 回收。
 * - **交互式会话**([pinned] = true): 长活 bash, 通过 [writeStdin] 持续喂命令,
 *   不受寿命上限约束, 只受 idle 超时约束。
 */
class WorkspaceBackgroundProcess internal constructor(
    val id: String,
    val root: String,
    val command: String,
    val startedAt: Long,
    /** 交互式会话标记: true 时 [reap] 按 idle 判定而非按总寿命判定 */
    val pinned: Boolean,
    private val process: Process,
    private val stdout: ShellStreamCollector,
    private val stderr: ShellStreamCollector,
) {
    /** 最后一次读写活动时间, 用于 idle 回收 */
    @Volatile
    var lastActivityAt: Long = startedAt
        private set

    /** 进程结束时间; null 表示仍在运行。用于墓碑保留期计算 */
    @Volatile
    private var finishedAt: Long? = null

    val isAlive: Boolean
        get() = process.isAlive.also { alive ->
            if (!alive && finishedAt == null) finishedAt = System.currentTimeMillis()
        }

    /** 进程结束时刻(毫秒); 仍在运行时返回 null */
    fun finishedAt(): Long? {
        isAlive // 触发一次探测以填充 finishedAt
        return finishedAt
    }

    fun exitCode(): Int? =
        if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    /** 等待进程结束, 返回是否已结束 */
    fun waitFor(millis: Long): Boolean =
        process.waitFor(millis, TimeUnit.MILLISECONDS)

    fun kill() {
        runCatching { stdinStream.close() }
        process.destroyForcibly()
        // 回收采集线程(进程被杀后流关闭, 线程会自然退出)
        stdout.join(1_000)
        stderr.join(1_000)
        if (finishedAt == null) finishedAt = System.currentTimeMillis()
    }

    // ---- stdin ----

    private val stdinStream: OutputStream = process.outputStream
    private val stdinLock = Any()

    /**
     * 向进程 stdin 写入文本并 flush。
     * 交互式会话靠它喂命令; 调用方负责自带换行。
     *
     * @throws IllegalStateException 进程已退出或 stdin 已关闭
     */
    fun writeStdin(text: String) {
        check(process.isAlive) { "Process $id is not running" }
        synchronized(stdinLock) {
            try {
                stdinStream.write(text.toByteArray(Charsets.UTF_8))
                stdinStream.flush()
            } catch (e: IOException) {
                throw IllegalStateException("Failed to write stdin of $id: ${e.message}", e)
            }
        }
        touch()
    }

    // ---- 输出读取 ----

    fun stdoutText(): String = stdout.text()
    fun stderrText(): String = stderr.text()
    fun truncated(): Boolean = stdout.truncated || stderr.truncated

    /** 当前 stdout 游标, 用于「从此刻起读增量」 */
    fun stdoutCursor(): Long = stdout.cursor()

    /** 当前 stderr 游标 */
    fun stderrCursor(): Long = stderr.cursor()

    /** 按游标读 stdout 增量 */
    fun readStdoutSince(cursor: Long): ShellStreamChunk =
        stdout.readSince(cursor).also { touch() }

    /** 按游标读 stderr 增量 */
    fun readStderrSince(cursor: Long): ShellStreamChunk =
        stderr.readSince(cursor).also { touch() }
}

/**
 * 后台进程注册表: 跨工具调用保存进程句柄。
 * 同时容纳后台任务与交互式会话, 二者靠 [WorkspaceBackgroundProcess.pinned] 区分。
 */
class WorkspaceBackgroundProcessRegistry {
    private val processes = ConcurrentHashMap<String, WorkspaceBackgroundProcess>()

    fun register(process: WorkspaceBackgroundProcess) {
        processes[process.id] = process
    }

    fun get(id: String): WorkspaceBackgroundProcess? = processes[id]

    fun list(root: String): List<WorkspaceBackgroundProcess> =
        processes.values.filter { it.root == root }.sortedBy { it.startedAt }

    /** 仅后台任务(非会话) */
    fun listTasks(root: String): List<WorkspaceBackgroundProcess> =
        list(root).filter { !it.pinned }

    /** 仅交互式会话 */
    fun listSessions(root: String): List<WorkspaceBackgroundProcess> =
        list(root).filter { it.pinned }

    fun remove(id: String): WorkspaceBackgroundProcess? = processes.remove(id)

    fun aliveCount(root: String): Int =
        processes.values.count { it.root == root && !it.pinned && it.isAlive }

    fun aliveSessionCount(root: String): Int =
        processes.values.count { it.root == root && it.pinned && it.isAlive }

    /**
     * 回收过期条目:
     * - 后台任务: 存活超过 [maxLifetimeMillis] → 杀掉并移除。
     * - 交互式会话: 空闲(无读写)超过 [sessionIdleMillis] → 杀掉并移除, 不受总寿命限制。
     * - 已结束的进程: 保留 [tombstoneMillis] 作为墓碑, 让 exitCode 可被多次读取, 超期才移除。
     */
    fun reap(
        maxLifetimeMillis: Long,
        sessionIdleMillis: Long = maxLifetimeMillis,
        tombstoneMillis: Long = DEFAULT_TOMBSTONE_MILLIS,
    ) {
        val now = System.currentTimeMillis()
        processes.values.toList().forEach { process ->
            if (!process.isAlive) {
                // 墓碑: 死亡后保留一段时间, 避免 exitCode 只能读一次
                val died = process.finishedAt() ?: now
                if (now - died > tombstoneMillis) processes.remove(process.id)
                return@forEach
            }
            val expired = if (process.pinned) {
                now - process.lastActivityAt > sessionIdleMillis
            } else {
                now - process.startedAt > maxLifetimeMillis
            }
            if (expired) {
                process.kill()
                processes.remove(process.id)
            }
        }
    }

    private companion object {
        /** 进程结束后保留记录的时长, 供多次读取 exitCode 与残留输出 */
        const val DEFAULT_TOMBSTONE_MILLIS = 10 * 60 * 1000L
    }
}
