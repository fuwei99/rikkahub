package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.ShellStreamChunk
import me.rerere.workspace.ShellStreamCollector
import me.rerere.workspace.WorkspaceExternalMount
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceSessionChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Method

private const val TAG = "WorkspacePtySession"

/**
 * 基于 pty 的交互式 shell 会话。
 *
 * ## 为什么要 pty
 * 无 pty 的管道会话有两个治不好的毛病(2026-08-01 实测):
 * 1. 向 stdin 写 `\u0003` 不产生 SIGINT, 只会被当普通字节, 污染下一条命令。
 * 2. 只能对「会话 bash 的直系子进程」发 SIGINT, 治不了 `while true; do sleep 1; done`
 *    这类循环 —— 杀掉当前 sleep, 循环立刻起新的, 变成打地鼠。
 *
 * pty 给了两样东西, 正好各治一条:
 * - **line discipline**: 写入 `\u0003` 由内核翻译成 SIGINT。
 * - **前台进程组**: 内核把 SIGINT 投递给整个前台进程组(循环连同 sleep 一起),
 *   而不是我们手工挑出来的单个 pid。
 *
 * ## 为什么复用 termux 的 JNI 而不自己写
 * `workspace/src/main/cpp/termux_pty.cpp` 已实现 `posix_openpt` + `setsid` +
 * `TIOCSCTTY` + `dup2` 全套。但**它只在本地有 NDK 时才编译**
 * (见 workspace/build.gradle.kts 的 ndkPath 判断), CI 不配 NDK, 跑的是
 * terminal-view AAR 自带的 libtermux.so。
 *
 * 所以这里通过反射调用 `com.termux.terminal.JNI` 的**上游标准方法**, 不新增任何
 * native 符号 —— 加符号会在 CI(无 NDK)构建的包上炸 UnsatisfiedLinkError。
 * 反射而非直接引用, 是为了让 JNI 缺失时能优雅降级回管道会话, 而不是崩溃。
 */
class WorkspacePtySession private constructor(
    val ptyFd: Int,
    val shellPid: Int,
    private val output: ShellStreamCollector,
    private val input: FileOutputStream,
    private val parcelFd: ParcelFileDescriptor,
) : WorkspaceSessionChannel {
    @Volatile
    private var closed = false

    @Volatile
    private var exitCodeCache: Int? = null

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set

    /** pty 有 line discipline, 写 `\u0003` 由内核翻译成 SIGINT 投给前台进程组 */
    override val supportsSignals: Boolean get() = true

    override val isAlive: Boolean
        get() {
            if (closed) return false
            // 用 /proc 探活: waitFor 会阻塞, 不能在这里调
            return runCatching { File("/proc/$shellPid").exists() }.getOrDefault(false)
        }

    override fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    override fun writeStdin(text: String) {
        check(!closed) { "Pty session already closed" }
        synchronized(input) {
            input.write(text.toByteArray(Charsets.UTF_8))
            input.flush()
        }
        touch()
    }

    /**
     * 发送 SIGINT。
     * 写入 `\u0003`(ETX): pty line discipline 会把它翻译成 SIGINT 并投递给
     * **前台进程组**, 因此 `while` 循环连同其子进程一起收到, 不再打地鼠。
     */
    fun sendInterrupt() = writeStdin("\u0003")

    override fun readStdoutSince(cursor: Long): ShellStreamChunk =
        output.readSince(cursor).also { touch() }

    override fun stdoutText(): String = output.text()

    fun cursor(): Long = output.cursor()

    fun truncated(): Boolean = output.truncated

    fun exitCode(): Int? = exitCodeCache

    override fun waitFor(millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive) return true
            Thread.sleep(50)
        }
        return !isAlive
    }

    override fun kill() = close()

    fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        // parcelFd 拥有 fd 所有权, 关它就等于关 fd; 不再另调 Jni.close 避免双重关闭
        runCatching { parcelFd.close() }
        output.join(1_000)
        // 收尸避免僵尸进程; 此时 fd 已关, 子进程会收到 SIGHUP 退出
        runCatching { exitCodeCache = Jni.waitFor(shellPid) }
    }

    companion object {
        private const val WORKSPACE_DIR = "/workspace"
        private const val SKILLS_DIR = "/skills"

        /** pty 的窗口尺寸: 列数放大可减少长命令回显被硬折行 */
        private const val PTY_ROWS = 60
        private const val PTY_COLS = 512

        val available: Boolean get() = Jni.available

        /**
         * 启动一个 pty 会话, 参数构造与 UI 终端页(createWorkspaceTerminalSession)保持一致。
         * @return null 表示 pty 不可用(应降级到管道会话)
         */
        fun start(
            context: Context,
            root: String,
            mounts: List<WorkspaceExternalMount>,
            cwd: String,
            maxOutputChars: Int,
        ): WorkspacePtySession? {
            if (!Jni.available) return null
            val appContext = context.applicationContext
            val workspaceBaseDir = File(appContext.filesDir, "workspaces")
            val workspaceDir = File(workspaceBaseDir, root)
            val filesDir = File(workspaceDir, "files").apply { mkdirs() }
            val linuxDir = File(workspaceBaseDir, WorkspaceManager.SHARED_ROOTFS_DIR)
            val tempDir = File(workspaceDir, "tmp").apply { mkdirs() }
            val skillsDir = File(appContext.filesDir, FileFolders.SKILLS).apply { mkdirs() }
            val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
            val proot = File(nativeLibraryDir, "libproot_exec.so")
            val loader = File(nativeLibraryDir, "libproot_loader.so")
            if (!proot.exists()) {
                Log.w(TAG, "proot not found, cannot start pty session")
                return null
            }
            RootfsPatcher().patch(linuxDir, RootfsPatchOptions())

            val validMounts = mounts.filter { File(it.sourcePath).isDirectory }
            val workspaceOverridden = validMounts.any { it.normalizedTargetPath() == WORKSPACE_DIR }
            val args = mutableListOf(
                "--root-id",
                "--link2symlink",
                "--kill-on-exit",
                "-r",
                linuxDir.absolutePath,
                "-w",
                resolveStartDir(cwd),
            )
            if (!workspaceOverridden) {
                args += "-b"
                args += "${filesDir.absolutePath}:$WORKSPACE_DIR"
            }
            args += "-b"
            args += "${skillsDir.absolutePath}:$SKILLS_DIR"
            validMounts.forEach { mount ->
                args += "-b"
                args += "${File(mount.sourcePath).absolutePath}:${mount.normalizedTargetPath()}"
            }
            listOf("/dev", "/proc", "/sys").forEach { path ->
                if (File(path).exists()) {
                    args += "-b"
                    args += path
                }
            }
            args += listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                // TERM=dumb: 让 bash/程序别输出光标移动与颜色转义, AI 读的是纯文本。
                // UI 终端页用 xterm-256color 是因为那边有真正的模拟器渲染。
                "TERM=dumb",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "USER=root",
                "SHELL=/bin/bash",
                "/bin/bash",
                "-l",
            )

            val env = arrayOf(
                "PROOT_LOADER=${loader.absolutePath}",
                "PROOT_TMP_DIR=${tempDir.absolutePath}",
                "TMPDIR=${tempDir.absolutePath}",
            )

            val pidHolder = IntArray(1)
            val fd = Jni.createSubprocess(
                proot.absolutePath,
                filesDir.absolutePath,
                args.toTypedArray(),
                env,
                pidHolder,
                PTY_ROWS,
                PTY_COLS,
            )
            if (fd < 0) {
                Log.w(TAG, "createSubprocess failed, fd=$fd")
                return null
            }
            // adoptFd 是公开 API, 接管裸 fd 的所有权。
            // 不用反射 FileDescriptor.descriptor: 那是 hidden field,
            // Android 14+ 的 hidden API 限制可能直接拦掉, 而这里一旦失败整个 pty 就废了。
            val parcelFd = runCatching { ParcelFileDescriptor.adoptFd(fd) }.getOrNull() ?: run {
                Log.w(TAG, "adoptFd failed for pty fd=$fd")
                runCatching { Jni.close(fd) }
                return null
            }
            // 输入与输出共用同一个 pty master fd, 各自包一层流;
            // 关闭交由 [close] 统一做(parcelFd.close 会带下层 fd)。
            val descriptor = parcelFd.fileDescriptor
            return WorkspacePtySession(
                ptyFd = fd,
                shellPid = pidHolder[0],
                output = ShellStreamCollector(FileInputStream(descriptor), maxOutputChars),
                input = FileOutputStream(descriptor),
                parcelFd = parcelFd,
            )
        }

        private fun resolveStartDir(cwd: String): String {
            val normalized = cwd.trim().trimEnd('/')
            if (normalized.isEmpty()) return WORKSPACE_DIR
            if (normalized.startsWith("/")) return normalized
            return "$WORKSPACE_DIR/$normalized"
        }
    }

    /**
     * `com.termux.terminal.JNI` 的反射包装。
     *
     * 反射而非直接调用: terminal-view 是 UI 依赖, 这里只借它的 native 实现;
     * 且一旦 so/类缺失要能降级而不是崩溃([available] = false)。
     */
    private object Jni {
        private var createMethod: Method? = null
        private var closeMethod: Method? = null
        private var waitForMethod: Method? = null

        val available: Boolean by lazy { init() }

        private fun init(): Boolean = runCatching {
            val clazz = Class.forName("com.termux.terminal.JNI")
            createMethod = clazz.getMethod(
                "createSubprocess",
                String::class.java,
                String::class.java,
                Array<String>::class.java,
                Array<String>::class.java,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            closeMethod = clazz.getMethod("close", Int::class.javaPrimitiveType)
            waitForMethod = clazz.getMethod("waitFor", Int::class.javaPrimitiveType)
            true
        }.getOrElse {
            Log.w(TAG, "termux JNI unavailable, pty sessions disabled", it)
            false
        }

        fun createSubprocess(
            cmd: String,
            cwd: String,
            args: Array<String>,
            env: Array<String>,
            processId: IntArray,
            rows: Int,
            cols: Int,
        ): Int = runCatching {
            createMethod?.invoke(null, cmd, cwd, args, env, processId, rows, cols) as? Int ?: -1
        }.getOrElse {
            Log.w(TAG, "createSubprocess threw", it)
            -1
        }

        fun close(fd: Int) {
            runCatching { closeMethod?.invoke(null, fd) }
        }

        fun waitFor(pid: Int): Int? = runCatching {
            waitForMethod?.invoke(null, pid) as? Int
        }.getOrNull()
    }
}
