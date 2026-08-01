package me.rerere.workspace

/**
 * 交互式会话的底层 IO 通道抽象。
 *
 * 有两种实现, 能力不同, 会话层按 [supportsSignals] 分流:
 *
 * | | 管道([WorkspaceBackgroundProcess]) | pty(WorkspacePtySession) |
 * |---|---|---|
 * | 中断方式 | 遍历 /proc 逐个 kill | 写 `\u0003`, 内核投递给前台进程组 |
 * | 能否中断 bash 自身的循环 | **不能**(打地鼠, 已实测) | 能 |
 * | 依赖 | 无 | termux JNI(缺失则不可用) |
 *
 * pty 是首选; 仅当 JNI 不可用时降级到管道, 并在中断能力上打折。
 */
interface WorkspaceSessionChannel {
    val isAlive: Boolean

    /** true 表示写入 `\u0003` 可产生真正的 SIGINT(即有 pty line discipline) */
    val supportsSignals: Boolean

    fun writeStdin(text: String)

    fun readStdoutSince(cursor: Long): ShellStreamChunk

    fun stdoutText(): String

    /** 等待进程结束, 返回是否已结束 */
    fun waitFor(millis: Long): Boolean

    fun kill()

    fun touch()
}
