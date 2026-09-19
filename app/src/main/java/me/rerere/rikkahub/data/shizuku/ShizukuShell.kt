package me.rerere.rikkahub.data.shizuku

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** 一次 shell 执行的结果 */
data class ShellExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

/**
 * Shizuku 外壳（2026-09-19）。
 *
 * ## 这是什么
 *
 * Shizuku 借一次「无线调试 / root」把服务端跑成 **shell(uid 2000)**，然后通过 binder
 * 把这份权限借给应用。拿到之后，`pm grant` / `appops set` / `settings put` / `am` /
 * `cmd` / `dumpsys` 这些平时只能靠 adb 敲的命令，应用自己就能执行。
 *
 * ## 为什么需要它
 *
 * 这个应用跑在 proot 里的 workspace shell 是应用进程的子进程（uid 就是应用 uid），
 * 既看不见 `/system`，也摸不到 binder —— 想从 shell 侧要 ADB 权限是死路。
 * 唯一合法入口就是应用自己通过 Shizuku 拿，再对外吐出去。
 *
 * ## 前置条件
 *
 * 1. 设备装了 Shizuku 并且已经启动（无线调试 / root）
 * 2. 本应用在 Shizuku 里被授予了权限（[isPermissionGranted]）
 *
 * 两条缺一，[exec] 会直接返回失败而不是抛异常 —— 调用方按字符串处理即可。
 */
class ShizukuShell {

    /** Shizuku 服务进程活着吗（跟「有没有授权」是两件事） */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本应用拿到 Shizuku 授权了吗 */
    fun isPermissionGranted(): Boolean {
        if (!isBinderAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 能干活吗 */
    fun isReady(): Boolean = isBinderAlive() && isPermissionGranted()

    /**
     * 以 shell(uid 2000) 身份执行一条命令。
     *
     * 用 `sh -c` 包一层，所以管道、重定向、`&&` 都能用。
     *
     * 注意：**必须在非主线程调用**（binder 调用 + 阻塞读流）。这里自己切到 IO，
     * 调用方直接 await 就行。
     */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellExecResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()

            if (!isBinderAlive()) {
                return@withContext fail(startedAt, "Shizuku 服务未运行（binder 不通）")
            }
            if (!isPermissionGranted()) {
                return@withContext fail(startedAt, "Shizuku 未授权本应用")
            }

            val process = runCatching {
                Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            }.getOrElse { t ->
                Log.w(TAG, "newProcess failed", t)
                return@withContext fail(startedAt, "创建 shell 进程失败: ${t.message}")
            }

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutReader = pump(process.inputStream, stdout)
            val stderrReader = pump(process.errorStream, stderr)

            val finished = runCatching {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            }.getOrElse { t ->
                Log.w(TAG, "waitFor failed", t)
                false
            }

            if (!finished) {
                runCatching { process.destroy() }
                stdoutReader.join(READER_JOIN_MS)
                stderrReader.join(READER_JOIN_MS)
                return@withContext ShellExecResult(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = stdout.toString(),
                    stderr = stderr.toString() + "\n[超时 ${timeoutMs}ms，进程已终止]",
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }

            stdoutReader.join(READER_JOIN_MS)
            stderrReader.join(READER_JOIN_MS)

            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            ShellExecResult(
                exitCode = exit,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                durationMs = System.currentTimeMillis() - startedAt,
            )
        }

    private fun fail(startedAt: Long, message: String): ShellExecResult = ShellExecResult(
        exitCode = UNAVAILABLE_EXIT_CODE,
        stdout = "",
        stderr = message,
        durationMs = System.currentTimeMillis() - startedAt,
    )

    /** 把一个流抽干到 [sink]；返回线程，方便 join 等它读完 */
    private fun pump(stream: java.io.InputStream, sink: StringBuilder): Thread =
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
        }.apply { isDaemon = true; start() }

    companion object {
        private const val TAG = "ShizukuShell"

        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 300_000L

        /** 进程超时被杀 */
        const val TIMEOUT_EXIT_CODE = -2

        /** Shizuku 不可用 / 未授权 */
        const val UNAVAILABLE_EXIT_CODE = -1

        private const val READER_JOIN_MS = 1_000L

        /** Shizuku 权限请求的 requestCode，随便挑一个正的 */
        const val PERMISSION_REQUEST_CODE = 0x5A1F
    }
}
