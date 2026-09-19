package me.rerere.rikkahub.data.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 执行模式。
 *
 * 为什么要这个参数：Shizuku 每次重启设备都要重新配对，**大部分时候其实是不可用的**。
 * 如果把「跑命令」和「用 ADB 跑命令」绑死，Shizuku 一掉整条链就废。
 * 所以拆成显式三档，由调用方按需挑。
 */
enum class ShellMode(val id: String) {
    /** 应用自身 uid。**默认档**，永远可用，看不见 /system、跑不了 pm/am/settings */
    LOCAL("local"),

    /** 强制走 shell(uid 2000)。没配对/没授权时**直接报错，不偷偷降级** */
    SHIZUKU("shizuku"),

    /** 能用 Shizuku 就用，不能就退本地。响应里会告诉你实际用了哪个 */
    AUTO("auto");

    companion object {
        /**
         * 宽松解析，认几个别名；认不出来返回 null（让调用方报 400 而不是瞎猜）。
         *
         * **缺省 = [LOCAL]**：这个设备上 Shizuku 每次重启都要重新配对，
         * 大部分时候不可用，所以默认不走 ADB，要用得显式传。
         */
        fun parse(raw: String?): ShellMode? = when (raw?.trim()?.lowercase()) {
            null, "", "local", "app", "normal", "plain", "self" -> LOCAL
            "shizuku", "adb", "shell", "privileged" -> SHIZUKU
            "auto" -> AUTO
            else -> null
        }
    }
}

/** 一次执行的结果。[mode] 是**实际**用上的模式，不是请求的模式 */
data class ShellRunResult(
    val mode: ShellMode,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

/**
 * 命令执行器（2026-09-19）：统一「本地」与「Shizuku(shell 2000)」两条路。
 *
 * 两条路的收尾逻辑（抽流、超时、杀进程）完全一样，所以只有「怎么开进程」这一步分叉，
 * 其余共用 [runProcess]。
 */
class ShellRunner(
    private val shizukuShell: ShizukuShell,
) {

    fun shizukuReady(): Boolean = shizukuShell.isReady()

    /** Shizuku 服务进程活着吗（状态查询用，与「有没有授权」是两件事） */
    fun shizukuBinderAlive(): Boolean = shizukuShell.isBinderAlive()

    /** 本应用在 Shizuku 里被授权了吗（状态查询用） */
    fun shizukuPermissionGranted(): Boolean = shizukuShell.isPermissionGranted()

    /**
     * 跑一条命令。
     *
     * 默认 [ShellMode.LOCAL] —— 不走 ADB。要用 shell(uid 2000) 得显式传 SHIZUKU。
     *
     * - [ShellMode.LOCAL]：永远走本地（应用 uid）
     * - [ShellMode.SHIZUKU]：不可用时直接返回 [UNAVAILABLE_EXIT_CODE]，**不降级**
     * - [ShellMode.AUTO]：Shizuku 就绪就用 Shizuku，否则本地
     */
    suspend fun exec(
        command: String,
        mode: ShellMode = ShellMode.LOCAL,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ShellRunResult {
        val startedAt = System.currentTimeMillis()

        val wantShizuku = when (mode) {
            ShellMode.SHIZUKU -> true
            ShellMode.LOCAL -> false
            ShellMode.AUTO -> shizukuReady()
        }
        val actual = if (wantShizuku) ShellMode.SHIZUKU else ShellMode.LOCAL

        if (wantShizuku && !shizukuReady()) {
            return ShellRunResult(
                mode = actual,
                exitCode = UNAVAILABLE_EXIT_CODE,
                stdout = "",
                stderr = buildString {
                    append("Shizuku 不可用：binder=")
                    append(shizukuShell.isBinderAlive())
                    append(", granted=")
                    append(shizukuShell.isPermissionGranted())
                    append("。先配对启动 Shizuku，或把 mode 换成 auto/local。")
                },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        val process = if (wantShizuku) {
            shizukuShell.newProcess(command)
                ?: return ShellRunResult(
                    mode = actual,
                    exitCode = UNAVAILABLE_EXIT_CODE,
                    stdout = "",
                    stderr = "以 shell(uid 2000) 创建进程失败",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
        } else {
            runCatching { localProcess(command).start() }.getOrElse { t ->
                Log.w(TAG, "local process failed", t)
                return ShellRunResult(
                    mode = actual,
                    exitCode = UNAVAILABLE_EXIT_CODE,
                    stdout = "",
                    stderr = "创建本地进程失败: ${t.message}",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
        }

        return runProcess(process, actual, startedAt, timeoutMs)
    }

    /** 本地模式：以应用自身 uid 跑 `sh -c` */
    private fun localProcess(command: String): ProcessBuilder =
        ProcessBuilder("sh", "-c", command).apply {
            // Android 应用进程的 env 很干净，PATH 得自己补，否则连 ls 都可能找不到
            environment()["PATH"] = LOCAL_PATH
        }

    /** 两条路共用的收尾：抽干 stdout/stderr → 等退出 → 超时就杀 */
    private suspend fun runProcess(
        process: Process,
        mode: ShellMode,
        startedAt: Long,
        timeoutMs: Long,
    ): ShellRunResult = withContext(Dispatchers.IO) {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = pump(process.inputStream, stdout)
        val stderrReader = pump(process.errorStream, stderr)

        val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrElse { t ->
                Log.w(TAG, "waitFor failed", t)
                false
            }

        if (!finished) {
            runCatching { process.destroy() }
            stdoutReader.join(READER_JOIN_MS)
            stderrReader.join(READER_JOIN_MS)
            return@withContext ShellRunResult(
                mode = mode,
                exitCode = TIMEOUT_EXIT_CODE,
                stdout = stdout.toString(),
                stderr = stderr.toString() + "\n[超时 ${timeoutMs}ms，进程已终止]",
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

        stdoutReader.join(READER_JOIN_MS)
        stderrReader.join(READER_JOIN_MS)

        ShellRunResult(
            mode = mode,
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** 把一个流抽干到 [sink]；返回线程，方便 join 等它读完 */
    private fun pump(stream: InputStream, sink: StringBuilder): Thread =
        Thread {
            runCatching {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        synchronized(sink) { sink.append(buf, 0, n) }
                    }
                }
            }.onFailure { Log.d(TAG, "pump stream ended: ${it.message}") }
        }.apply {
            isDaemon = true
            start()
        }

    companion object {
        private const val TAG = "ShellRunner"

        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 300_000L

        /** 进程超时被杀 */
        const val TIMEOUT_EXIT_CODE = -2

        /** Shizuku 不可用 / 进程起不来 */
        const val UNAVAILABLE_EXIT_CODE = -1

        private const val READER_JOIN_MS = 1_000L

        private const val LOCAL_PATH = "/system/bin:/system/xbin:/vendor/bin:/product/bin"
    }
}
