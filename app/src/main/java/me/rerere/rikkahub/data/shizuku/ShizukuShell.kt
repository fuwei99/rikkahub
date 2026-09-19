package me.rerere.rikkahub.data.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 外壳（2026-09-19）。
 *
 * ## 这是什么
 *
 * Shizuku 借一次「无线调试 / root」把服务端跑成 **shell(uid 2000)**，再通过 binder
 * 把这份权限借给应用。拿到之后 `pm grant` / `appops set` / `settings put` / `am` /
 * `cmd` / `dumpsys` 这些平时只能靠 adb 敲的命令，应用自己就能执行。
 *
 * ## 为什么需要它
 *
 * workspace 里那个 shell 是应用进程的子进程（uid 就是应用 uid），既看不见 `/system`
 * 也摸不到 binder —— 想从 shell 侧直接要 ADB 权限是死路。唯一合法入口就是应用
 * 自己通过 Shizuku 拿。
 *
 * ## 它不负责「跑」
 *
 * 这里只管**开进程**；读流、超时、收尾全在 [ShellRunner] 里做，因为本地模式
 * （不走 Shizuku）要走同一套收尾逻辑，没必要写两份。
 *
 * ## 为什么不用 `Shizuku.newProcess`
 *
 * 因为它在 Shizuku 13.1.5 里是 **private static**（反编译 `api-13.1.5.aar` 确认）。
 * 公开的替代路径是直接拿 `Shizuku.getBinder()` 转成
 * `moe.shizuku.server.IShizukuService`，调它自己的 `newProcess()` ——
 * `Shizuku.newProcess` 内部干的就是这件事，我们只是把它抄出来。
 *
 * 拿到的 `IRemoteProcess` 也没法直接塞进 `ShizukuRemoteProcess`
 * （那个构造函数是 package-private），所以自己包一层 [ShizukuProcess]。
 *
 * ## 前置条件
 *
 * 1. 设备装了 Shizuku 且已启动（无线配对 / root）
 * 2. 本应用在 Shizuku 里被授权
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

    /** 能干活吗：binder 通 + 已授权 */
    fun isReady(): Boolean = isBinderAlive() && isPermissionGranted()

    /** Shizuku 服务进程的 uid —— 也就是命令会以什么身份跑。2000=shell，0=root，-1=拿不到 */
    fun serverUid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    /** Shizuku 服务版本号；拿不到返回 -1 */
    fun version(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(-1)

    /**
     * 以 shell(uid 2000) 身份起一个 `sh -c <command>` 进程。
     *
     * 用 `sh -c` 包一层，所以管道、重定向、`&&` 都能用。
     *
     * @return 起不来时返回 null（Shizuku 没跑 / 没授权 / binder 抛异常），
     *         具体原因查 [isBinderAlive] 和 [isPermissionGranted]。
     */
    fun newProcess(command: String): Process? {
        if (!isBinderAlive()) {
            Log.w(TAG, "newProcess: shizuku binder not alive")
            return null
        }
        if (!isPermissionGranted()) {
            Log.w(TAG, "newProcess: shizuku permission not granted")
            return null
        }

        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (binder == null) {
            Log.w(TAG, "newProcess: Shizuku.getBinder() returned null")
            return null
        }

        return runCatching {
            val service = IShizukuService.Stub.asInterface(binder)
            // 显式声明成可空：AIDL 生成的 Java 方法没有 @Nullable 注解，
            // Kotlin 看到的是平台类型，直接塞 null 有歧义风险。
            val argv = arrayOf("sh", "-c", command)
            val env: Array<String>? = null
            val dir: String? = null
            val remote = service.newProcess(argv, env, dir)
            ShizukuProcess(remote)
        }.onFailure {
            Log.w(TAG, "newProcess failed", it)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ShizukuShell"

        /** Shizuku 权限请求的 requestCode，随便挑一个正的 */
        const val PERMISSION_REQUEST_CODE = 0x5A1F
    }
}

/**
 * 把 [IRemoteProcess] 包成标准 [Process]。
 *
 * 存在的唯一理由：`rikka.shizuku.ShizukuRemoteProcess` 那个接收 `IRemoteProcess`
 * 的构造函数是 package-private，跨包调不到。
 *
 * 退出判定用轮询 [IRemoteProcess.alive]（40ms 一次）。没去用
 * `IRemoteProcess.waitForTimeout(long, String)` —— 它第二个参数是 String，
 * 语义在 AIDL 里不明确，不如自己轮询可控。
 */
private class ShizukuProcess(private val remote: IRemoteProcess) : Process() {

    override fun getOutputStream(): OutputStream =
        ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)

    override fun getInputStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)

    override fun getErrorStream(): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

    override fun waitFor(): Int {
        while (aliveRemote()) sleepQuietly(POLL_MS)
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (aliveRemote()) {
            if (System.nanoTime() >= deadline) return false
            sleepQuietly(POLL_MS)
        }
        return true
    }

    override fun exitValue(): Int {
        // 默认的 Process.waitFor(timeout, unit) 就是靠「轮询 exitValue + 抓
        // IllegalThreadStateException」判活的，这里必须照规矩抛，否则超时会失效。
        if (aliveRemote()) throw IllegalThreadStateException("process hasn't exited")
        return runCatching { remote.exitValue() }.getOrDefault(-1)
    }

    override fun destroy() {
        runCatching { remote.destroy() }
    }

    override fun isAlive(): Boolean = aliveRemote()

    private fun aliveRemote(): Boolean = runCatching { remote.alive() }.getOrDefault(false)

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    companion object {
        private const val POLL_MS = 40L
    }
}
