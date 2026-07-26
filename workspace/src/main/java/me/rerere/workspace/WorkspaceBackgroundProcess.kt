package me.rerere.workspace

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 一个正在后台运行(或已结束但输出未被读取)的 shell 进程。
 * 输出由 [ShellStreamCollector] 持续采集, 随时可读取快照。
 */
class WorkspaceBackgroundProcess internal constructor(
    val id: String,
    val root: String,
    val command: String,
    val startedAt: Long,
    private val process: Process,
    private val stdout: ShellStreamCollector,
    private val stderr: ShellStreamCollector,
) {
    val isAlive: Boolean get() = process.isAlive

    fun exitCode(): Int? =
        if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

    /** 等待进程结束, 返回是否已结束 */
    fun waitFor(millis: Long): Boolean =
        process.waitFor(millis, TimeUnit.MILLISECONDS)

    fun kill() {
        process.destroyForcibly()
        // 回收采集线程(进程被杀后流关闭, 线程会自然退出)
        stdout.join(1_000)
        stderr.join(1_000)
    }

    fun stdoutText(): String = stdout.text()
    fun stderrText(): String = stderr.text()
    fun truncated(): Boolean = stdout.truncated || stderr.truncated
}

/** 后台进程注册表: 跨工具调用保存进程句柄 */
class WorkspaceBackgroundProcessRegistry {
    private val processes = ConcurrentHashMap<String, WorkspaceBackgroundProcess>()

    fun register(process: WorkspaceBackgroundProcess) {
        processes[process.id] = process
    }

    fun get(id: String): WorkspaceBackgroundProcess? = processes[id]

    fun list(root: String): List<WorkspaceBackgroundProcess> =
        processes.values.filter { it.root == root }.sortedBy { it.startedAt }

    fun remove(id: String): WorkspaceBackgroundProcess? = processes.remove(id)

    fun aliveCount(root: String): Int =
        processes.values.count { it.root == root && it.isAlive }

    /** 杀掉超过生命周期上限的进程并移除记录 */
    fun reap(maxLifetimeMillis: Long) {
        val now = System.currentTimeMillis()
        processes.values.toList().forEach { process ->
            if (now - process.startedAt > maxLifetimeMillis) {
                if (process.isAlive) process.kill()
                processes.remove(process.id)
            }
        }
    }
}
